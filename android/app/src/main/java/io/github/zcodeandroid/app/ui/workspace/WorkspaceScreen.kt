package io.github.zcodeandroid.app.ui.workspace

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.zcodeandroid.app.ui.components.ErrorState
import io.github.zcodeandroid.app.ui.components.SectionHeader
import io.github.zcodeandroid.app.ui.theme.CodeStyle
import io.github.zcodeandroid.app.ui.theme.ZcodeThemeValues

/**
 * P4 workspace screen (REVISED architecture §5 Phase 4): read-only file
 * explorer + git status over verified runtime contracts. Boundaries are
 * explicit — browsing is rooted at the app workspace; unsupported
 * capabilities (file writes, terminal) are NOT exposed as working
 * features. Failures are explicit, recoverable error states.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreen(
    vm: WorkspaceViewModel,
    onBack: () -> Unit,
) {
    val state by vm.state.collectAsState()

    LaunchedEffect(Unit) { vm.start() }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                title = {
                    Column {
                        Text("Workspace", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "This device · read-only",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.refreshGit() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh git status")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            state.error?.let { message ->
                ErrorState(
                    title = "Workspace operation failed",
                    detail = message,
                    retryLabel = "Dismiss",
                    onRetry = { vm.dismissError() },
                )
            }
            state.preview?.let { preview ->
                PreviewPane(preview = preview, onClose = { vm.closePreview() })
                return@Column
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = ZcodeThemeValues.spacing.lg, vertical = ZcodeThemeValues.spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (state.cwd != state.rootPath) {
                            Text(
                                "…/" + state.cwd.substringAfterLast('/'),
                                style = CodeStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "Up",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clickable { vm.openParent() }
                                    .padding(ZcodeThemeValues.spacing.sm),
                            )
                        } else {
                            Text(
                                "Workspace root",
                                style = CodeStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (state.loading) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(ZcodeThemeValues.spacing.lg),
                            horizontalArrangement = Arrangement.spacedBy(ZcodeThemeValues.spacing.md),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.height(16.dp), strokeWidth = 2.dp)
                            Text("Reading directory…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                items(state.entries, key = { it.path }) { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (entry.isDirectory) vm.openDir(entry.path) else vm.openFile(entry.path)
                            }
                            .padding(
                                horizontal = ZcodeThemeValues.spacing.lg,
                                vertical = ZcodeThemeValues.spacing.sm,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ZcodeThemeValues.spacing.md),
                    ) {
                        Icon(
                            if (entry.isDirectory) Icons.Filled.Folder else Icons.Filled.Description,
                            contentDescription = if (entry.isDirectory) "Directory" else "File",
                            tint = if (entry.isDirectory) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Text(
                            entry.name + if (entry.isSymbolicLink) " (link)" else "",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                if (!state.loading && state.entries.isEmpty() && state.error == null) {
                    item {
                        Text(
                            "Empty directory.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(ZcodeThemeValues.spacing.lg),
                        )
                    }
                }
                item { GitSection(state) }
            }
        }
    }
}

@Composable
private fun GitSection(state: WorkspaceViewModel.UiState) {
    SectionHeader("Git")
    val git = state.git
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ZcodeThemeValues.spacing.lg, vertical = ZcodeThemeValues.spacing.xs),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(ZcodeThemeValues.spacing.md),
            verticalArrangement = Arrangement.spacedBy(ZcodeThemeValues.spacing.xs),
        ) {
            if (state.gitLoading) {
                Text("Reading git status…", style = MaterialTheme.typography.bodySmall)
            } else if (git == null) {
                Text(
                    "Git status unavailable on this runtime.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (!git.gitAvailable) {
                Text(
                    "git is not available inside the runtime.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (!git.isRepository) {
                Text(
                    "Not a git repository.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(ZcodeThemeValues.spacing.md)) {
                    Text(
                        git.branchName ?: "(detached)",
                        style = CodeStyle,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (git.ahead > 0) Text("↑${git.ahead}", style = CodeStyle)
                    if (git.behind > 0) Text("↓${git.behind}", style = CodeStyle)
                    if (git.isDirty) {
                        Text(
                            "dirty",
                            style = MaterialTheme.typography.labelSmall,
                            color = ZcodeThemeValues.statusColors.running,
                        )
                    }
                }
                state.changes.take(20).forEach { change ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(ZcodeThemeValues.spacing.sm),
                    ) {
                        Text(
                            change.kind,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            change.path,
                            style = CodeStyle,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                        )
                        if (change.added > 0) {
                            Text("+${change.added}", style = CodeStyle, color = ZcodeThemeValues.statusColors.completed)
                        }
                        if (change.removed > 0) {
                            Text("-${change.removed}", style = CodeStyle, color = ZcodeThemeValues.statusColors.failed)
                        }
                    }
                }
                if (state.changes.size > 20) {
                    Text(
                        "…and ${state.changes.size - 20} more changes",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.changes.isEmpty()) {
                    Text(
                        "No unstaged changes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(ZcodeThemeValues.spacing.xl))
}

@Composable
private fun PreviewPane(preview: WorkspaceViewModel.PreviewUi, onClose: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ZcodeThemeValues.spacing.lg, vertical = ZcodeThemeValues.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZcodeThemeValues.spacing.md),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    preview.path.substringAfterLast('/'),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    preview.path,
                    style = CodeStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Text(
                "Close",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable { onClose() }
                    .padding(ZcodeThemeValues.spacing.sm),
            )
        }
        Box(modifier = Modifier.weight(1f)) {
            when {
                preview.isBinary -> Text(
                    "Binary file — text preview unavailable.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(ZcodeThemeValues.spacing.lg),
                )
                else -> Column {
                    Text(
                        preview.content,
                        style = CodeStyle,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = ZcodeThemeValues.spacing.lg),
                    )
                    if (preview.truncated) {
                        Text(
                            "Preview truncated by the runtime.",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(ZcodeThemeValues.spacing.lg),
                        )
                    }
                }
            }
        }
    }
}
