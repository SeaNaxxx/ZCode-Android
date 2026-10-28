package com.example.zcode_mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.zcode_mobile.local.RuntimeBus
import com.example.zcode_mobile.remote.RemoteControlKeepAliveService
import com.example.zcode_mobile.remote.RemoteControlRecentLink
import com.example.zcode_mobile.remote.RemoteControlRecentLinksStore
import com.example.zcode_mobile.remote.RemoteControlUrlPolicy
import com.example.zcode_mobile.ui.LocalApp
import com.example.zcode_mobile.ui.RemoteControlApp
import com.example.zcode_mobile.ui.theme.ZcodemobileTheme

/**
 * Entry point. Two modes:
 *  - local (default): provisions and runs the original ZCode distribution in
 *    web mode on-device and shows the official web UI in a WebView.
 *  - remote: the official remote-control client (unchanged upstream behavior).
 * Deep links (zcode://remote, https://zcode.z.ai/remote) open remote mode.
 */
class MainActivity : ComponentActivity() {
    private var mode by mutableStateOf(MODE_LOCAL)
    private var remoteUrl by mutableStateOf<String?>(null)
    private var recentLinks by mutableStateOf<List<RemoteControlRecentLink>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // failure-3 A/B instrument (emulator evidence 2026-10-28: the WebView's
        // viewport-unit chain collapses to 0 under edge-to-edge — the suspected
        // trigger is the edge-to-edge <-> WebView insets interaction). The
        // marker file DISABLES edge-to-edge for controlled CI experiments;
        // default behavior is unchanged. Diagnostic hook, not a product switch.
        val edgeToEdgeDisabledByMarker = runCatching {
            java.io.File(filesDir, "e2-no-edge-to-edge").isFile
        }.getOrDefault(false)
        if (edgeToEdgeDisabledByMarker) {
            RuntimeBus.append("edge-to-edge: OFF (e2-no-edge-to-edge marker present — A/B experiment config)")
        } else {
            enableEdgeToEdge()
        }
        if (BuildConfig.DEBUG) {
            android.webkit.WebView.setWebContentsDebuggingEnabled(true)
        }
        recentLinks = RemoteControlRecentLinksStore.read(this)

        val restoredRemoteUrl = savedInstanceState?.getString(KEY_REMOTE_URL)
        val launchRemoteUrl = restoredRemoteUrl ?: resolveRemoteUrlFromIntent(intent)
        if (launchRemoteUrl != null) {
            mode = MODE_REMOTE
            openRemoteUrl(launchRemoteUrl)
        }

        setContent {
            ZcodemobileTheme {
                if (mode == MODE_LOCAL) {
                    LocalApp(
                        onOpenRemote = {
                            mode = MODE_REMOTE
                            openRemoteUrl(BuildConfig.REMOTE_CONTROL_DEFAULT_URL)
                        }
                    )
                } else {
                    RemoteControlApp(
                        remoteUrl = remoteUrl,
                        recentLinks = recentLinks,
                        onOpenUrl = ::openRemoteUrl,
                        onCloseRemote = {
                            closeRemote()
                            mode = MODE_LOCAL
                        },
                        onClearRecentLinks = ::clearRecentLinks,
                    )
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
        mode = savedInstanceState.getString(KEY_MODE, MODE_LOCAL)
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

    companion object {
        private const val MODE_LOCAL = "local"
        private const val MODE_REMOTE = "remote"
        private const val KEY_REMOTE_URL = "remoteUrl"
        private const val KEY_MODE = "mode"
        private const val REQUEST_NOTIFICATIONS = 4001
    }
}
