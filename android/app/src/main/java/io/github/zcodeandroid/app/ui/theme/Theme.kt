package io.github.zcodeandroid.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Theme (REVISED architecture §5 Phase 1 + Phase 2 UI brief). Full Material 3
 * color schemes built from the ZCode tokens; plus semantic-status extension
 * (running/completed/failed/approval), a terminal-surface extension (tool
 * cards read as product content in both themes), the spacing scale and the
 * corner-radii scale — the only sanctioned values, so screens stay consistent
 * by construction rather than by discipline. Spacing/radii tokens live in
 * Dimens.kt (same package).
 */

@Immutable
data class ZcodeStatusColors(
    val running: androidx.compose.ui.graphics.Color,
    val completed: androidx.compose.ui.graphics.Color,
    val failed: androidx.compose.ui.graphics.Color,
    val approvalRequired: androidx.compose.ui.graphics.Color,
)

/** Terminal-style surface for tool cards — dark in both themes (Color.kt). */
@Immutable
data class ZcodeTerminalColors(
    val background: androidx.compose.ui.graphics.Color,
    val onBackground: androidx.compose.ui.graphics.Color,
    val accent: androidx.compose.ui.graphics.Color,
)

val LocalZcodeStatusColors = staticCompositionLocalOf {
    ZcodeStatusColors(Amber60, StatusOkLight, StatusFailLight, StatusPendingLight)
}
val LocalZcodeSpacing = staticCompositionLocalOf { ZcodeSpacing() }
val LocalZcodeRadii = staticCompositionLocalOf { ZcodeRadii() }
val LocalZcodeTerminal = staticCompositionLocalOf {
    ZcodeTerminalColors(TerminalLight, TerminalOnLight, TerminalAccentLight)
}

private val DarkColorScheme = darkColorScheme(
    primary = Blue80,
    onPrimary = Color(0xFF0A2547),
    primaryContainer = BlueContainerDark,
    onPrimaryContainer = OnBlueContainerDark,
    secondary = OnVariantDark,
    onSecondary = PaperDark,
    secondaryContainer = SurfaceVariantDark,
    onSecondaryContainer = OnDark,
    tertiary = Teal70,
    onTertiary = Color(0xFF05332F),
    tertiaryContainer = TealContainerDark,
    onTertiaryContainer = OnTealContainerDark,
    background = PaperDark,
    onBackground = OnDark,
    surface = SurfaceDark,
    onSurface = OnDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnVariantDark,
    outline = OutlineDark,
    outlineVariant = OutlineVariantDark,
    error = StatusFailDark,
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF410E0B),
    onErrorContainer = Color(0xFFF9DEDC),
)

private val LightColorScheme = lightColorScheme(
    primary = Blue60,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = BlueContainerLight,
    onPrimaryContainer = OnBlueContainerLight,
    secondary = OnVariantLight,
    onSecondary = PaperLight,
    secondaryContainer = SurfaceVariantLight,
    onSecondaryContainer = OnLight,
    tertiary = Teal40,
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = TealContainerLight,
    onTertiaryContainer = OnTealContainerLight,
    background = PaperLight,
    onBackground = OnLight,
    surface = SurfaceLight,
    onSurface = OnLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnVariantLight,
    outline = OutlineLight,
    outlineVariant = OutlineVariantLight,
    error = StatusFailLight,
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

@Composable
fun ZcodeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val statusColors = if (darkTheme) {
        ZcodeStatusColors(StatusRunDark, StatusOkDark, StatusFailDark, StatusPendingDark)
    } else {
        ZcodeStatusColors(StatusRunLight, StatusOkLight, StatusFailLight, StatusPendingLight)
    }
    val terminal = if (darkTheme) {
        ZcodeTerminalColors(TerminalDark, TerminalOnDark, TerminalAccentDark)
    } else {
        ZcodeTerminalColors(TerminalLight, TerminalOnLight, TerminalAccentLight)
    }
    CompositionLocalProvider(
        LocalZcodeStatusColors provides statusColors,
        LocalZcodeSpacing provides ZcodeSpacing(),
        LocalZcodeRadii provides ZcodeRadii(),
        LocalZcodeTerminal provides terminal,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = ZcodeTypography,
            shapes = ZcodeShapes,
            content = content,
        )
    }
}

/** Convenience accessors — keep screens free of CompositionLocal plumbing. */
object ZcodeThemeValues {
    val statusColors: ZcodeStatusColors
        @Composable get() = LocalZcodeStatusColors.current
    val spacing: ZcodeSpacing
        @Composable get() = LocalZcodeSpacing.current
    val radii: ZcodeRadii
        @Composable get() = LocalZcodeRadii.current
    val terminal: ZcodeTerminalColors
        @Composable get() = LocalZcodeTerminal.current
}

/** App theme mode (§7 owner: Android UI / persistent settings). */
const val THEME_SYSTEM = 0
const val THEME_LIGHT = 1
const val THEME_DARK = 2
