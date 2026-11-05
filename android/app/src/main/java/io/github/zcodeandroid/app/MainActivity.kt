package io.github.zcodeandroid.app

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.zcodeandroid.app.local.LocalServerService
import io.github.zcodeandroid.app.local.Provisioner
import io.github.zcodeandroid.app.local.RuntimeBus
import io.github.zcodeandroid.app.local.RuntimeState
import io.github.zcodeandroid.app.ui.LocalApp
import io.github.zcodeandroid.app.ui.conversation.ConversationScreen
import io.github.zcodeandroid.app.ui.conversation.ConversationViewModel
import io.github.zcodeandroid.app.ui.workspace.WorkspaceScreen
import io.github.zcodeandroid.app.ui.workspace.WorkspaceViewModel
import io.github.zcodeandroid.app.ui.theme.THEME_DARK
import io.github.zcodeandroid.app.ui.theme.THEME_LIGHT
import io.github.zcodeandroid.app.ui.theme.THEME_SYSTEM
import io.github.zcodeandroid.app.ui.theme.ZcodeTheme

/**
 * Entry point — NATIVE-ONLY product surface (owner decision 2026-11-05,
 * ANDROID-UI-ARCHITECTURE-REVISED.md): install -> open -> silent
 * provisioning -> the Compose conversation screen backed by the local
 * ZCode runtime over the WS RPC contract.
 *
 * The WebView is no longer a product surface (§2/§5 Phase 5); the local
 * server remains an internal service. The diagnostics overlay (runtime
 * log, workspace file import, backup/restore, app theme) is reached from
 * the conversation screen's overflow (⋮) menu — see LocalApp.
 *
 * Permissions (§9 just-in-time): no storage permission is requested at
 * startup — runtime provisioning is app-private; workspace import uses
 * the SAF document picker from the diagnostics overlay.
 */
class MainActivity : ComponentActivity() {
    private var showDiagnostics by mutableStateOf(false)
    private var showWorkspace by mutableStateOf(false)
    private var diagnosticsOpenBackup by mutableStateOf(false)
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
        themeMode = prefs.getInt(KEY_THEME_MODE, THEME_SYSTEM)

        if (savedInstanceState != null) {
            showDiagnostics = savedInstanceState.getBoolean(KEY_SHOW_DIAGNOSTICS, false)
            showWorkspace = savedInstanceState.getBoolean(KEY_SHOW_WORKSPACE, false)
            diagnosticsOpenBackup = savedInstanceState.getBoolean(KEY_DIAGNOSTICS_BACKUP, false)
        }

        setContent {
            val runtimeState by RuntimeBus.state.collectAsState()
            // Whether the runtime reached Running at least once in this app
            // session — after a deliberate stop (⋮ menu / notification) the
            // screen offers an honest restart path instead of a stuck
            // "Preparing" placeholder (never auto-restarts: the stop must win).
            var ranOnce by remember { mutableStateOf(false) }
            LaunchedEffect(runtimeState) {
                if (runtimeState is RuntimeState.Running) {
                    ranOnce = true
                }
            }
            // Silent first-run: ONE owner for provisioning + start. Extract,
            // become Ready, hand the runtime to the foreground service — the
            // UI never spawns Node directly and never double-runs extraction.
            LaunchedEffect(Unit) {
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
            ZcodeTheme(
                darkTheme = when (themeMode) {
                    THEME_LIGHT -> false
                    THEME_DARK -> true
                    else -> isSystemInDarkTheme()
                },
            ) {
                when (val running = runtimeState) {
                    is RuntimeState.Running ->
                        if (showDiagnostics) {
                            LocalApp(
                                onBackToWeb = { showDiagnostics = false },
                                openBackupSection = diagnosticsOpenBackup,
                                themeMode = themeMode,
                                onSetThemeMode = { value ->
                                    themeMode = value
                                    prefs.edit().putInt(KEY_THEME_MODE, value).apply()
                                },
                            )
                        } else if (showWorkspace) {
                            val vm: WorkspaceViewModel = viewModel(
                                factory = WorkspaceViewModel.factory(this@MainActivity),
                            )
                            WorkspaceScreen(vm = vm, onBack = { showWorkspace = false })
                        } else {
                            val vm: ConversationViewModel = viewModel(
                                factory = ConversationViewModel.factory(this@MainActivity),
                            )
                            ConversationScreen(
                                vm = vm,
                                onOpenDiagnostics = {
                                    diagnosticsOpenBackup = false
                                    showDiagnostics = true
                                },
                                onOpenWorkspace = { showWorkspace = true },
                                themeMode = themeMode,
                                onSetThemeMode = { value ->
                                    themeMode = value
                                    prefs.edit().putInt(KEY_THEME_MODE, value).apply()
                                },
                            )
                        }
                    is RuntimeState.Failed -> Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            "ZCode could not start: ${running.message}",
                            color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                        )
                    }
                    else -> {
                        if (ranOnce) {
                            Column(
                                modifier = Modifier.fillMaxSize().padding(24.dp),
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Text("ZCode runtime is stopped")
                                Spacer(Modifier.height(12.dp))
                                Button(onClick = { LocalServerService.start(this@MainActivity) }) {
                                    Text("Start ZCode")
                                }
                            }
                        } else {
                            Column(
                                modifier = Modifier.fillMaxSize().padding(24.dp),
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Text("Preparing ZCode…")
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_SHOW_DIAGNOSTICS, showDiagnostics)
        outState.putBoolean(KEY_SHOW_WORKSPACE, showWorkspace)
        outState.putBoolean(KEY_DIAGNOSTICS_BACKUP, diagnosticsOpenBackup)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Do NOT stop the runtime here: the foreground service keeps it alive
        // and the notification offers an explicit Stop action.
        if (isFinishing) {
            RuntimeBus.append("activity destroyed; runtime kept by foreground service")
        }
    }

    companion object {
        private const val KEY_SHOW_DIAGNOSTICS = "showDiagnostics"
        private const val KEY_SHOW_WORKSPACE = "showWorkspace"
        private const val KEY_DIAGNOSTICS_BACKUP = "diagnosticsOpenBackup"
        private const val KEY_THEME_MODE = "theme_mode"
    }
}
