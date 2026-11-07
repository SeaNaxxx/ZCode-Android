package io.github.zcodeandroid.app.ui.conversation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.zcodeandroid.app.ui.components.EmptyState
import io.github.zcodeandroid.app.ui.components.SectionHeader
import io.github.zcodeandroid.app.ui.components.StatusDot
import io.github.zcodeandroid.app.ui.theme.CodeStyle
import io.github.zcodeandroid.app.ui.theme.THEME_DARK
import io.github.zcodeandroid.app.ui.theme.THEME_LIGHT
import io.github.zcodeandroid.app.ui.theme.THEME_SYSTEM
import io.github.zcodeandroid.app.ui.theme.ZcodeThemeValues

/**
 * P2 native conversation screen (REVISED architecture §5 Phase 2) and P3
 * agent-activity rendering (§5 Phase 3). Workbench layout: the turn
 * timeline reads top-down, the composer stays at the thumb zone, the top
 * bar carries the host identity (`This device`) and the connection dot.
 * No bubbles-heavy chat template, no decorative chrome — rows earn their
 * place by function: prose, tool activity (visually distinct from prose
 * per Phase 3), approvals (never mistakable for completed work), notices.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    vm: ConversationViewModel,
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

    // CI/diagnostics marker: the native product surface is visible.
    val dark = (LocalConfiguration.current.uiMode and
        android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES
    LaunchedEffect(Unit) {
        android.util.Log.i("NativeConversation", "surface visible dark=$dark")
    }

    // Process-death restore: the saved task id goes to the VM before start.
    LaunchedEffect(Unit) {
        if (vm.restoreTaskId == null && restoredTaskId != null) {
            vm.restoreTaskId = restoredTaskId
        }
        vm.start()
    }
    LaunchedEffect(state.selectedTaskId) {
        state.selectedTaskId?.let { restoredTaskId = it }
    }

    // Keep the newest row visible while the user sits at the bottom.
    val pinnedToBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= info.totalItemsCount - 2 || info.totalItemsCount == 0
        }
    }
    LaunchedEffect(state.timeline.size, state.running) {
        if (pinnedToBottom && state.timeline.isNotEmpty()) {
            listState.animateScrollToItem(state.timeline.size - 1)
        }
    }

    LaunchedEffect(Unit) { vm.start() }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                title = {
                    Column {
                        Text("ZCode", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "This device",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    val (dot, label) = when (state.conn) {
                        ConversationViewModel.Conn.READY ->
                            ZcodeThemeValues.statusColors.completed to "runtime connected"
                        ConversationViewModel.Conn.CONNECTING ->
                            ZcodeThemeValues.statusColors.running to "runtime connecting"
                        ConversationViewModel.Conn.CLOSED ->
                            ZcodeThemeValues.statusColors.failed to "runtime disconnected"
                    }
                    Box(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        StatusDot(color = dot, contentDescription = label)
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
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            state.sendError?.let { message ->
                SendErrorRow(message, onRetry = { vm.dismissSendError() })
            }
            if (!state.hasSelectedTask && state.tasks.isEmpty()) {
                EmptyState(
                    title = "No conversations yet",
                    body = "Type below to start the first task on this device. " +
                        "The ZCode runtime runs locally; your prompt goes to the connected provider.",
                )
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    vertical = ZcodeThemeValues.spacing.sm,
                ),
            ) {
                items(state.timeline, key = { item -> stableKey(item) }) { item ->
                    TimelineRow(item, onRespond = { requestId, option -> vm.respondPermission(requestId, option) })
                }
            }
            ComposerRow(
                value = input,
                running = state.running,
                connected = state.conn == ConversationViewModel.Conn.READY,
                onValueChange = { input = it },
                onSend = {
                    vm.send(input)
                    input = ""
                },
                onStop = { vm.stop() },
            )
        }
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
private fun SendErrorRow(message: String, onRetry: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = ZcodeThemeValues.spacing.lg, vertical = ZcodeThemeValues.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZcodeThemeValues.spacing.sm),
        ) {
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) { Text("Dismiss") }
        }
    }
}

@Composable
private fun TimelineRow(
    item: ConversationViewModel.TimelineItem,
    onRespond: (String, ConversationViewModel.PermOption) -> Unit,
) {
    when (item) {
        is ConversationViewModel.TimelineItem.UserMsg -> UserMsgRow(item)
        is ConversationViewModel.TimelineItem.AssistantMsg -> AssistantMsgRow(item)
        is ConversationViewModel.TimelineItem.ThoughtMsg -> ThoughtRow(item)
        is ConversationViewModel.TimelineItem.ToolRow -> ToolRowView(item)
        is ConversationViewModel.TimelineItem.PermissionCard -> PermissionCardView(item, onRespond)
        is ConversationViewModel.TimelineItem.Notice -> NoticeRow(item)
    }
}

@Composable
private fun UserMsgRow(item: ConversationViewModel.TimelineItem.UserMsg) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ZcodeThemeValues.spacing.lg, vertical = ZcodeThemeValues.spacing.xs),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Text(
                item.text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(
                    horizontal = ZcodeThemeValues.spacing.md,
                    vertical = ZcodeThemeValues.spacing.sm,
                ),
            )
        }
    }
}

@Composable
private fun AssistantMsgRow(item: ConversationViewModel.TimelineItem.AssistantMsg) {
    if (item.text.isBlank()) return
    Text(
        item.text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ZcodeThemeValues.spacing.lg, vertical = ZcodeThemeValues.spacing.sm),
    )
}

@Composable
private fun ThoughtRow(item: ConversationViewModel.TimelineItem.ThoughtMsg) {
    var expanded by remember(item.id) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ZcodeThemeValues.spacing.lg, vertical = ZcodeThemeValues.spacing.xs)
            .clickable { expanded = !expanded },
    ) {
        Text(
            if (expanded) "Thought ▾" else "Thought ▸",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AnimatedVisibility(visible = expanded) {
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
 * Agent tool activity (Phase 3): visually distinct from prose — teal
 * chip, code font, explicit status. Running/completed/failed map to the
 * semantic status colors; unknown statuses render as-is, never guessed.
 */
