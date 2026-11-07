package io.github.zcodeandroid.app.ui.conversation

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.zcodeandroid.app.local.RuntimeBus
import io.github.zcodeandroid.app.local.RuntimePaths
import io.github.zcodeandroid.app.ui.components.AgentMessageBubble
import io.github.zcodeandroid.app.ui.components.ChatInputBar
import io.github.zcodeandroid.app.ui.components.SectionHeader
import io.github.zcodeandroid.app.ui.components.StatusDot
import io.github.zcodeandroid.app.ui.components.ToolCard
import io.github.zcodeandroid.app.ui.components.UserMessageBubble
import io.github.zcodeandroid.app.ui.components.ZCodeTopBar
import io.github.zcodeandroid.app.ui.theme.CodeStyle
import io.github.zcodeandroid.app.ui.theme.THEME_DARK
import io.github.zcodeandroid.app.ui.theme.THEME_LIGHT
import io.github.zcodeandroid.app.ui.theme.THEME_SYSTEM
import io.github.zcodeandroid.app.ui.theme.ZcodeTheme
import io.github.zcodeandroid.app.ui.theme.ZcodeThemeValues
import java.io.File

/**
 * Native chat (owner UI brief): the timeline reads newest-at-bottom via
 * `reverseLayout = true` (index 0 == newest), bubbles for prose, terminal
 * cards for tool activity, the composer pinned above the IME. The model
 * button in the top bar opens the real config-option picker; nothing is
 * presented as a working feature that the runtime does not provide.
 *
 * CI marker: "NativeConversation: surface visible" fires here too (the
 * chat screen is a native product surface; dark-mode A/B reads the same
 * contract from whichever surface is visible).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: ConversationViewModel,
    initialTaskId: String?,
    openMostRecent: Boolean,
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenWorkspace: () -> Unit,
    themeMode: Int,
    onSetThemeMode: (Int) -> Unit,
) {
    val state by vm.state.collectAsState()
    val listState = rememberLazyListState()
    var showHistory by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var input by rememberSaveable { mutableStateOf("") }
    var restoredTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    BackHandler { onBack() }

    if (!LocalInspectionMode.current) {
        val dark = (LocalConfiguration.current.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        LaunchedEffect(Unit) {
            android.util.Log.i("NativeConversation", "surface visible dark=$dark")
        }
    }

    // Process-death restore / explicit Home card open: the task id goes to
    // the VM before start. The FAB path passes null + openMostRecent=false.
    LaunchedEffect(Unit) {
        if (vm.restoreTaskId == null && restoredTaskId != null) {
            vm.restoreTaskId = restoredTaskId
        } else if (vm.restoreTaskId == null && initialTaskId != null) {
            vm.restoreTaskId = initialTaskId
        }
        vm.openMostRecentOnStart = openMostRecent
        vm.start()
    }
    LaunchedEffect(state.selectedTaskId) {
        state.selectedTaskId?.let { restoredTaskId = it }
    }

    // reverseLayout: "at the bottom" == firstVisibleItemIndex near 0. While
    // the user sits at the newest row, new rows keep it pinned; scrolling
    // up through history never yanks the list.
    val pinnedToNewest by remember {
        derivedStateOf { listState.firstVisibleItemIndex <= 1 }
    }
    LaunchedEffect(state.timeline.size, state.running) {
        if (pinnedToNewest && state.timeline.isNotEmpty()) {
            listState.scrollToItem(0)
        }
    }

    // Attach: SAF import into the workspace (same contract as the
    // diagnostics overlay's workspace import — the file becomes readable
    // by the agent; the composer stays honest about what was done).
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        java.util.concurrent.Executors.newSingleThreadExecutor().execute {
            val ws = RuntimePaths.workspace(context)
            val names = uris.mapNotNull { uri ->
                runCatching {
                    val name = queryDisplayName(context, uri)
                        ?: "file-${System.currentTimeMillis()}"
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        File(ws, name).outputStream().use { input.copyTo(it) }
                    }
                    name
                }.getOrNull()
            }
            val note = if (names.isEmpty()) "attach copy failed (see log)"
            else "imported: ${names.joinToString()}"
            RuntimeBus.append("workspace attach: $note")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .navigationBarsPadding(),
    ) {
        ZCodeTopBar(
            title = "ZCode",
            subtitle = state.selectedTaskId?.let { "This device · ${it.take(8)}" } ?: "This device",
            statusColor = when (state.conn) {
                ConversationViewModel.Conn.READY -> ZcodeThemeValues.statusColors.completed
                ConversationViewModel.Conn.CONNECTING -> ZcodeThemeValues.statusColors.running
                ConversationViewModel.Conn.CLOSED -> ZcodeThemeValues.statusColors.failed
            },
            statusLabel = when (state.conn) {
                ConversationViewModel.Conn.READY -> "runtime connected"
                ConversationViewModel.Conn.CONNECTING -> "runtime connecting"
                ConversationViewModel.Conn.CLOSED -> "runtime disconnected"
            },
            modelName = state.modelName,
            onModelClick = if (state.conn == ConversationViewModel.Conn.READY) {
                { vm.openModelSheet() }
            } else null,
            onBack = null,
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back to home")
                }
            },
            actions = {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Task history") },
                        leadingIcon = { Icon(Icons.Filled.List, contentDescription = null) },
                        onClick = { menuOpen = false; showHistory = true },
                    )
                    DropdownMenuItem(
                        text = { Text("Workspace") },
                        leadingIcon = { Icon(Icons.Filled.Folder, contentDescription = null) },
                        onClick = { menuOpen = false; onOpenWorkspace() },
                    )
                    DropdownMenuItem(
                        text = { Text("Diagnostics") },
                        leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) },
                        onClick = { menuOpen = false; onOpenDiagnostics() },
                    )
                    val nextTheme = when (themeMode) {
                        THEME_SYSTEM -> THEME_DARK
                        THEME_DARK -> THEME_LIGHT
                        else -> THEME_SYSTEM
                    }
                    val themeLabel = when (nextTheme) {
                        THEME_SYSTEM -> "Theme: follow system"
                        THEME_DARK -> "Theme: dark"
                        else -> "Theme: light"
                    }
                    DropdownMenuItem(
                        text = { Text(themeLabel) },
                        onClick = { menuOpen = false; onSetThemeMode(nextTheme) },
                    )
                }
            },
        )
        state.sendError?.let { message ->
            SendErrorRow(message, onDismiss = { vm.dismissSendError() })
        }
        if (!state.hasSelectedTask && state.tasks.isEmpty()) {
            io.github.zcodeandroid.app.ui.components.EmptyState(
                title = "New conversation",
                body = "Type below to start the first task on this device. " +
                    "The ZCode runtime runs locally; your prompt goes to the connected provider.",
            )
        }
        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                vertical = ZcodeThemeValues.spacing.sm,
            ),
        ) {
            items(
                state.timeline.asReversed(),
                key = { item -> stableKey(item) },
            ) { item ->
                TimelineRow(item, onRespond = { requestId, option ->
                    vm.respondPermission(requestId, option)
                })
            }
        }
        ChatInputBar(
            value = input,
            onValueChange = { input = it },
            running = state.running,
            connected = state.conn == ConversationViewModel.Conn.READY,
            onSend = {
                vm.send(input)
                input = ""
            },
            onStop = { vm.stop() },
            onAttach = if (state.conn == ConversationViewModel.Conn.READY) {
                { importLauncher.launch(arrayOf("*/*")) }
            } else null,
        )
    }

    if (showHistory) {
        ModalBottomSheet(onDismissRequest = { showHistory = false }) {
            SectionHeader("Task history")
            if (state.tasks.isEmpty() && state.listError != null) {
                Text(
                    "Task history unavailable: ${state.listError}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(ZcodeThemeValues.spacing.lg),
                )
            }
            state.tasks.forEach { task ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showHistory = false
                            vm.openTask(task.taskId)
                        }
                        .padding(
                            horizontal = ZcodeThemeValues.spacing.lg,
                            vertical = ZcodeThemeValues.spacing.md,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            task.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (task.taskId == state.selectedTaskId) {
                                FontWeight.SemiBold
                            } else {
                                FontWeight.Normal
                            },
                        )
                        Text(
                            "Task " + task.taskId.take(8),
                            style = CodeStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        Icons.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.tasks.isEmpty() && state.listError == null) {
                Text(
                    "No tasks recorded yet on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(ZcodeThemeValues.spacing.lg),
                )
            }
            Spacer(Modifier.padding(ZcodeThemeValues.spacing.xl))
        }
    }

    state.modelSheet?.let { sheet ->
        ModalBottomSheet(onDismissRequest = { vm.closeModelSheet() }) {
            SectionHeader("Model")
            if (sheet.loading) {
                Text(
                    "Reading model options…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(ZcodeThemeValues.spacing.lg),
                )
            }
            sheet.unavailableReason?.let { reason ->
                Text(
                    reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(ZcodeThemeValues.spacing.lg),
                )
            }
            state.modelError?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(ZcodeThemeValues.spacing.lg),
                )
            }
            sheet.options.forEach { option ->
                val selected = option.value == sheet.currentModelId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { vm.selectModel(option) }
                        .padding(
                            horizontal = ZcodeThemeValues.spacing.lg,
                            vertical = ZcodeThemeValues.spacing.md,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZcodeThemeValues.spacing.sm),
                ) {
                    if (selected) {
                        StatusDot(
                            ZcodeThemeValues.statusColors.completed,
                            contentDescription = "current model",
                        )
                    }
                    Text(
                        option.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.padding(ZcodeThemeValues.spacing.xl))
        }
    }
}

