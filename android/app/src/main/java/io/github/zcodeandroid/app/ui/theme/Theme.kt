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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * P1 theme (REVISED architecture §5 Phase 1). Full Material 3 color
 * schemes built from the ZCode tokens; plus a small semantic-status
 * extension (running/completed/failed/approval) and a spacing scale —
 * the only sanctioned spacing values, so screens stay consistent by
 * construction rather than by discipline.
 */

@Immutable
data class ZcodeStatusColors(
    val running: androidx.compose.ui.graphics.Color,
    val completed: androidx.compose.ui.graphics.Color,
    val failed: androidx.compose.ui.graphics.Color,
    val approvalRequired: androidx.compose.ui.graphics.Color,
)

@Immutable
data class ZcodeSpacing(
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 24.dp,
)

val LocalZcodeStatusColors = staticCompositionLocalOf {
    ZcodeStatusColors(Amber60, StatusOkLight, StatusFailLight, StatusPendingLight)
}
val LocalZcodeSpacing = staticCompositionLocalOf { ZcodeSpacing() }

private val DarkColorScheme = darkColorScheme(
    primary = Amber80,
    onPrimary = Color(0xFF2B1E02),
    primaryContainer = AmberContainerDark,
    onPrimaryContainer = OnAmberContainerDark,
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
    primary = Amber60,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = AmberContainerLight,
    onPrimaryContainer = OnAmberContainerLight,
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
    CompositionLocalProvider(
        LocalZcodeStatusColors provides statusColors,
        LocalZcodeSpacing provides ZcodeSpacing(),
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
}

/** App theme mode (§7 owner: Android UI / persistent settings). */
const val THEME_SYSTEM = 0
const val THEME_LIGHT = 1
const val THEME_DARK = 2