@Composable
private fun ToolRowView(item: ConversationViewModel.TimelineItem.ToolRow) {
    val status = ZcodeThemeValues.statusColors
    val (dotColor, statusLabel) = when (item.status) {
        "in_progress", "running", "pending" -> status.running to "running"
        "completed", "success", "done" -> status.completed to "completed"
        "failed", "error" -> status.failed to "failed"
        else -> MaterialTheme.colorScheme.onSurfaceVariant to item.status
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ZcodeThemeValues.spacing.lg, vertical = ZcodeThemeValues.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZcodeThemeValues.spacing.sm),
    ) {
        Icon(
            Icons.Outlined.Build,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.padding(2.dp),
        )
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ZcodeThemeValues.spacing.sm)) {
                Text(item.name, style = CodeStyle, color = MaterialTheme.colorScheme.tertiary)
                StatusDot(dotColor, contentDescription = statusLabel)
                Text(statusLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (item.title.isNotBlank()) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                )
            }
        }
    }
}

/**
 * Approval request (Phase 3): bordered card in the approval color — it
 * must never read as a completed operation. Each option is a button that
 * sends that option's own decision kind.
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
            // P3 design note: options list is a Row while options are <= 3
            // (upstream permission prompts offer <= 3). With wider sets this
            // would overflow horizontally — use FlowRow if that ever changes
            // (documented in NATIVE-UI-INTEGRATION §4.4).
            Row(horizontalArrangement = Arrangement.spacedBy(ZcodeThemeValues.spacing.sm)) {
                item.options.forEach { option ->
                    androidx.compose.material3.Button(
                        onClick = { onRespond(item.requestId, option) },
                    ) {
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
            .padding(horizontal = ZcodeThemeValues.spacing.lg, vertical = ZcodeThemeValues.spacing.xs),
    )
}

@Composable
private fun ComposerRow(
    value: String,
    running: Boolean,
    connected: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(tonalElevation = 2.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = ZcodeThemeValues.spacing.md,
                    vertical = ZcodeThemeValues.spacing.sm,
                ),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(ZcodeThemeValues.spacing.sm),
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message the agent…") },
                enabled = connected,
                maxLines = 6,
                shape = MaterialTheme.shapes.small,
            )
            if (running) {
                androidx.compose.material3.FilledIconButton(
                    onClick = onStop,
                    modifier = Modifier.padding(bottom = ZcodeThemeValues.spacing.sm),
                ) {
                    Icon(Icons.Filled.Clear, contentDescription = "Stop generation")
                }
            } else {
                androidx.compose.material3.FilledIconButton(
                    onClick = onSend,
                    enabled = connected && value.isNotBlank(),
                    modifier = Modifier.padding(bottom = ZcodeThemeValues.spacing.sm),
                ) {
                    Icon(Icons.Filled.Send, contentDescription = "Send message")
                }
            }
        }
    }
}
