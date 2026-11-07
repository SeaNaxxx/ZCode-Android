package io.github.zcodeandroid.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Dimension tokens (REVISED Phase 2 UI brief). THE sanctioned scale — screens
 * consume these via [ZcodeThemeValues]; literal dp values outside this file
 * are limited to hairline widths (1dp borders) and icon sizes.
 *
 * Spacing: multiples of 4dp only (deliberate whitespace per the Paseo
 * direction — generous, never cramped).
 * Radii: 8/12/16dp for surfaces; 24dp is reserved for the chat input bar
 * (the one soft-pill surface in the app).
 */
@Immutable
data class ZcodeSpacing(
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 24.dp,
)

@Immutable
data class ZcodeRadii(
    /** Chips, small inline surfaces. */
    val sm: Dp = 8.dp,
    /** Cards, tool terminal, permission card. */
    val md: Dp = 12.dp,
    /** Workspace cards, sheets. */
    val lg: Dp = 16.dp,
    /** Chat input bar only (owner brief: rounded 24dp). */
    val input: Dp = 24.dp,
)
