package com.example.zcode_mobile.ui

import android.net.Uri
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
import com.example.zcode_mobile.R
import com.example.zcode_mobile.local.LocalServerService
import com.example.zcode_mobile.local.Provisioner
import com.example.zcode_mobile.local.RuntimeBus
import com.example.zcode_mobile.local.RuntimePaths
import com.example.zcode_mobile.local.RuntimeState
import com.example.zcode_mobile.local.ZcodeServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
                Text(
                    "First run downloads and verifies: Ubuntu base (arm64) + Node.js " +
                        "${RuntimePaths.NODE_VERSION} (checksums verified), then stages the official " +
                        "ZCode distribution embedded in this APK. Needs a few hundred MB of storage.",
                    style = MaterialTheme.typography.bodyMedium
                )
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

        Text("Runtime log", style = MaterialTheme.typography.titleSmall)
        LogConsole(logLines)
    }
}

@Composable
private fun StatusCard(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
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
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = WebViewClient()
                    loadUrl(url)
                }
            }
        )
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
