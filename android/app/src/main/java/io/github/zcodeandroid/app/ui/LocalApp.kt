package io.github.zcodeandroid.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.zcodeandroid.app.BuildConfig
import io.github.zcodeandroid.app.backup.ZcodeBackup
import io.github.zcodeandroid.app.local.DiagLog
import io.github.zcodeandroid.app.local.LocalServerProbe
import io.github.zcodeandroid.app.local.LocalServerService
import io.github.zcodeandroid.app.local.RuntimeBus
import io.github.zcodeandroid.app.local.RuntimePaths
import io.github.zcodeandroid.app.local.RuntimeState
import io.github.zcodeandroid.app.local.WebDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** App-theme selector values — mirror MainActivity's persisted preference. */
private const val THEME_SYSTEM = 0
private const val THEME_LIGHT = 1
private const val THEME_DARK = 2

/**
 * Diagnostics overlay (unified product surface, owner decision 2026-11-04).
 *
 * The official ZCode web UI IS the product; this screen is the honest
 * escape hatch reached from the web UI's overflow (⋮) menu: runtime log,
 * workspace file import, backup/restore, app theme, and the diagnostics
 * export. No simulated states, no fake buttons.
 */
@Composable
fun LocalApp(
    onBackToWeb: () -> Unit,
    openBackupSection: Boolean,
    themeMode: Int,
    onSetThemeMode: (Int) -> Unit,
) {
    val context = LocalContext.current
    val state by RuntimeBus.state.collectAsState()
    val logLines by RuntimeBus.log.collectAsState()
    val scope = rememberCoroutineScope()
    var backupNote by remember { mutableStateOf<String?>(null) }
    var backupBusy by remember { mutableStateOf(false) }
    var copiedNote by remember { mutableStateOf<String?>(null) }

    // System back returns to the web UI (the product surface), not out of
    // the app — the runtime keeps running behind the foreground service.
    BackHandler { onBackToWeb() }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val ws = RuntimePaths.workspace(context)
            val names = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    runCatching {
                        val name = queryDisplayName(context, uri) ?: "file-${System.currentTimeMillis()}"
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            File(ws, name).outputStream().use { input.copyTo(it) }
                        }
                        name
                    }.getOrNull()
                }
            }
            copiedNote = if (names.isEmpty()) "copy failed (see log)" else "copied: ${names.joinToString()}"
            RuntimeBus.append("workspace files: $copiedNote")
        }
    }

    val exportBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        backupBusy = true
        scope.launch {
            val result = ZcodeBackup.export(context, uri)
            backupBusy = false
            backupNote = result.fold(
                onSuccess = { "backup exported: $it files (credentials excluded by policy)" },
                onFailure = { "backup export failed: ${it.message}" },
            )
            RuntimeBus.append("backup export: $backupNote")
        }
    }
    val importBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        backupBusy = true
        scope.launch {
            val result = ZcodeBackup.restore(context, uri)
            backupBusy = false
            backupNote = result.fold(
                onSuccess = { "backup restored: $it files (integrity verified)" },
                onFailure = { "backup restore failed: ${it.message}" },
            )
            RuntimeBus.append("backup restore: $backupNote")
        }
    }

    // Evidence probes fire once per transition into Running (device failure 3
    // protocol: local-server / external / persistence evidence are separate
    // classes). Fire-and-forget — they must never block or break the UI.
    LaunchedEffect(state is RuntimeState.Running) {
        if (state is RuntimeState.Running) {
            launch {
                runCatching { LocalServerProbe.runAll(context) }
                    .onFailure { RuntimeBus.append("probe run failed: ${it.message}") }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        Text("ZCode — diagnostics", style = MaterialTheme.typography.headlineSmall)
        Text(
            "The web UI is the product. This overlay holds the runtime log, " +
                "workspace imports, backup/restore, and the app theme.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        TextButton(onClick = onBackToWeb) { Text("← Back to the web UI") }

        when (val s = state) {
            is RuntimeState.Provisioning -> {
                Text("Setting up: ${s.step}", style = MaterialTheme.typography.titleSmall)
                LinearProgressIndicator(
                    progress = { s.percent / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
                Text("${s.percent}%", style = MaterialTheme.typography.labelLarge)
            }
            is RuntimeState.Running -> Text(
                "Runtime: running — ${s.url}",
                style = MaterialTheme.typography.titleSmall
            )
            else -> Text(
                "Runtime: ${s::class.simpleName}",
                style = MaterialTheme.typography.titleSmall
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                Text("Add files to workspace")
            }
        }
        copiedNote?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            "Workspace: ${RuntimePaths.workspace(context).absolutePath} " +
                "(also bound inside the runtime as /workspace)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            border = if (openBackupSection) {
                BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
            } else null,
            colors = CardDefaults.cardColors(),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Backup / restore", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Sessions, memory, and settings in a schema-versioned zip with " +
                        "sha256 manifest. Credential material is excluded by policy. " +
                        "Restore writes over the runtime data directory — stop the " +
                        "runtime first (⋮ menu) for a clean round-trip.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                            exportBackup.launch("zcode-backup-$stamp.zip")
                        },
                        enabled = !backupBusy,
                    ) { Text("Export") }
                    OutlinedButton(
                        onClick = { importBackup.launch(arrayOf("application/zip", "application/octet-stream")) },
                        enabled = !backupBusy,
                    ) { Text("Restore") }
                }
                if (backupBusy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                backupNote?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("App theme", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = themeMode == THEME_SYSTEM,
                    onClick = { onSetThemeMode(THEME_SYSTEM) },
                    label = { Text("System") },
                )
                FilterChip(
                    selected = themeMode == THEME_LIGHT,
                    onClick = { onSetThemeMode(THEME_LIGHT) },
                    label = { Text("Light") },
                )
                FilterChip(
                    selected = themeMode == THEME_DARK,
                    onClick = { onSetThemeMode(THEME_DARK) },
                    label = { Text("Dark") },
                )
            }
        }

        OutlinedButton(
            onClick = {
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "ZCode diagnostics $stamp")
                    putExtra(Intent.EXTRA_TEXT, buildDiagnostics(context))
                }
                runCatching {
                    context.startActivity(Intent.createChooser(send, "Share diagnostics"))
                }.onFailure { RuntimeBus.append("diagnostics share failed: ${it.message}") }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Share diagnostics") }

        Text("Runtime log", style = MaterialTheme.typography.titleSmall)
        LogConsole(logLines)
    }
}