private fun stableKey(item: ConversationViewModel.TimelineItem): String = when (item) {
    is ConversationViewModel.TimelineItem.UserMsg -> item.id
    is ConversationViewModel.TimelineItem.AssistantMsg -> item.id
    is ConversationViewModel.TimelineItem.ThoughtMsg -> item.id
    is ConversationViewModel.TimelineItem.ToolRow -> "tool-" + item.toolId
    is ConversationViewModel.TimelineItem.PermissionCard -> "perm-" + item.requestId
    is ConversationViewModel.TimelineItem.Notice -> item.id
}

@Composable
private fun SendErrorRow(message: String, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = ZcodeThemeValues.spacing.lg,
                vertical = ZcodeThemeValues.spacing.sm,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZcodeThemeValues.spacing.sm),
        ) {
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

@Composable
private fun TimelineRow(
    item: ConversationViewModel.TimelineItem,
    onRespond: (String, ConversationViewModel.PermOption) -> Unit,
) {
    when (item) {
        is ConversationViewModel.TimelineItem.UserMsg -> UserMessageBubble(item.text)
        is ConversationViewModel.TimelineItem.AssistantMsg -> AgentMessageBubble(item.text)
        is ConversationViewModel.TimelineItem.ThoughtMsg -> ThoughtRow(item)
        is ConversationViewModel.TimelineItem.ToolRow -> ToolCard(
            name = item.name,
            title = item.title,
            status = item.status,
        )
        is ConversationViewModel.TimelineItem.PermissionCard -> PermissionCardView(item, onRespond)
        is ConversationViewModel.TimelineItem.Notice -> NoticeRow(item)
    }
}

@Composable
private fun ThoughtRow(item: ConversationViewModel.TimelineItem.ThoughtMsg) {
    var expanded by remember(item.id) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = ZcodeThemeValues.spacing.lg,
                vertical = ZcodeThemeValues.spacing.xs,
            )
            .clickable { expanded = !expanded },
    ) {
        Text(
            if (expanded) "Thought ▾" else "Thought ▸",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        androidx.compose.animation.AnimatedVisibility(visible = expanded) {
            Text(
                item.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = ZcodeThemeValues.spacing.xs),
            )
        }
    }
}

