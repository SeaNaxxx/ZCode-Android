package io.github.zcodeandroid.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.zcodeandroid.app.ui.theme.CodeStyle
import io.github.zcodeandroid.app.ui.theme.ZcodeThemeValues

/**
 * Tool activity card (owner UI brief): terminal-style dark surface in both
 * themes — tool output is product content, not chrome. Icon + tool name +
 * semantic status dot on the header row; the tool's one-line title below;
 * a copy button whenever there is something copyable. Hairline outline so
 * the dark surface stays distinguishable from a dark theme background.
 * Flat: no shadow, no gradient.
 *
 * Copy honesty: the card copies exactly the text it displays (the tool's
 * title/command line). The pinned runtime exposes no separate output
 * payload on the native timeline, so nothing else is claimed.
 */
@Composable
fun ToolCard(
    name: String,
    title: String,
    status: String,
    modifier: Modifier = Modifier,
) {
    val terminal = ZcodeThemeValues.terminal
    val statusColors = ZcodeThemeValues.statusColors
    val (dotColor, statusLabel) = when (status) {
        "in_progress", "running", "pending" -> statusColors.running to "running"
        "completed", "success", "done" -> statusColors.completed to "completed"
        "failed", "error" -> statusColors.failed to "failed"
        else -> statusColors.running to status
    }
    val shape = RoundedCornerShape(ZcodeThemeValues.radii.md)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = ZcodeThemeValues.spacing.lg,
                vertical = ZcodeThemeValues.spacing.xs,
            )
            .background(terminal.background, shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(ZcodeThemeValues.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZcodeThemeValues.spacing.xs),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZcodeThemeValues.spacing.sm),
        ) {
            Icon(
                Icons.Outlined.Build,
                contentDescription = null,
                tint = terminal.accent,
                modifier = Modifier.size(16.dp),
            )
            Text(
                name,
                style = CodeStyle,
                color = terminal.accent,
                modifier = Modifier.weight(1f, fill = false),
            )
            StatusDot(dotColor, contentDescription = statusLabel)
            Text(
                statusLabel,
                style = MaterialTheme.typography.labelSmall,
                color = terminal.onBackground,
            )
            if (title.isNotBlank()) {
                CopyButton(text = title, tint = terminal.onBackground)
            }
        }
        if (title.isNotBlank()) {
            Text(
                title,
                style = CodeStyle,
                color = terminal.onBackground,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                maxLines = 6,
            )
        }
    }
}

@Composable
private fun CopyButton(text: String, tint: androidx.compose.ui.graphics.Color) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    IconButton(
        onClick = {
            clipboard.setText(AnnotatedString(text))
            copied = true
        },
        modifier = Modifier.size(32.dp),
    ) {
        Icon(
            Icons.Filled.ContentCopy,
            contentDescription = if (copied) "Copied" else "Copy",
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
    }
}