/**
 * Evidence export for the on-device test protocol. Deliberately EXCLUDES
 * provider config / credentials — secrets must never land in diagnostics.
 */
private fun buildDiagnostics(context: android.content.Context): String {
    val sb = StringBuilder()
    val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
    sb.appendLine("ZCode-Android diagnostics — $ts")
    sb.appendLine(
        "device: ${Build.MANUFACTURER} ${Build.MODEL} | Android ${Build.VERSION.RELEASE} " +
            "(SDK ${Build.VERSION.SDK_INT})"
    )
    sb.appendLine("abis: ${Build.SUPPORTED_ABIS.joinToString(",")}")
    sb.appendLine("app: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    sb.appendLine("embedded bionic bundle: ${RuntimePaths.hasBionicAssets(context)}")
    sb.appendLine("runtime provisioned: ${RuntimePaths.provisioned(context)}")
    sb.appendLine("webview provider: ${WebDiagnostics.webViewProviderInfo(context)}")
    RuntimePaths.marker(context).takeIf { it.isFile }?.let {
        sb.appendLine("marker: ${it.readText().take(500)}")
    }
    sb.appendLine("workspace: ${RuntimePaths.workspace(context).absolutePath}")
    val files = RuntimePaths.workspace(context).listFiles()
    sb.appendLine(
        "workspace files: " +
            (files?.joinToString(", ") { "${it.name}(${it.length()}B)" } ?: "<none>")
    )
    sb.appendLine("--- runtime log (last 150 lines) ---")
    RuntimeBus.log.value.takeLast(150).forEach { sb.appendLine(it) }
    sb.appendLine("--- webview / js / probe evidence (last 250 lines) ---")
    DiagLog.snapshot().takeLast(250).forEach { sb.appendLine(it) }
    // Persisted evidence from previous app sessions (the in-memory buffers
    // above die with the process; sticky web-UI failures such as the
    // "Group action failed" toast are often reported only after a restart).
    val persisted = WebDiagnostics.fileSinkTail(80)
    if (persisted.isNotEmpty()) {
        sb.appendLine("--- persisted web log (last ${persisted.size} lines, files/logs/webconsole.log) ---")
        persisted.forEach { sb.appendLine(it) }
    }
    // On-device bitmap captures from the paint-evidence chain (pull via
    // `adb shell run-as io.github.zcodeandroid.app ls files/logs` when needed).
    File(context.filesDir, "logs").listFiles { f -> f.name.startsWith("webview-") }
        ?.sortedByDescending { it.lastModified() }
        ?.take(6)
        ?.forEach { sb.appendLine("bitmap-capture: files/logs/${it.name} (${it.length()}B)") }
    sb.appendLine("--- provider config / credentials are NOT included in this export ---")
    return sb.toString()
}

@Composable
private fun LogConsole(lines: List<String>) {
    if (lines.isEmpty()) {
        Text("— no log yet —", style = MaterialTheme.typography.bodySmall)
        return
    }
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
    ) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(lines) { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

/**
 * Device failure 3 instrumentation (Vivo V2035, 2026-10-28): the server ran
 * and the RPC layer was alive, but the screen stayed black and NOTHING on the
 * UI side was observable. This WebView records the page lifecycle, every
 * JavaScript console message, every resource/HTTP error, renderer death, and
 * a post-load DOM snapshot (element counts + visible text + UA) — without
 * touching any credential storage. All evidence goes to WebDiagnostics.
 *
 * Unified product surface (owner decision 2026-11-04): this full-screen
 * WebView IS the app. The overflow (⋮) menu exposes the only navigation
 * surfaces outside the page itself: the upstream bundle's own phone-first
 * entry (/remote), the desktop entry (/), the diagnostics overlay,
 * backup/restore, and an explicit stop — every one of them loopback-only
 * (loopback-only URL policy is enforced by construction: the menu navigates
 * only to http://127.0.0.1:$SERVER_PORT routes).
 */
@Composable
fun LocalWebViewScreen(url: String, onOpenDiagnostics: (openBackup: Boolean) -> Unit) {
    // Owner directive (2026-10-31): the "ZCode — local" status strip ate a
    // slice of the interface — the official web UI must be FULL-SCREEN.
    // Only the system status bar height is reserved, so the page never draws
    // under the clock/battery.
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        var filePathCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
        var webViewRef by remember { mutableStateOf<WebView?>(null) }
        var overflowOpen by remember { mutableStateOf(false) }
        val chooserLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val cb = filePathCallback
            filePathCallback = null
            if (cb == null) return@rememberLauncherForActivityResult
            val parsed = runCatching {
                android.webkit.WebChromeClient.FileChooserParams.parseResult(
                    result.resultCode, result.data
                )
            }.getOrNull()
            runCatching { cb.onReceiveValue(parsed ?: arrayOf()) }
                .onFailure { WebDiagnostics.log("webview", "chooser callback failed: ${it.message}") }
        }

        /** Loopback-only in-app navigation (menu surface). */
        fun navigateTo(path: String) {
            val wv = webViewRef ?: return
            val target = "http://127.0.0.1:${RuntimePaths.SERVER_PORT}$path"
            WebDiagnostics.log("webview", "overflow navigate -> $target")
            wv.loadUrl(target)
        }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                // Persisted web-evidence sink: survives WebView reloads AND app
                // restarts, unlike DiagLog/RuntimeBus/logcat. Installed before
                // the first log call below so the boot lines land in the file.
                WebDiagnostics.initFileSink(ctx.filesDir)
                // Config-file URL override (same pattern as
                // MainActivity.config-edge-to-edge-off): a `files/config-web-url`
                // file containing a loopback PATH (must start with "/") redirects
                // the WebView to that route — e.g. "/remote", the upstream web
                // bundle's built-in mobile remote-control entry. No UI writes
                // this file; on release installs it is unwritable (non-debuggable),
                // so normal users always get the default URL.
                val effectiveUrl = runCatching {
                    val cfg = java.io.File(ctx.filesDir, "config-web-url")
                    val path = if (cfg.isFile) cfg.readText().trim() else ""
                    if (path.startsWith("/") && !path.startsWith("//")) {
                        val overridden = "http://127.0.0.1:${RuntimePaths.SERVER_PORT}$path"
                        WebDiagnostics.log("webview", "config-web-url override active: $overridden")
                        overridden
                    } else {
                        WebDiagnostics.log(
                            "webview",
                            "config-web-url: not present or not a path (default url) exists=" +
                                cfg.isFile
                        )
                        url
                    }
                }.getOrDefault(url)
                // Mobile-adaptation layer kill-switch (owner-decision family of
                // config hooks): a `files/config-mobile-css-off` marker disables
                // the injected phone CSS — the page then renders exactly as the
                // desktop bundle intends. Diagnostic hook, not a product switch.
                val mobileCssDisabled = runCatching {
                    java.io.File(ctx.filesDir, "config-mobile-css-off").isFile
                }.getOrDefault(false)
                WebDiagnostics.log(
                    "webview",
                    "mobile CSS: " + if (mobileCssDisabled) {
                        "OFF (config-mobile-css-off marker present — experiment config)"
                    } else {
                        "ON (phone-adaptation layer will inject on page finish)"
                    }
                )
                var experimentRan = false
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.javaScriptCanOpenWindowsAutomatically = true
                    // OAuth: the Z AI accounts domain sets cookies during the
                    // login redirects; WebView rejects third-party cookies by
                    // default which can fail the round-trip. Loopback server
                    // pages stay same-site, so this only affects the auth flow.
                    android.webkit.CookieManager.getInstance()
                        .setAcceptThirdPartyCookies(this, true)
                    // NOTE: the earlier LAYER_TYPE_SOFTWARE mitigation is REMOVED:
                    // the A/B (run 40637657592) already showed it does not help,
                    // and the proven root cause (failure-3, 0.3.7-e2 evidence) is
                    // the pre-layout viewport latch, not the raster path. Default
                    // hardware rasterization is restored.
                    WebDiagnostics.log("webview", "raster: default hardware (SOFTWARE mitigation removed: did not address the proven cause)")
                    WebDiagnostics.log("webview", "default UA: ${settings.userAgentString}")
                    WebDiagnostics.log("webview", "provider: ${WebDiagnostics.webViewProviderInfo(ctx)}")
                    webViewClient = object : WebViewClient() {
                        /** Loopback host = the embedded ZCode server itself. */
                        fun isLoopback(u: android.net.Uri?): Boolean {
                            val host = u?.host?.lowercase() ?: return false
                            return host == "127.0.0.1" || host == "localhost" || host == "[::1]"
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: android.webkit.WebResourceRequest?,
                        ): Boolean {
                            val target = request?.url?.toString() ?: return false
                            // Full navigation trace — the OAuth round-trip
                            // (z.ai accounts -> callback) is only diagnosable
                            // when every redirect is on the record.
                            WebDiagnostics.log("webview", "navigate -> $target")
                            val scheme = request?.url?.scheme?.lowercase() ?: "https"
                            if (scheme != "http" && scheme != "https" && scheme != "about" &&
                                scheme != "data" && scheme != "javascript"
                            ) {
                                // app links (market:, intent:, mailto:) leave the WebView
                                WebDiagnostics.log("webview", "external scheme $scheme -> OS")
                                return try {
                                    view?.context?.startActivity(
                                        Intent(Intent.ACTION_VIEW, request?.url)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                    true
                                } catch (t: Throwable) {
                                    WebDiagnostics.log("webview", "external scheme failed: ${t.message}")
                                    true
                                }
                            }
                            // Non-loopback http(s) navigation (z.ai authorize
                            // pages, docs links): hand to the EXTERNAL browser.
                            // The OAuth state machine lives on the local server
                            // (pending authorization + loopback callback), which
                            // every app AND the browser can reach — the login
                            // completes even when the authorize page is rendered
                            // in Chrome. In-WebView, window.open to these hosts
                            // was silently swallowed (no createWindow handler)
                            // — the concrete "Login failed, please try again"
                            // mechanism on the device.
                            if (scheme == "http" || scheme == "https") {
                                if (!isLoopback(request?.url)) {
                                    WebDiagnostics.log("webview", "non-loopback http(s) -> external browser: $target")
                                    return try {
                                        view?.context?.startActivity(
                                            Intent(Intent.ACTION_VIEW, request?.url)
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                        true
                                    } catch (t: Throwable) {
                                        WebDiagnostics.log("webview", "external browser handoff failed: ${t.message}")
                                        // Stay in the WebView rather than dead-ending.
                                        false
                                    }
                                }
                            }
                            return false
                        }

                        override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: android.graphics.Bitmap?) {
                            WebDiagnostics.log("webview", "onPageStarted $pageUrl")
                        }

                        override fun onPageCommitVisible(view: WebView?, pageUrl: String?) {
                            WebDiagnostics.log("webview", "onPageCommitVisible $pageUrl")
                        }

                        override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                            WebDiagnostics.log("webview", "onPageFinished $finishedUrl")
                            if (view == null) return
                            // Viewport px shim (self-gating): on WebViews whose
                            // style engine resolves CSS vh/dvh units and media
                            // queries against a 0-height layout viewport while
                            // JS viewport metrics are sane (the failure-3
                            // engine state), this rewrites the stylesheet to
                            // pixel heights so the UI renders. On healthy
                            // engines (newer WebView providers, real devices)
                            // the shim detects health and stays idle.
                            if (finishedUrl != null && !finishedUrl.startsWith("data:")) {
                                view.evaluateJavascript(VIEWPORT_SHIM_JS, null)
                                // Phone-adaptation CSS layer (self-gating by
                                // media query + idempotent per document): only
                                // narrows the desktop layout to phone widths —
                                // compact composer, safe-area padding, 16px
                                // inputs, no horizontal overflow. Kill-switch:
                                // config-mobile-css-off marker.
                                if (!mobileCssDisabled) {
                                    view.evaluateJavascript(MOBILE_ADAPT_JS, null)
                                }
                            }
                            view.postDelayed({ dumpDomState(view) }, 4_000)
                            // Invisible render probe (owner directive 2026-10-31:
                            // the visible diagnostic sequence — yellow force-vis
                            // flash, red minimal-html page swap with the
                            // VH=…/INNER=…/PCT=…/BUTTON text, double reload — was
                            // removed now that the layout issue is fixed and
                            // shipped). This keeps the CI-verdict evidence chain
                            // (visual-state JSON + bitmap[before] pixel stats)
                            // WITHOUT any visible hijack of the UI: the bitmap is
                            // captured via view.draw() into memory, never shown.
                            if (!experimentRan && finishedUrl != null && !finishedUrl.startsWith("data:")) {
                                experimentRan = true
                                view.postDelayed({ runRenderProbe(view) }, 5_200)
                            }
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: android.webkit.WebResourceRequest?,
                            error: android.webkit.WebResourceError?,
                        ) {
                            val target = request?.url?.toString() ?: "?"
                            if (request?.isForMainFrame == true || target.startsWith("http")) {
                                WebDiagnostics.log(
                                    "webview",
                                    "onReceivedError code=${error?.errorCode} desc=${error?.description} url=$target"
                                )
                            }
                        }

                        override fun onReceivedHttpError(
                            view: WebView?,
                            request: android.webkit.WebResourceRequest?,
                            response: android.webkit.WebResourceResponse?,
                        ) {
                            WebDiagnostics.log(
                                "webview",
                                "onReceivedHttpError status=${response?.statusCode} " +
                                    "url=${request?.url ?: "?"}"
                            )
                        }

                        override fun onRenderProcessGone(
                            view: WebView?,
                            detail: android.webkit.RenderProcessGoneDetail?,
                        ): Boolean {
                            WebDiagnostics.log(
                                "webview",
                                "RENDERER GONE (didCrash=${detail?.didCrash()}) — UI cannot recover " +
                                    "this WebView; evidence recorded"
                            )
                            // Returning true: the WebView is gone either way; keep the
                            // app process alive so diagnostics can still be shared.
                            (view?.parent as? android.view.ViewGroup)?.removeAllViews()
                            return true
                        }
                    }
                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                            WebDiagnostics.log(
                                "js",
                                "${consoleMessage?.messageLevel()}: ${consoleMessage?.message()} " +
                                    "@ ${consoleMessage?.sourceId()}:${consoleMessage?.lineNumber()}"
                            )
                            return true
                        }

                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            if (newProgress >= 100) {
                                WebDiagnostics.log("webview", "onProgressChanged 100")
                            }
                        }

                        // File upload from the phone (owner report 2026-10-31:
                        // the web UI's attach button did nothing). Without this
                        // override the WebView silently swallows every
                        // <input type=file> activation — the ZCode composer's
                        // attach button could never open a picker. SAF handoff:
                        // the system picker returns content:// URIs with read
                        // grants; WebView streams them to the local server itself.
                        override fun onShowFileChooser(
                            webView: WebView?,
                            callback: ValueCallback<Array<Uri>>,
                            params: android.webkit.WebChromeClient.FileChooserParams,
                        ): Boolean {
                            WebDiagnostics.log(
                                "webview",
                                "file chooser requested (mode=${params.mode}, accept=${params.acceptTypes.joinToString(",")})"
                            )
                            // A previous chooser still pending: cancel it first
                            // (the framework allows exactly one live callback).
                            filePathCallback?.onReceiveValue(null)
                            filePathCallback = callback
                            return try {
                                val intent = params.createIntent().apply {
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    // FileChooserParams' mode constants are API-21-stable
                                    // (0 = open single, 1 = open multiple) but the K2
                                    // toolchain against the compileSdk 36.1 stubs fails
                                    // to resolve them (CI runs 41145908837/41146236029:
                                    // Unresolved reference 'FILE_CHOOSER_MODE_MULTIPLE')
                                    // — compare the documented value directly.
                                    if (params.mode == 1) {
                                        putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true)
                                    }
                                }
                                chooserLauncher.launch(intent)
                                true
                            } catch (e: ActivityNotFoundException) {
                                WebDiagnostics.log("webview", "file chooser: no activity handles it: ${e.message}")
                                filePathCallback = null
                                false
                            } catch (t: Throwable) {
                                WebDiagnostics.log("webview", "file chooser launch failed: ${t.message}")
                                filePathCallback = null
                                false
                            }
                        }

                        // window.open() handoff: the ZCode login flow opens the
                        // z.ai authorize page via window.open. With no
                        // createWindow handler WebView swallowed it silently —
                        // the second half of the "Login failed, please try
                        // again" mechanism (non-loopback navigations are
                        // already handed to the external browser in
                        // shouldOverrideUrlLoading; this covers the popup path).
                        override fun onCreateWindow(
                            view: WebView?,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: android.os.Message?,
                        ): Boolean {
                            val ctx = view?.context ?: return false
                            fun openExternally(target: android.net.Uri): Boolean = try {
                                ctx.startActivity(
                                    Intent(Intent.ACTION_VIEW, target)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                                WebDiagnostics.log("webview", "window.open -> external browser: $target")
                                true
                            } catch (t: Throwable) {
                                WebDiagnostics.log("webview", "window.open external failed: ${t.message}")
                                false
                            }
                            if (!isUserGesture) {
                                WebDiagnostics.log("webview", "window.open blocked (no user gesture, dialog=$isDialog)")
                                return false
                            }
                            // Extract the target URL via the classic
                            // WebView transport mechanism.
                            val transport = resultMsg?.obj as? android.webkit.WebView.WebViewTransport
                            if (transport != null) {
                                val temp = WebView(ctx)
                                temp.webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        v: WebView?,
                                        request: android.webkit.WebResourceRequest?,
                                    ): Boolean {
                                        val target = request?.url ?: return false
                                        v?.destroy()
                                        return openExternally(target)
                                    }
                                }
                                transport.webView = temp
                                resultMsg.sendToTarget()
                                return true
                            }
                            return false
                        }
                    }
                    var started = false
                    // DEVICE FAILURE 3 ROOT CAUSE + FIX (proven by the emulator
                    // evidence chain, runs 40660693522 / 40665560040, 2026-10-28):
                    // loadUrl() called in the FACTORY runs BEFORE the WebView is
                    // attached and laid out — the document was created while the
                    // view had NO size, and this WebView state latches the CSS
                    // viewport-height resolution to 0 for the document's lifetime:
                    // measured on the AVD — window.innerHeight 791, clientHeight
                    // 791, visualViewport 791@1.0 — while 100vh/100dvh/100% ALL
                    // resolve to 0 (probe elements), html/body/#root compute
                    // height 0, the RootShell (upstream h-dvh) collapses, the
                    // welcome card centers into a 48px strip at y=-168 and is
                    // clipped; only the dark background paints = BLACK SCREEN.
                    // Forcing 100vh inline did NOT repair it (bitmap[repaired]
                    // unchanged) — the latch is document-level, so the fix is to
                    // create the document AFTER the view has a real size.
                    // FIX: load on FIRST real layout (with a 2s fallback), never
                    // pre-layout. This is correct Android WebView practice in
                    // general, not a diagnostic-only hack.
                    addOnLayoutChangeListener(object : android.view.View.OnLayoutChangeListener {
                        override fun onLayoutChange(
                            v: android.view.View?, l: Int, t: Int, r: Int, b: Int,
                            ol: Int, ot: Int, or2: Int, ob: Int,
                        ) {
                            val vw = v?.width ?: 0
                            val vh = v?.height ?: 0
                            if (vw > 0 && vh > 0 && !started) {
                                started = true
                                removeOnLayoutChangeListener(this)
                                WebDiagnostics.log(
                                    "webview",
                                    "first layout ${vw}x$vh -> loadUrl now (pre-layout viewport latch avoided: failure-3 fix)"
                                )
                                loadUrl(effectiveUrl)
                            }
                        }
                    })
                    postDelayed({
                        if (!started) {
                            started = true
                            WebDiagnostics.log("webview", "fallback load (no layout callback fired within 2s)")
                            loadUrl(effectiveUrl)
                        }
                    }, 2_000)
                    WebDiagnostics.log("webview", "created; will load $effectiveUrl on first layout (failure-3 fix)")
                }.also { created ->
                    // Keep a reference for the overflow menu's loopback-only
                    // navigation (mobile /remote route, desktop /).
                    webViewRef = created
                }
            }
        )

        // Overflow (⋮) menu — the app-side navigation surface over the unified
        // web UI. Placed under the status bar at the top-end; small footprint
        // over the page's own top bar, with a full-size touch target.
        Box(
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            IconButton(
                onClick = { overflowOpen = true },
                modifier = Modifier.padding(end = 4.dp),
            ) {
                Text(
                    "⋮",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            DropdownMenu(
                expanded = overflowOpen,
                onDismissRequest = { overflowOpen = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Mobile UI (/remote)") },
                    onClick = {
                        overflowOpen = false
                        navigateTo("/remote")
                    },
                )
                DropdownMenuItem(
                    text = { Text("Desktop UI (/)") },
                    onClick = {
                        overflowOpen = false
                        navigateTo("/")
                    },
                )
                DropdownMenuItem(
                    text = { Text("Diagnostics") },
                    onClick = {
                        overflowOpen = false
                        onOpenDiagnostics(false)
                    },
                )
                DropdownMenuItem(
                    text = { Text("Backup / restore") },
                    onClick = {
                        overflowOpen = false
                        onOpenDiagnostics(true)
                    },
                )
                DropdownMenuItem(
                    text = { Text("Stop runtime") },
                    onClick = {
                        overflowOpen = false
                        WebDiagnostics.log("webview", "overflow: stop runtime requested")
                        RuntimeBus.append("overflow menu: stopping runtime")
                        LocalServerService.stop(context)
                    },
                )
            }
        }
    }
}

