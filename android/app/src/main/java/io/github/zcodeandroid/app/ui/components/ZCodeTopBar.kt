package io.github.zcodeandroid.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.github.zcodeandroid.app.ui.theme.ZcodeThemeValues

/**
 * The one clean top bar (owner UI brief): identity on the left, connection
 * status as the navigation slot, the current model as a quiet text button
 * (only when the runtime reports one), and screen actions on the right.
 * No gradients, no elevation theatre — a surface-colored bar.
 *
 * Model honesty rule (FEATURE-MATRIX): the button appears only when a model
 * is actually known; otherwise it renders muted "Model" and is only enabled
 * when a click handler was provided.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZCodeTopBar(
    title: String,
    subtitle: String,
    statusColor: Color,
    statusLabel: String,
    modifier: Modifier = Modifier,
    modelName: String? = null,
    onModelClick: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        modifier = modifier,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        title = {
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        navigationIcon = {
            when {
                navigationIcon != null -> navigationIcon()
                else -> Box(
                    modifier = Modifier.padding(horizontal = ZcodeThemeValues.spacing.md),
                    contentAlignment = Alignment.Center,
                ) {
                    StatusDot(color = statusColor, contentDescription = statusLabel)
                }
            }
        },
        actions = {
            if (modelName != null || onModelClick != null) {
                TextButton(
                    onClick = { onModelClick?.invoke() },
                    enabled = onModelClick != null,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Model",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            modelName ?: "—",
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                        )
                    }
                }
            }
            actions()
        },
    )
}
