package io.github.zcodeandroid.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.zcodeandroid.app.ui.theme.ZcodeThemeValues

/**
 * P1 shared components (REVISED architecture §5 Phase 1 acceptance):
 * small, token-driven, purposeful. Used by every later screen so states
 * render identically everywhere. No decorative variants.
 */

/** Semantic status dot — the one status indicator style in the app. */
@Composable
fun StatusDot(color: Color, contentDescription: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(8.dp)
            .background(color, CircleShape)
            .semantics { this.contentDescription = contentDescription },
    )
}

/** Compact section header — keeps lists scannable without oversized headings. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(
            horizontal = ZcodeThemeValues.spacing.lg,
            vertical = ZcodeThemeValues.spacing.xs,
        ),
    )
}

/**
 * Empty state: one line of what to do next, optionally the primary
 * action. Never a decorative illustration dump.
 */
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(ZcodeThemeValues.spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZcodeThemeValues.spacing.sm),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (actionLabel != null && onAction != null) {
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/**
 * Recoverable error state: explicit about what failed and offers the
 * retry path (Phase 2 acceptance: failed requests produce explicit,
 * recoverable errors).
 */
@Composable
fun ErrorState(
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
    retryLabel: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(ZcodeThemeValues.spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZcodeThemeValues.spacing.sm),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (retryLabel != null && onRetry != null) {
            OutlinedButton(onClick = onRetry) { Text(retryLabel) }
        }
    }
}

/** Determinate inline progress with a label (provisioning, long actions). */
@Composable
fun LoadingIndicator(label: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(ZcodeThemeValues.spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(ZcodeThemeValues.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Icon + label row for compact metadata (host identity, paths). */
@Composable
fun MetadataRow(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = ZcodeThemeValues.spacing.lg,
                vertical = ZcodeThemeValues.spacing.xs,
            ),
        horizontalArrangement = Arrangement.spacedBy(ZcodeThemeValues.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Status badge (owner UI brief: Idle gray / Running / Completed). A quiet
 * chip — status dot plus a small label on a surfaceVariant surface. The
 * caller supplies the semantic color from the theme's status tokens; the
 * badge never invents a state the runtime did not report.
 */
@Composable
fun StatusBadge(
    statusColor: Color,
    label: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = ZcodeThemeValues.spacing.sm,
                vertical = ZcodeThemeValues.spacing.xs,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZcodeThemeValues.spacing.xs),
        ) {
            StatusDot(color = statusColor, contentDescription = label)
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}