/**
 * Read-only snapshot of what the page actually rendered. Distinguishes the
 * black-screen hypotheses: boot shell with an EMPTY #root (React never
 * mounted / crashed at import), a mounted tree with no visible text, or a
 * fully rendered UI. No storage, no cookies, no tokens — DOM shape + short
 * visible text only.
 */
private fun dumpDomState(view: WebView?) {
    view?.evaluateJavascript(
        """(function(){
        var root = document.getElementById('root');
        return JSON.stringify({
          readyState: document.readyState,
          href: location.href,
          rootChildren: root ? root.children.length : -1,
          rootHtmlLen: root ? root.innerHTML.length : -1,
          bodyText: ((document.body && document.body.innerText) || '').slice(0, 200),
          viewport: window.innerWidth + 'x' + window.innerHeight,
          dpr: window.devicePixelRatio,
          ua: navigator.userAgent
        });
      })()"""
    ) { value ->
        WebDiagnostics.log("webview", "dom-state: ${value.take(700)}")
    }
}

/*
 * ============================================================================
 * render evidence (failure-3 history, now invisible by design)
 * ============================================================================
 * The debug suite that isolated the black-screen root cause ran VISIBLE
 * experiments in the live WebView: a yellow force-visibility flash, a 100vh
 * layout-repair flash, and the isolated minimal-html paint page (red
 * background, on-screen "VH=… INNER=… PCT=…" report + BUTTON) swapped in
 * over the real UI, followed by two reloads. That instrumentation served
 * its purpose (root cause proven, px shim shipped, UI renders) and on a
 * real phone showed up as the startup red flicker the owner reported
 * (2026-10-31). Per the owner directive it is REMOVED.
 *
 * What stays — strictly invisible, and exactly what the CI render verdict
 * asserts on:
 *  1. visual-state — the read-only DOM/style/viewport-unit probe (JSON in
 *     the log; it also records the probe values the flicker used to print,
 *     so diagnostics lose nothing).
 *  2. bitmap[before] — view.draw() into an in-memory bitmap + quantized
 *     pixel statistics + PNG under files/logs/. The view is never replaced
 *     or reloaded; nothing on screen changes.
 */
