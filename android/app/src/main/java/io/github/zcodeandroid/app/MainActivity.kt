package io.github.zcodeandroid.app

import android.Manifest
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.zcodeandroid.app.local.LocalServerService
import io.github.zcodeandroid.app.local.Provisioner
import io.github.zcodeandroid.app.local.RuntimeBus
import io.github.zcodeandroid.app.local.RuntimeState
import io.github.zcodeandroid.app.remote.RemoteControlKeepAliveService
import io.github.zcodeandroid.app.remote.RemoteControlRecentLink
import io.github.zcodeandroid.app.remote.RemoteControlRecentLinksStore
import io.github.zcodeandroid.app.remote.RemoteControlUrlPolicy
import io.github.zcodeandroid.app.native.NativeZcodeApp
import io.github.zcodeandroid.app.ui.LocalApp
import io.github.zcodeandroid.app.ui.LocalWebViewScreen
import io.github.zcodeandroid.app.ui.RemoteControlApp
import io.github.zcodeandroid.app.ui.theme.ZcodeTheme

/**
 * Entry point. Three modes:
 *  - native (default): the native ZCode shell — silent first-run, native task
 *    list + conversation over the pinned channel protocol, Z.ai sign-in via
 *    the oauth channel. The WebView is NOT part of this path (owner decision);
 *  - webFallback: explicit "Open web UI (fallback)" from native settings —
 *    the proven WebView path kept as an escape hatch during migration;
 *  - local: diagnostics/log console screen (LocalApp);
 *  - remote: the official remote-control client (deep links only, out of the
 *    product surface per owner decision; code kept until migration completes).
 * Deep links (zcode://remote, https://zcode.z.ai/remote) open remote mode.
 */
class MainActivity : ComponentActivity() {
    private var mode by mutableStateOf(MODE_NATIVE)
    private var remoteUrl by mutableStateOf<String?>(null)
    private var recentLinks by mutableStateOf<List<RemoteControlRecentLink>>(emptyList())
    private var themeMode by mutableStateOf(THEME_SYSTEM)

    private val prefs by lazy { getSharedPreferences("zcode_native", Context.MODE_PRIVATE) }

