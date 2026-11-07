package io.github.zcodeandroid.app.ui.home

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.zcodeandroid.app.ui.components.EmptyState
import io.github.zcodeandroid.app.ui.components.ErrorState
import io.github.zcodeandroid.app.ui.components.StatusBadge
import io.github.zcodeandroid.app.ui.components.ZCodeTopBar
import io.github.zcodeandroid.app.ui.theme.CodeStyle
import io.github.zcodeandroid.app.ui.theme.THEME_DARK
import io.github.zcodeandroid.app.ui.theme.THEME_LIGHT
import io.github.zcodeandroid.app.ui.theme.THEME_SYSTEM
import io.github.zcodeandroid.app.ui.theme.ZcodeTheme
import io.github.zcodeandroid.app.ui.theme.ZcodeThemeValues

/**
 * Home (owner UI brief, Omnara/OpenClaw/Paseo direction): one quiet
 * dashboard — the workspace's tasks as calm cards with honest status
 * badges, ONE primary action (the new-conversation FAB), generous
 * whitespace. No gradients, no shadows, no decoration.
 *
 * CI marker: this is the start surface, so the "NativeConversation:
 * surface visible" contract log fires here (and in the chat screen).
 * The tag text is the established CI contract for "the native product
 * surface is visible" — see android-verify.yml frontend-smoke/dark-mode.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onOpenTask: (String) -> Unit,
    onNewChat: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenWorkspace: () -> Unit,
    themeMode: Int,
    onSetThemeMode: (Int) -> Unit,
) {
    val state by vm.state.collectAsState()

    if (!LocalInspectionMode.current) {
        val dark = (LocalConfiguration.current.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        LaunchedEffect(Unit) {
            android.util.Log.i("NativeConversation", "surface visible dark=$dark")
        }
        LaunchedEffect(Unit) { vm.start() }
    }

    HomeScreenContent(
        state = state,
        themeMode = themeMode,
        onOpenTask = onOpenTask,
        onNewChat = onNewChat,
        onOpenDiagnostics = onOpenDiagnostics,
        onOpenWorkspace = onOpenWorkspace,
        onSetThemeMode = onSetThemeMode,
        onDismissError = vm::dismissError,
        onRefresh = vm::refresh,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreenContent(
    state: HomeViewModel.UiState,
    themeMode: Int,
    onOpenTask: (String) -> Unit,
    onNewChat: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenWorkspace: () -> Unit,
    onSetThemeMode: (Int) -> Unit,
    onDismissError: () -> Unit,
    onRefresh: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            val (dot, label) = when (state.conn) {
                HomeViewModel.Conn.READY ->
                    ZcodeThemeValues.statusColors.completed to "runtime connected"
                HomeViewModel.Conn.CONNECTING ->
                    ZcodeThemeValues.statusColors.running to "runtime connecting"
                HomeViewModel.Conn.CLOSED ->
                    ZcodeThemeValues.statusColors.failed to "runtime disconnected"
            }
            ZCodeTopBar(
                title = "ZCode",
                subtitle = "This device",
                statusColor = dot,
                statusLabel = label,
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
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
        floatingActionButton = {
            FloatingActionButton(onClick = onNewChat) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = "New conversation",
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            state.error?.let { message ->
                ErrorState(
                    title = "Task list unavailable",
                    detail = message,
                    retryLabel = "Dismiss",
                    onRetry = onDismissError,
                )
            }
            if (state.cards.isEmpty() && state.error == null && !state.loading) {
                EmptyState(
                    title = "No conversations yet",
                    body = "Start the first task on this device with the + button. " +
                        "The ZCode runtime runs locally; your prompt goes to the connected provider.",
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    horizontal = ZcodeThemeValues.spacing.lg,
                    vertical = ZcodeThemeValues.spacing.md,
                ),
                verticalArrangement = Arrangement.spacedBy(ZcodeThemeValues.spacing.sm),
            ) {
                items(state.cards, key = { it.taskId }) { card ->
                    WorkspaceCard(
                        card = card,
                        badge = state.badges[card.taskId] ?: HomeViewModel.Badge.IDLE,
                        onClick = { onOpenTask(card.taskId) },
                    )
                }
            }
        }
    }
}

/** One workspace card: title, model line, relative time, status badge. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkspaceCard(
    card: HomeViewModel.WorkspaceCardUi,
    badge: HomeViewModel.Badge,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(ZcodeThemeValues.spacing.md),
            verticalArrangement = Arrangement.spacedBy(ZcodeThemeValues.spacing.xs),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZcodeThemeValues.spacing.sm),
            ) {
                Text(
                    card.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                )
                val (badgeColor, badgeLabel) = when (badge) {
                    HomeViewModel.Badge.IDLE ->
                        MaterialTheme.colorScheme.onSurfaceVariant to "Idle"
                    HomeViewModel.Badge.RUNNING ->
                        ZcodeThemeValues.statusColors.running to "Running"
                    HomeViewModel.Badge.COMPLETED ->
                        ZcodeThemeValues.statusColors.completed to "Completed"
                }
                StatusBadge(statusColor = badgeColor, label = badgeLabel)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZcodeThemeValues.spacing.sm),
            ) {
                Text(
                    relativeTime(card.updatedAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!card.model.isNullOrBlank()) {
                    Text(
                        "·",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        card.model,
                        style = CodeStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Relative time in the token typography — never a raw epoch in the UI. */