private const val JS_VISUAL_STATE = """(function(){
  function dsc(el){ if(!el) return 'null'; var s = el.id ? '#'+el.id : el.tagName.toLowerCase(); var c = (el.getAttribute && el.getAttribute('class')) || ''; if (c) { var p = c.trim().split(/\s+/).slice(0,3).join('.'); if (p) s += '.' + p; } return s; }
  function stl(el){ var cs = getComputedStyle(el); return 'd=' + cs.display + ' v=' + cs.visibility + ' o=' + cs.opacity + ' pos=' + cs.position + ' z=' + cs.zIndex + ' color=' + cs.color + ' bg=' + cs.backgroundColor; }
  function rc(el){ var r = el.getBoundingClientRect(); return '[' + Math.round(r.x) + ',' + Math.round(r.y) + ' ' + Math.round(r.width) + 'x' + Math.round(r.height) + ']'; }
  var nodes = [];
  nodes.push(dsc(document.documentElement) + ' ' + rc(document.documentElement) + ' ' + stl(document.documentElement));
  if (document.body) nodes.push(dsc(document.body) + ' ' + rc(document.body) + ' ' + stl(document.body));
  var root = document.getElementById('root');
  (function walk(el, d){ if (!el || d > 3 || nodes.length > 24) return; if (el.shadowRoot) nodes.push('SHADOW-ROOT-AT:' + dsc(el)); nodes.push(dsc(el) + ' ' + rc(el) + ' ' + stl(el)); var k = el.children || []; for (var i = 0; i < k.length && i < 6; i++) walk(k[i], d + 1); })(root, 0);
  var hits = [[0.5,0.35],[0.5,0.5],[0.5,0.65],[0.25,0.5],[0.75,0.5]].map(function(p){ var el = document.elementFromPoint(window.innerWidth * p[0], window.innerHeight * p[1]); return dsc(el); });
  var anims = [];
  try { if (document.getAnimations) document.getAnimations().slice(0,5).forEach(function(a){ anims.push((a.animationName || a.constructor.name) + ':' + a.playState); }); } catch(e) { anims.push('err:' + e.message); }
  // Viewport-unit resolution probe: measure what 100vh / 100dvh / 100% ACTUALLY
  // resolve to in this engine state (failure-3 emulator evidence 2026-10-28:
  // html/body/#root collapse to 0 while innerHeight is 912 — this tells WHICH
  // unit breaks: dvh vs the percentage chain vs the ICB itself).
  function unitProbe(cssText){ var p = document.createElement('div'); p.style.cssText = 'position:absolute;visibility:hidden;pointer-events:none;' + cssText; document.body.appendChild(p); var h = p.getBoundingClientRect().height; p.remove(); return Math.round(h); }
  var vp = { inner: window.innerWidth + 'x' + window.innerHeight, client: document.documentElement.clientWidth + 'x' + document.documentElement.clientHeight };
  try { if (window.visualViewport) vp.vv = Math.round(window.visualViewport.height) + '@' + Math.round(window.visualViewport.offsetTop || 0) + ' s=' + Number(window.visualViewport.scale).toFixed(3); } catch(e) {}
  try { vp.vh100 = unitProbe('height:100vh'); } catch(e) { vp.vh100 = 'err'; }
  try { vp.dvh100 = unitProbe('height:100dvh'); } catch(e) { vp.dvh100 = 'err'; }
  try { vp.pct100 = unitProbe('height:100%'); } catch(e) { vp.pct100 = 'err'; }
  // Media queries share the viewport source with the units: if they agree with
  // clientHeight (791) while vh100=0, the split is inside the style system;
  // if they ALSO report 0, the whole layout viewport is 0 and JS metrics lie.
  var ih = Math.round(window.innerHeight);
  var mq = { compat: document.compatMode,
    mqInner: window.matchMedia('(height: ' + ih + 'px)').matches,
    mqZero: window.matchMedia('(height: 0px)').matches,
    mqMin700: window.matchMedia('(min-height: 700px)').matches };
  // What the WebView REPORTS for prefers-color-scheme — the direct measurement
  // of the app-side night-mode wiring (MainActivity follows the system dark
  // setting; targetSdk 28 does not receive it on its own). The frontend's own
  // 'dark' class is NOT a reliable indicator (the login UI is dark-styled by
  // default even on a light system), so the dark-mode CI stage asserts THIS.
  var pcs = { dark: window.matchMedia('(prefers-color-scheme: dark)').matches,
    light: window.matchMedia('(prefers-color-scheme: light)').matches };
  var heights = [];
  [document.documentElement, document.body, root, root.firstElementChild].forEach(function(el){ if (!el) return; var cs = getComputedStyle(el); heights.push(dsc(el) + ' cssH=' + cs.height + ' offH=' + el.offsetHeight + ' scrollH=' + el.scrollHeight); });
  return JSON.stringify({ vis: document.visibilityState, hidden: document.hidden, focus: document.hasFocus(), docHiddenAttr: document.documentElement.hasAttribute('data-document-hidden'), bootTheme: document.documentElement.getAttribute('data-zcode-bootstrap-theme'), dark: document.documentElement.classList.contains('dark'), pcs: pcs, iframes: document.querySelectorAll('iframe').length, anims: anims, hits: hits, vp: vp, mq: mq, heights: heights, nodes: nodes });
})()"""