    /**
     * Follow the SYSTEM dark setting explicitly. The runtime architecture
     * pins targetSdk to 28 (Android 10+ blocks exec() from app-writable
     * storage, so the embedded Bionic node could not run otherwise) — and
     * apps targeting pre-Q do not receive the system night mode in their
     * Configuration: on a dark phone this app (and the WebView's
     * prefers-color-scheme, which the official ZCode web UI follows) stayed
     * light. Owner report 2026-10-31: light UI on a dark phone, no dark
     * theme. Fix: read the real system night mode from UiModeManager (a
     * system service, unaffected by the targetSdk legacy behavior) and
     * qualify the base context with UI_MODE_NIGHT_YES when the system is
     * dark. Compose then picks the dark scheme, values-night themes apply,
     * and the WebView reports prefers-color-scheme: dark.
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(applySystemNightMode(newBase))
    }

    private fun applySystemNightMode(base: Context): Context {
        val wantsDark = runCatching {
            (base.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)
                ?.nightMode == UiModeManager.MODE_NIGHT_YES
        }.getOrDefault(false)
        val current = base.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (!wantsDark || current == Configuration.UI_MODE_NIGHT_YES) return base
        val cfg = Configuration(base.resources.configuration)
        cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            Configuration.UI_MODE_NIGHT_YES
        return base.createConfigurationContext(cfg)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // failure-3 A/B instrument (emulator evidence 2026-10-28: the WebView's
        // viewport-unit chain collapses to 0 under edge-to-edge — the suspected
        // trigger is the edge-to-edge <-> WebView insets interaction). The
        // marker file DISABLES edge-to-edge for controlled CI experiments;
        // default behavior is unchanged. Diagnostic hook, not a product switch.
        val edgeToEdgeDisabledByMarker = runCatching {
            java.io.File(filesDir, "config-edge-to-edge-off").isFile
        }.getOrDefault(false)
        if (edgeToEdgeDisabledByMarker) {
            RuntimeBus.append("edge-to-edge: OFF (config-edge-to-edge-off marker present — A/B experiment config)")
        } else {
            enableEdgeToEdge()
        }
        if (BuildConfig.DEBUG) {
            android.webkit.WebView.setWebContentsDebuggingEnabled(true)
        }
        recentLinks = RemoteControlRecentLinksStore.read(this)
        themeMode = prefs.getInt(KEY_THEME_MODE, THEME_SYSTEM)
        // CI/diagnostic hook (same family as config-edge-to-edge-off): when the
        // marker exists, the app boots straight into the WebView fallback once
        // the runtime is Running — the deliberate native path keeps its
        // buttons out of the product, and the verify stages keep driving the
        // fallback (web-UI) surface reproducibly.
        if (runCatching { java.io.File(filesDir, "config-web-fallback").isFile }.getOrDefault(false)) {
            if (mode == MODE_NATIVE) {
                mode = MODE_WEB_FALLBACK
                RuntimeBus.append("web fallback: ON (config-web-fallback marker present — experiment config)")
            }
        }
        requestStoragePermissionIfNeeded()

        val restoredRemoteUrl = savedInstanceState?.getString(KEY_REMOTE_URL)
        val launchRemoteUrl = restoredRemoteUrl ?: resolveRemoteUrlFromIntent(intent)
        if (launchRemoteUrl != null) {
            mode = MODE_REMOTE
            openRemoteUrl(launchRemoteUrl)
        }

        setContent {
            val runtimeState by RuntimeBus.state.collectAsState()
            // Silent first-run: ONE owner for provisioning + start so the
            // native shell and the CI fallback marker can never double-run
            // extraction. Native and web-fallback modes share it; the
            // diagnostics (local) and remote modes keep their manual flows.
            LaunchedEffect(mode) {
                if (mode == MODE_NATIVE || mode == MODE_WEB_FALLBACK) {
                    val s = runtimeState
                    if (s is RuntimeState.NotProvisioned) {
                        if (Provisioner.isProvisioned(this@MainActivity)) {
                            RuntimeBus.setState(RuntimeState.Ready)
                        } else {
                            Provisioner.provision(this@MainActivity)
                        }
                    }
                    val after = RuntimeBus.state.value
                    if (after is RuntimeState.Ready || after is RuntimeState.Stopped) {
                        LocalServerService.start(this@MainActivity)
                    }
                }
            }
            ZcodeTheme(
                darkTheme = when (themeMode) {
                    THEME_LIGHT -> false
                    THEME_DARK -> true
                    else -> isSystemInDarkTheme()
                },
            ) {
                when (mode) {
                    MODE_NATIVE -> NativeZcodeApp(
                        themeMode = themeMode,
                        onSetThemeMode = { value ->
                            themeMode = value
                            prefs.edit().putInt(KEY_THEME_MODE, value).apply()
                        },
                        onOpenWebFallback = { mode = MODE_WEB_FALLBACK },
                        onOpenDiagnostics = { mode = MODE_LOCAL },
                    )
                    MODE_WEB_FALLBACK -> {
                        when (val running = runtimeState) {
                            is RuntimeState.Running ->
                                LocalWebViewScreen(url = running.url, onExit = { mode = MODE_NATIVE })
                            is RuntimeState.Failed -> Column(
                                modifier = Modifier.fillMaxSize().padding(24.dp),
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Text(
                                    "ZCode could not start: ${running.message}",
                                    color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                                )
                            }
                            else -> Column(
                                modifier = Modifier.fillMaxSize().padding(24.dp),
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Text("Preparing ZCode…")
                            }
                        }
                    }
                    MODE_LOCAL -> LocalApp(
                        onOpenRemote = {
                            mode = MODE_REMOTE
                            openRemoteUrl(BuildConfig.REMOTE_CONTROL_DEFAULT_URL)
                        },
                        onBackToNative = { mode = MODE_NATIVE },
                    )
                    else -> {
                        RemoteControlApp(
                            remoteUrl = remoteUrl,
                            recentLinks = recentLinks,
                            onOpenUrl = ::openRemoteUrl,
                            onCloseRemote = {
                                closeRemote()
                                mode = MODE_NATIVE
                            },
                            onClearRecentLinks = ::clearRecentLinks,
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        resolveRemoteUrlFromIntent(intent)?.let {
            mode = MODE_REMOTE
            openRemoteUrl(it)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        remoteUrl?.let { outState.putString(KEY_REMOTE_URL, it) }
        outState.putString(KEY_MODE, mode)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        mode = savedInstanceState.getString(KEY_MODE, MODE_NATIVE)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Do NOT stop the runtime here: the foreground service keeps it alive
        // and the notification offers an explicit Stop action.
        if (isFinishing) {
            RuntimeBus.append("activity destroyed; runtime kept by foreground service")
        }
    }

    private fun openRemoteUrl(rawUrl: String): Boolean {
        val resolvedUrl = RemoteControlUrlPolicy.resolve(rawUrl) ?: return false
        recentLinks = RemoteControlRecentLinksStore.record(this, resolvedUrl)
        remoteUrl = resolvedUrl
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestNotificationPermissionIfNeeded()
        RemoteControlKeepAliveService.start(this, resolvedUrl)
        return true
    }

    private fun closeRemote() {
        remoteUrl = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        RemoteControlKeepAliveService.stop(this)
    }

    private fun clearRecentLinks() {
        recentLinks = RemoteControlRecentLinksStore.clear(this)
    }

    private fun resolveRemoteUrlFromIntent(intent: Intent?): String? {
        if (intent == null) {
            return null
        }

        intent.getStringExtra(RemoteControlKeepAliveService.EXTRA_REMOTE_URL)?.let { raw ->
            RemoteControlUrlPolicy.resolve(raw)?.let { return it }
        }
        intent.data?.let { uri ->
            RemoteControlUrlPolicy.resolve(uri)?.let { return it }
        }
        intent.getStringExtra(Intent.EXTRA_TEXT)?.let { raw ->
            RemoteControlUrlPolicy.resolve(raw)?.let { return it }
        }

        return null
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return
        }
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
    }

    /**
     * Legacy external storage (targetSdk 28): a granted READ/WRITE pair gives
     * the embedded runtime full /storage/emulated/0 access. Owner report
     * 2026-11-02: the runtime could not read /sdcard/Download (workspace
     * imports, APK analysis inputs). The request fires once at startup;
     * denial is logged and non-fatal — the app keeps working with
     * app-private storage only.
     */
    private fun requestStoragePermissionIfNeeded() {
        val missing = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ).filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            return
        }
        requestPermissions(missing.toTypedArray(), REQUEST_STORAGE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_STORAGE) {
            val granted = grantResults.count { it == PackageManager.PERMISSION_GRANTED }
            RuntimeBus.append("storage permissions: $granted/${grantResults.size} granted")
        }
    }

    companion object {
        private const val MODE_NATIVE = "native"
        private const val MODE_WEB_FALLBACK = "webFallback"
        private const val MODE_LOCAL = "local"
        private const val MODE_REMOTE = "remote"
        private const val THEME_SYSTEM = 0
        private const val THEME_LIGHT = 1
        private const val THEME_DARK = 2
        private const val KEY_REMOTE_URL = "remoteUrl"
        private const val KEY_MODE = "mode"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val REQUEST_NOTIFICATIONS = 4001
        private const val REQUEST_STORAGE = 4002
    }
}