private fun relativeTime(epochMs: Long): String {
    if (epochMs <= 0L) return "—"
    val delta = System.currentTimeMillis() - epochMs
    val minutes = delta / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 60 * 24 -> "${minutes / 60}h ago"
        minutes < 60 * 24 * 30 -> "${minutes / (60 * 24)}d ago"
        else -> "${minutes / (60 * 24 * 30)}mo ago"
    }
}

@Preview(name = "Home — light", showBackground = true, widthDp = 380, heightDp = 640)
@Composable
private fun HomeScreenPreview() {
    ZcodeTheme(darkTheme = false) {
        HomeScreenContent(
            state = HomeViewModel.UiState(
                conn = HomeViewModel.Conn.READY,
                cards = listOf(
                    HomeViewModel.WorkspaceCardUi(
                        "s_1", "Fix the login flake", System.currentTimeMillis(), "glm-4.7",
                    ),
                    HomeViewModel.WorkspaceCardUi(
                        "s_2", "Migrate storage to Room", System.currentTimeMillis() - 3_600_000, null,
                    ),
                ),
                badges = mapOf("s_1" to HomeViewModel.Badge.RUNNING),
            ),
            themeMode = THEME_SYSTEM,
            onOpenTask = {}, onNewChat = {}, onOpenDiagnostics = {}, onOpenWorkspace = {},
            onSetThemeMode = {}, onDismissError = {}, onRefresh = {},
        )
    }
}

@Preview(name = "Home — dark", showBackground = true, widthDp = 380, heightDp = 640, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun HomeScreenDarkPreview() {
    ZcodeTheme(darkTheme = true) {
        HomeScreenContent(
            state = HomeViewModel.UiState(
                conn = HomeViewModel.Conn.READY,
                cards = listOf(
                    HomeViewModel.WorkspaceCardUi(
                        "s_1", "Fix the login flake", System.currentTimeMillis(), "glm-4.7",
                    ),
                ),
                badges = mapOf("s_1" to HomeViewModel.Badge.COMPLETED),
            ),
            themeMode = THEME_DARK,
            onOpenTask = {}, onNewChat = {}, onOpenDiagnostics = {}, onOpenWorkspace = {},
            onSetThemeMode = {}, onDismissError = {}, onRefresh = {},
        )
    }
}

@Preview(name = "Home — empty", showBackground = true, widthDp = 380, heightDp = 480)
@Composable
private fun HomeScreenEmptyPreview() {
    ZcodeTheme(darkTheme = false) {
        HomeScreenContent(
            state = HomeViewModel.UiState(conn = HomeViewModel.Conn.CONNECTING),
            themeMode = THEME_LIGHT,
            onOpenTask = {}, onNewChat = {}, onOpenDiagnostics = {}, onOpenWorkspace = {},
            onSetThemeMode = {}, onDismissError = {}, onRefresh = {},
        )
    }
}