private fun evaluateJs(view: WebView, script: String, onResult: (String) -> Unit) {
    runCatching { view.evaluateJavascript(script) { value -> onResult(value) } }
        .onFailure { WebDiagnostics.log("probe", "evaluateJavascript failed: ${it.message}") }
}

/**
 * One-shot INVISIBLE evidence probe (UI thread via evaluateJavascript
 * callbacks): visual-state -> bitmap[before]. Replaces the former visible
 * evidence sequence (force-vis flash / layout-repair flash / minimal-html
 * red page swap / double reload) which the owner reported as the startup
 * red flicker; the underlying layout issue is fixed and shipped, so the
 * visible experiments are gone while the CI-verdict evidence lines stay.
 */
private fun runRenderProbe(view: WebView) {
    WebDiagnostics.log("probe", "render probe start (one-shot per WebView, invisible)")
    evaluateJs(view, JS_VISUAL_STATE) { value ->
        WebDiagnostics.log("probe", "visual-state: ${value.take(2400)}")
        captureAndAnalyze(view, "before")
        WebDiagnostics.log("probe", "render probe complete")
    }
}

/**
 * Capture the WebView's own painted output (works regardless of what covers
 * it on screen) and log quantized pixel statistics — the decisive
 * "renderer produced pixels / produced nothing" discriminator — plus a PNG
 * under files/logs/ for offline inspection.
 */
