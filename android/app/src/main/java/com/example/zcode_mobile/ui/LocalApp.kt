package com.example.zcode_mobile.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import com.example.zcode_mobile.BuildConfig
import com.example.zcode_mobile.R
import com.example.zcode_mobile.local.LocalServerProbe
import com.example.zcode_mobile.local.DiagLog
import com.example.zcode_mobile.local.LocalServerService
import com.example.zcode_mobile.local.Provisioner
import com.example.zcode_mobile.local.RuntimeBus
import com.example.zcode_mobile.local.RuntimePaths
import com.example.zcode_mobile.local.RuntimeState
import com.example.zcode_mobile.local.WebDiagnostics
import com.example.zcode_mobile.local.ZcodeServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local-mode UI: honest runtime status, setup, start/stop, the OFFICIAL ZCode
 * web interface in a WebView, and a way to put real files into the workspace.
 * No simulated states, no fake buttons.
 */
@Composable
fun LocalApp(
    onOpenRemote: () -> Unit,
) {
    val context = LocalContext.current
    val state by RuntimeBus.state.collectAsState()
    val logLines by RuntimeBus.log.collectAsState()
    val scope = rememberCoroutineScope()
    var inWebView by remember { mutableStateOf(false) }
    var copiedNote by remember { mutableStateOf<String?>(null) }

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

    LaunchedEffect(Unit) {
        if (Provisioner.isProvisioned(context) && state is RuntimeState.NotProvisioned) {
            RuntimeBus.setState(RuntimeState.Ready)
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

    if (inWebView && state is RuntimeState.Running) {
        val url = (state as RuntimeState.Running).url
        BackHandler { inWebView = false }
        LocalWebViewScreen(url = url, onExit = { inWebView = false })
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        Text("Z AI ZCode", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Local runtime — official ZCode web interface served on-device",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        when (val s = state) {
            is RuntimeState.NotProvisioned -> {
                StatusCard("Setup required")
                if (RuntimePaths.hasBionicAssets(context)) {
                    Text(
                        "First run extracts the fully embedded Bionic runtime from this APK: " +
                            "Node.js 24.22.1 + git + bash (Termux Bionic build, checksum-verified) and " +
                            "the official ZCode distribution. No downloads — works in airplane mode.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    Text(
                        "First run downloads and verifies: Ubuntu base (arm64) + Node.js " +
                            "${RuntimePaths.NODE_VERSION} (checksums verified), then stages the official " +
                            "ZCode distribution embedded in this APK. Needs a few hundred MB of storage.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Button(
                    onClick = {
                        scope.launch { Provisioner.provision(context) }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Set up local runtime") }
            }

            is RuntimeState.Provisioning -> {
                StatusCard("Setting up: ${s.step}")
                LinearProgressIndicator(
                    progress = { s.percent / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
                Text("${s.percent}%", style = MaterialTheme.typography.labelLarge)
            }

            is RuntimeState.Ready, is RuntimeState.Stopped -> {
                StatusCard(if (s is RuntimeState.Ready) "Ready" else "Stopped")
                Button(
                    onClick = {
                        scope.launch {
                            ZcodeServer.start(context)
                            LocalServerService.start(context)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Start ZCode") }
            }

            is RuntimeState.Starting -> {
                StatusCard("Starting ZCode runtime…")
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = {
                    ZcodeServer.stop()
                    LocalServerService.stop(context)
                }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            }

            is RuntimeState.Running -> {
                StatusCard("Running — ${s.url}")
                Button(
                    onClick = { inWebView = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Open ZCode") }
                // Standalone-server contract (upstream project-storage CONTRACT.md):
                // the --web entry runs with a remote ownerHostId, so task creation
                // REQUIRES a registered project (project.remote-project-required
                // otherwise — Vivo log 2026-10-28 11:38). The supported path is the
                // official Projects flow; no contract bypass is implemented.
                Text(
                    "Before the first task: in the web UI open Projects and add this " +
                        "workspace as a project, then start chats from that project. " +
                        "A bare composer session fails with 草稿会话创建失败 on a server.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(onClick = {
                    ZcodeServer.stop()
                    LocalServerService.stop(context)
                }, modifier = Modifier.fillMaxWidth()) { Text("Stop runtime") }
            }

            is RuntimeState.Failed -> {
                StatusCard("Failed — ${s.stage}")
                Text(
                    s.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(onClick = {
                    scope.launch { Provisioner.provision(context) }
                }, modifier = Modifier.fillMaxWidth()) { Text("Retry setup") }
                TextButton(onClick = {
                    RuntimeBus.setState(RuntimeState.Ready)
                }) { Text("Skip to start (if files exist)") }
            }
        }

        if (state is RuntimeState.Running || state is RuntimeState.Ready) {
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
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onOpenRemote) { Text("Remote control (official)") }
        }

        OutlinedButton(
            onClick = {
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "ZCode E2 diagnostics $stamp")
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

@Composable
private fun StatusCard(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

/**
 * Evidence export for the on-device test protocol. Deliberately EXCLUDES
 * provider config / credentials — secrets must never land in diagnostics.
 */
private fun buildDiagnostics(context: android.content.Context): String {
    val sb = StringBuilder()
    val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
    sb.appendLine("ZCode-Android E2 diagnostics — $ts")
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
    // On-device bitmap captures from the paint-evidence chain (pull via
    // `adb shell run-as com.example.zcode_mobile ls files/logs` when needed).
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
 */
@Composable
private fun LocalWebViewScreen(url: String, onExit: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onExit) { Text("‹ Status") }
            Spacer(Modifier.width(8.dp))
            Text("ZCode — local", style = MaterialTheme.typography.titleSmall)
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                var experimentRan = false
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // Device failure 3 root-cause class (evidence, not guess):
                    // run 40627330416 shows the DOM fully populated (dom-state
                    // rootChildren=1, rootHtmlLen=15007, bodyText="Welcome to
                    // ZCode...") while the screen stays black on BOTH the API 33
                    // AVD and the Vivo — page background paints, content layers
                    // do not. That is the hardware compositing/raster path.
                    // Mitigation under test: force CPU rasterization for this
                    // WebView. Revisit if upstream CSS compositing is fixed.
                    setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
                    WebDiagnostics.log("webview", "raster: SOFTWARE (content-not-painted mitigation, evidence-backed)")
                    WebDiagnostics.log("webview", "created; will load $url")
                    WebDiagnostics.log("webview", "default UA: ${settings.userAgentString}")
                    WebDiagnostics.log("webview", "provider: ${WebDiagnostics.webViewProviderInfo(ctx)}")
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: android.graphics.Bitmap?) {
                            WebDiagnostics.log("webview", "onPageStarted $pageUrl")
                        }

                        override fun onPageCommitVisible(view: WebView?, pageUrl: String?) {
                            WebDiagnostics.log("webview", "onPageCommitVisible $pageUrl")
                        }

                        override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                            WebDiagnostics.log("webview", "onPageFinished $finishedUrl")
                            if (view == null) return
                            view.postDelayed({ dumpDomState(view) }, 4_000)
                            // failure-3 follow-up (owner protocol, 2026-10-28): the
                            // decisive visual-state evidence chain — runs ONCE per
                            // WebView instance, never for the isolated data:-URL
                            // paint test itself. Read-only probes + a diagnostic-only
                            // force-visibility experiment + restore + isolated paint
                            // test; every step leaves a timestamped evidence line.
                            if (!experimentRan && finishedUrl != null && !finishedUrl.startsWith("data:")) {
                                experimentRan = true
                                view.postDelayed({ runVisualEvidenceSequence(view, url) }, 5_200)
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
                    }
                    loadUrl(url)
                }
            }
        )
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
 * failure-3 visual-state evidence suite (0.3.5-e2, owner protocol 2026-10-28)
 * ============================================================================
 * Established device/AVD fact: DOM fully populated (rootChildren=1,
 * rootHtmlLen=15007, welcome text present), page background paints, content
 * pixels never appear, on BOTH raster paths (GPU + LAYER_TYPE_SOFTWARE).
 *
 * What was still missing — and what this suite captures, in one timestamped
 * chain, without inventing selectors (everything is read from the LIVE DOM):
 *
 *  1. visual-state — document.visibilityState/hidden (the WebView-visible
 *     state question, i.e. whether installDocumentHiddenMotionPause sees a
 *     hidden document), the data-document-hidden marker, computed
 *     display/visibility/opacity/color/background + getBoundingClientRect()
 *     for html, body, #root and the REAL top-3-level subtree (walked from the
 *     loaded DOM), elementFromPoint() at five viewport points, live animation
 *     list with play states, iframe count and shadow-root markers.
 *  2. bitmap[before] — the WebView's own painted output via view.draw():
 *     quantized pixel statistics + a PNG saved under files/logs/. This is the
 *     "did the renderer actually produce pixels" evidence, separable from a
 *     black SCREEN (occlusion above the WebView would leave the bitmap intact).
 *  3. force-vis (diagnostic-only) — inline-forces opacity/visibility/display
 *     on the walked subtree, removes data-document-hidden, and paints a bright
 *     #ffcc00 marker background on html/body; then bitmap[forced]. If pixels
 *     turn yellow but content stays missing, style restyling reaches the
 *     compositor and the content itself is the problem; if content appears,
 *     it was style-hidden (opacity/visibility/animation state) — the walk
 *     names the element. Original inline styles are restored afterwards.
 *  4. minimal-html — an isolated solid-background page with visible text and
 *     an interactive button loaded in the SAME WebView instance; bitmap[minimal]
 *     separates a general WebView paint failure from a ZCode-frontend-specific
 *     one. This is evidence only — never shipped as a UI replacement.
 *
 * Privacy: no storage/cookie/token access; the walk logs geometry + computed
 * style values only. PNG captures stay on-device (files/logs/).
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
  return JSON.stringify({ vis: document.visibilityState, hidden: document.hidden, focus: document.hasFocus(), docHiddenAttr: document.documentElement.hasAttribute('data-document-hidden'), bootTheme: document.documentElement.getAttribute('data-zcode-bootstrap-theme'), dark: document.documentElement.classList.contains('dark'), iframes: document.querySelectorAll('iframe').length, anims: anims, hits: hits, nodes: nodes });
})()"""

private const val JS_FORCE_VIS = """(function(){
  var root = document.getElementById('root');
  if (!root) return JSON.stringify({ error: 'no-root' });
  var els = [document.documentElement, document.body, root];
  (function collect(el, d){ if (!el || d > 3 || els.length > 40) return; var k = el.children || []; for (var i = 0; i < k.length && i < 8; i++) { els.push(k[i]); collect(k[i], d + 1); } })(root, 0);
  var PROPS = ['opacity','visibility','display','transform','animation','transition','content-visibility','background'];
  var saved = [];
  els.forEach(function(el){ var before = {}; PROPS.forEach(function(p){ before[p] = el.style.getPropertyValue(p); }); saved.push(before); el.style.setProperty('opacity','1','important'); el.style.setProperty('visibility','visible','important'); if (getComputedStyle(el).display === 'none') el.style.setProperty('display','block','important'); el.style.setProperty('transform','none','important'); el.style.setProperty('animation','none','important'); el.style.setProperty('transition','none','important'); el.style.setProperty('content-visibility','visible','important'); });
  var attrWas = document.documentElement.hasAttribute('data-document-hidden');
  document.documentElement.removeAttribute('data-document-hidden');
  document.documentElement.style.setProperty('background','#ffcc00','important');
  document.body.style.setProperty('background','#ffcc00','important');
  window.__e2restore = { els: els, saved: saved, props: PROPS };
  return JSON.stringify({ forced: els.length, docHiddenAttrWas: attrWas, vis: document.visibilityState });
})()"""

private const val JS_RESTORE = """(function(){
  var st = window.__e2restore;
  if (!st) return JSON.stringify({ restored: 0 });
  var n = 0;
  st.els.forEach(function(el, i){ var before = st.saved[i] || {}; st.props.forEach(function(p){ if (before[p]) { el.style.setProperty(p, before[p]); } else { el.style.removeProperty(p); } }); n++; });
  delete window.__e2restore;
  return JSON.stringify({ restored: n });
})()"""

/**
 * Isolated paint test per owner protocol: solid background + visible text +
 * an interactive button, loaded into the SAME WebView instance. Evidence
 * only — never a UI replacement.
 */
private const val MINIMAL_PAINT_HTML =
    "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\"></head>" +
        "<body style=\"margin:0;background:#e11d48;height:100vh;\">" +
        "<div style=\"font:700 44px sans-serif;color:#ffffff;padding:36px 24px;\">E2 PAINT TEST</div>" +
        "<button id=\"e2t\" onclick=\"document.getElementById('e2t').textContent='TAPPED-OK'\" " +
        "style=\"font:700 30px sans-serif;padding:26px 34px;margin:20px 24px;\">BUTTON</button>" +
        "</body></html>"

private fun evaluateJs(view: WebView, script: String, onResult: (String) -> Unit) {
    runCatching { view.evaluateJavascript(script) { value -> onResult(value) } }
        .onFailure { WebDiagnostics.log("probe", "evaluateJavascript failed: ${it.message}") }
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

/**
 * One-shot evidence chain (UI thread via evaluateJavascript callbacks and
 * view.postDelayed): visual-state -> bitmap[before] -> force-vis ->
 * bitmap[forced] -> restore -> minimal-html -> bitmap[minimal] -> reload.
 */
private fun runVisualEvidenceSequence(view: WebView, originalUrl: String) {
    WebDiagnostics.log("probe", "visual evidence sequence start (one-shot per WebView)")
    evaluateJs(view, JS_VISUAL_STATE) { value ->
        WebDiagnostics.log("probe", "visual-state: ${value.take(2400)}")
        captureAndAnalyze(view, "before")
        evaluateJs(view, JS_FORCE_VIS) { fv ->
            WebDiagnostics.log("probe", "force-vis: ${fv.take(300)}")
            view.postDelayed({
                captureAndAnalyze(view, "forced")
                evaluateJs(view, JS_RESTORE) { r ->
                    WebDiagnostics.log("probe", "styles-restored: ${r.take(160)}")
                    view.postDelayed({
                        WebDiagnostics.log("probe", "minimal-html: loading isolated paint test in same WebView")
                        runCatching {
                            view.loadDataWithBaseURL(null, MINIMAL_PAINT_HTML, "text/html", "utf-8", null)
                        }.onFailure { WebDiagnostics.log("probe", "minimal-html load failed: ${it.message}") }
                        view.postDelayed({
                            captureAndAnalyze(view, "minimal")
                            view.postDelayed({
                                WebDiagnostics.log("probe", "minimal-html done; reloading original page")
                                view.loadUrl(originalUrl)
                            }, 500)
                        }, 2_500)
                    }, 400)
                }
            }, 1_000)
        }
    }
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
