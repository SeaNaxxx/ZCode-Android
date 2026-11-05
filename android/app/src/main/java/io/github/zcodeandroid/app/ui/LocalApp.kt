package io.github.zcodeandroid.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.zcodeandroid.app.BuildConfig
import io.github.zcodeandroid.app.backup.ZcodeBackup
import io.github.zcodeandroid.app.local.DiagLog
import io.github.zcodeandroid.app.local.LocalServerProbe
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
            "The native conversation UI is the product (Native-only decision " +
                "2026-11-05). This overlay holds the runtime log, workspace " +
                "imports, backup/restore, and the app theme.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        TextButton(onClick = onBackToWeb) { Text("← Back to ZCode") }

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


private fun queryDisplayName(context: android.content.Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.query(
            uri, null, null, null, null
        )?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull()