private fun captureAndAnalyze(view: WebView, label: String) {
    runCatching {
        val w = view.width
        val h = view.height
        if (w == 0 || h == 0) {
            WebDiagnostics.log("probe", "bitmap[$label]: view not laid out (${w}x$h)")
            return
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        val xs = 40
        val ys = 80
        val pixels = IntArray(xs * ys)
        var idx = 0
        for (yy in 0 until ys) {
            val py = if (h == 1) 0 else (yy * (h - 1)) / (ys - 1)
            for (xx in 0 until xs) {
                val px = if (w == 1) 0 else (xx * (w - 1)) / (xs - 1)
                pixels[idx++] = bmp.getPixel(px, py)
            }
        }
        WebDiagnostics.log("probe", "bitmap[$label]: ${w}x${h} -> ${WebDiagnostics.analyzePixels(pixels)}")
        saveShotPng(view, bmp, label)
        bmp.recycle()
    }.onFailure { WebDiagnostics.log("probe", "bitmap[$label] failed: ${it.message}") }
}

private fun saveShotPng(view: WebView, bmp: Bitmap, label: String) {
    runCatching {
        val dir = File(view.context.filesDir, "logs").apply { mkdirs() }
        val f = File(dir, "webview-$label-${System.currentTimeMillis()}.png")
        java.io.FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        WebDiagnostics.log("probe", "shot saved: ${f.name} (${f.length()}B)")
    }.onFailure { WebDiagnostics.log("probe", "shot save failed: ${it.message}") }
}


private fun queryDisplayName(context: android.content.Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.query(
            uri, null, null, null, null
        )?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull()

/**
 * Phone-adaptation CSS layer (owner decision 2026-11-04: the desktop web UI
 * runs full-screen on a phone; this injection adapts it conservatively).
 *
 * Design constraints, deliberately minimal and class-name-agnostic (no
 * upstream bundle internals are pinned):
 *  1. SELF-GATING by media query: every rule sits inside
 *     `@media (max-width: 820px)` — desktop/tablet layouts and the upstream
 *     mobile route's own styling are untouched when the viewport is wider.
 *  2. IDEMPOTENT per document: a fixed-id <style> (#zcode-mobile-adapt) plus
 *     a window flag; re-runs of this script (SPA soft reloads, repeat
 *     onPageFinished) append nothing.
 *  3. SAFE by scope: only four conservative behaviors —
 *     (a) no horizontal overflow on the root scrollers;
 *     (b) 16px font-size on form controls (prevents the WebView focus zoom
 *         that shifts the composer off-screen on phones);
 *     (c) safe-area bottom padding for composer-classed containers
 *         (viewport-fit=cover is added to the existing viewport meta so
 *         env(safe-area-inset-*) can resolve on notched devices);
 *     (d) hero-classed blocks constrained to full width with word wrap.
 *  4. KILL-SWITCH: the `config-mobile-css-off` marker file disables the
 *     whole layer (checked app-side before evaluation).
 *
 * Evidence: the injection result is logged to the console sink so the
 * persisted web log records whether the layer applied (probe-able in CI).
 */
private const val MOBILE_ADAPT_JS = """
(function(){
  if (window.__zcodeMobileAdapt) { return; }
  window.__zcodeMobileAdapt = true;
  try {
    var m = document.querySelector('meta[name="viewport"]');
    if (m) {
      var c = m.getAttribute('content') || '';
      if (c.indexOf('viewport-fit') < 0) {
        m.setAttribute('content', c + (c ? ', ' : '') + 'viewport-fit=cover');
      }
    }
  } catch (e) {}
  if (document.getElementById('zcode-mobile-adapt')) { return; }
  var css = [
    '@media (max-width: 820px) {',
    '  html, body { overflow-x: hidden !important; }',
    '  textarea, input, select { font-size: 16px !important; }',
    '  [class*="composer"], [class*="inputbar"], [class*="input-bar"], [class*="composer-bar"] {',
    '    padding-bottom: calc(env(safe-area-inset-bottom, 0px) + 8px) !important;',
    '  }',
    '  [class*="hero"] { max-width: 100% !important; overflow-wrap: break-word; }',
    '}'
  ].join('\n');
  var st = document.createElement('style');
  st.id = 'zcode-mobile-adapt';
  st.textContent = css;
  (document.head || document.documentElement).appendChild(st);
  console.log('[zcode-mobile-css] injected: media(max-width:820px) layer, viewport-fit=cover, idempotent');
})();
"""

/**
 * Viewport pixel shim (failure-3 engine state).
 *
 * Evidence (emulator runs, x86_64-verify-evidence): window.innerHeight /
 * clientHeight / visualViewport are SANE while EVERY CSS viewport-height unit
 * (vh, dvh and %-chains from html) resolves to 0 and media queries agree with
 * the zero height (mqZero=true). The defect is engine-state, not page CSS:
 * reloads, wide-viewport mode, forced visibility and style repair never
 * recover it, so the upstream UI (h-dvh shell, centered cards) collapses to a
 * 0-height strip = black screen.
 *
 * This shim is SELF-GATING: it first probes whether vh/dvh resolve; on a
 * healthy engine it stays idle (real devices with current WebView providers).
 * On the broken engine it (1) pins html/body to the real pixel height so the
 * %-chain resolves, and (2) rewrites stylesheet declarations that use vh/dvh
 * to pixel values. Media-query GATES themselves cannot be repaired (only
 * their inner declarations); Tailwind base styles carry the layout at phone
 * widths, so the UI renders fully. Idempotent per document; re-applies on
 * resize and on newly added <style>/<link> nodes.
 */
private const val VIEWPORT_SHIM_JS = """
(function(){
  if (window.__zcodeViewportShim) { return; }
  window.__zcodeViewportShim = true;
  function innerH(){
    var vv = window.visualViewport;
    var h = (vv && vv.height) || window.innerHeight ||
            document.documentElement.clientHeight || 0;
    return Math.max(1, Math.round(h));
  }
  function engineBroken(){
    try {
      var host = document.body || document.documentElement;
      if (!host) return false;
      var el = document.createElement('div');
      el.style.cssText = 'position:absolute;visibility:hidden;height:100vh;width:1px;top:-9999px;';
      host.appendChild(el);
      var hv = el.getBoundingClientRect().height;
      el.style.height = '100dvh';
      var hd = el.getBoundingClientRect().height;
      el.parentNode.removeChild(el);
      return !(hv > 100) && !(hd > 100);
    } catch (e) { return false; }
  }
  function fixRule(rule){
    var decls = rule.style ? rule.style.cssText : null;
    if (!decls || decls.indexOf('vh') < 0) return 0;
    var fixed = decls.replace(/(-?[0-9.]+)(dvh|vh)(?![a-zA-Z0-9_-])/g, function(m, num, unit){
      var v = parseFloat(num);
      if (!isFinite(v)) return m;
      return Math.round(v * innerH() / 100) + 'px';
    });
    if (fixed === decls) return 0;
    try { rule.style.cssText = fixed; return 1; } catch (e) { return 0; }
  }
  function walk(rules){
    var n = 0;
    for (var i = 0; i < rules.length; i++){
      var r = rules[i];
      try {
        // Type-agnostic: Chromium reports CSSLayerBlockRule.type as 0 (not 6),
        // and Tailwind v4 wraps ALL utilities in @layer — type-number matching
        // silently skips the whole layer (proven on the real UI: rulesFixed
        // stuck at 1 with type matching, 63 with cssRules traversal).
        if (r.style) { n += fixRule(r); }
        if (r.cssRules) { n += walk(r.cssRules); }
      } catch (e) {}
    }
    return n;
  }
  function applyAll(){
    var de = document.documentElement;
    if (!de) return 0;
    var h = innerH();
    var w = Math.max(1, window.innerWidth || de.clientWidth || 0);
    de.style.height = h + 'px';
    de.style.width = w + 'px';
    var b = document.body;
    if (b) { b.style.height = h + 'px'; b.style.width = w + 'px'; }
    var n = 0;
    for (var i = 0; i < document.styleSheets.length; i++){
      try { n += walk(document.styleSheets[i].cssRules); } catch (e) {}
    }
    console.log('[zcode-shim] applied: rulesFixed=' + n + ' htmlPx=' + h + 'x' + w);
    return n;
  }
  var lastApply = 0;
  function maybeApply(){
    // Evidence integrity: engineBroken() returns false while document.body is
    // still absent (nothing to measure) — logging "engine healthy" in that
    // window would poison the on-device verdict (its total absence across the
    // persisted log is the proof that the engine never resolves vh/dvh).
    if (!(document.body || document.documentElement)) return;
    if (!engineBroken()) {
      if (!window.__zcodeShimHealthyLogged) {
        window.__zcodeShimHealthyLogged = true;
        console.log('[zcode-shim] engine healthy (vh/dvh resolve) - shim idle');
      }
      return;
    }
    var now = Date.now();
    if (now - lastApply < 250) return;
    lastApply = now;
    applyAll();
  }
  function boot(){
    maybeApply();
    window.addEventListener('resize', maybeApply, true);
    try {
      var mo = new MutationObserver(function(muts){
        for (var i = 0; i < muts.length; i++){
          var added = muts[i].addedNodes;
          for (var j = 0; j < added.length; j++){
            var n = added[j];
            if (n && (n.tagName === 'STYLE' || n.tagName === 'LINK')) { maybeApply(); return; }
          }
        }
      });
      var root = document.documentElement || document;
      mo.observe(root, { childList: true, subtree: true });
    } catch (e) {}
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
    window.addEventListener('load', boot);
  } else {
    boot();
  }
})();
"""
