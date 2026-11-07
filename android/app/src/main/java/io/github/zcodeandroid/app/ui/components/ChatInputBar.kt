package io.github.zcodeandroid.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.zcodeandroid.app.ui.theme.ZcodeThemeValues

/**
 * The composer (owner UI brief): a rounded-24dp input pinned above the IME
 * (the screen owns imePadding/navigationBarsPadding), an attach affordance
 * on the leading edge, and ONE circular action button on the trailing edge
 * — send, or stop while the agent is running. Never both.
 *
 * Attach honesty: onAttach opens the SAF document picker and imports the
 * picked files into the workspace (the same contract the diagnostics
 * overlay uses). The button is hidden when no handler is provided.
 */
@Composable
fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    running: Boolean,
    connected: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    onAttach: (() -> Unit)? = null,
    placeholder: String = "Message the agent…",
) {
    Surface(tonalElevation = 2.dp) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(
                    horizontal = ZcodeThemeValues.spacing.md,
                    vertical = ZcodeThemeValues.spacing.sm,
                ),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(ZcodeThemeValues.spacing.sm),
        ) {
            if (onAttach != null) {
                IconButton(onClick = onAttach, enabled = connected) {
                    Icon(
                        Icons.Outlined.AttachFile,
                        contentDescription = "Attach files to the workspace",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(placeholder) },
                enabled = connected,
                maxLines = 6,
                shape = RoundedCornerShape(ZcodeThemeValues.radii.input),
            )
            FilledIconButton(
                onClick = if (running) onStop else onSend,
                enabled = if (running) true else (connected && value.isNotBlank()),
                modifier = Modifier.padding(bottom = ZcodeThemeValues.spacing.sm),
            ) {
                if (running) {
                    Icon(Icons.Filled.Clear, contentDescription = "Stop generation")
                } else {
                    Icon(Icons.Filled.Send, contentDescription = "Send message")
                }
            }
        }
    }
}