/**
 * Approval request: bordered card in the approval color — it must never
 * read as a completed operation. Each option is a button that sends that
 * option's own decision kind.
 */
@Composable
private fun PermissionCardView(
    item: ConversationViewModel.TimelineItem.PermissionCard,
    onRespond: (String, ConversationViewModel.PermOption) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = ZcodeThemeValues.spacing.lg,
                vertical = ZcodeThemeValues.spacing.sm,
            )
            .border(
                width = 1.dp,
                color = ZcodeThemeValues.statusColors.approvalRequired,
                shape = MaterialTheme.shapes.medium,
            ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(ZcodeThemeValues.spacing.md),
            verticalArrangement = Arrangement.spacedBy(ZcodeThemeValues.spacing.sm),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZcodeThemeValues.spacing.sm),
            ) {
                StatusDot(
                    ZcodeThemeValues.statusColors.approvalRequired,
                    contentDescription = "approval required",
                )
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (item.description.isNotBlank()) {
                Text(
                    item.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Options list is a Row while options are <= 3 (upstream
            // permission prompts offer <= 3). With wider sets this would
            // overflow horizontally — use FlowRow if that ever changes.
            Row(horizontalArrangement = Arrangement.spacedBy(ZcodeThemeValues.spacing.sm)) {
                item.options.forEach { option ->
                    Button(onClick = { onRespond(item.requestId, option) }) {
                        Text(option.name)
                    }
                }
            }
        }
    }
}

@Composable
private fun NoticeRow(item: ConversationViewModel.TimelineItem.Notice) {
    Text(
        item.text,
        style = MaterialTheme.typography.labelMedium,
        color = if (item.isError) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = ZcodeThemeValues.spacing.lg,
                vertical = ZcodeThemeValues.spacing.xs,
            ),
    )
}

/** Local SAF display-name lookup (same contract as the diagnostics overlay). */
private fun queryDisplayName(
    context: android.content.Context,
    uri: android.net.Uri,
): String? = runCatching {
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
    }
}.getOrNull()

@Preview(name = "Chat pieces — bubbles + approval + tool", showBackground = true, widthDp = 380)
@Composable
private fun ChatPiecesPreview() {
    ZcodeTheme(darkTheme = false) {
        Column {
            PermissionCardView(
                ConversationViewModel.TimelineItem.PermissionCard(
                    requestId = "r1",
                    title = "Run command",
                    description = "npm test",
                    options = listOf(
                        ConversationViewModel.PermOption("allow", "Allow", "allow"),
                        ConversationViewModel.PermOption("deny", "Deny", "deny"),
                    ),
                ),
                onRespond = { _, _ -> },
            )
            UserMessageBubble("Run the failing test first")
            AgentMessageBubble("I'll start with **auth.spec.ts**.")
            ToolCard(name = "bash", title = "npx jest auth", status = "completed")
            NoticeRow(ConversationViewModel.TimelineItem.Notice("d1", "Completed (end_turn)", false))
        }
    }
}
