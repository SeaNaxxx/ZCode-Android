package io.github.zcodeandroid.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * P1 design tokens (REVISED architecture §5 Phase 1; §4 design directive).
 *
 * ZCode identity: a local coding-agent workbench. The palette is built on
 * the z.ai amber accent over warm stone neutrals — deliberate, restrained,
 * no gradients/glow/glassmorphism. Every color has a functional job:
 * accent = primary actions and active state; teal = agent tool activity
 * (distinguishable from assistant prose per Phase 3); status colors are
 * semantic and defined once, consumed everywhere.
 */

// ---------------------------------------------------------------- amber (accent)
val Amber60 = Color(0xFFA16207)      // light theme primary (4.5:1 on white)
val Amber80 = Color(0xFFFBBF24)      // dark theme primary
val AmberContainerLight = Color(0xFFFEF3C7)
val OnAmberContainerLight = Color(0xFF713F12)
val AmberContainerDark = Color(0xFF3B2E06)
val OnAmberContainerDark = Color(0xFFFDE68A)

// ---------------------------------------------------------------- teal (tool activity)
val Teal40 = Color(0xFF0F766E)
val Teal70 = Color(0xFF2DD4BF)
val TealContainerLight = Color(0xFFCCFBF1)
val OnTealContainerLight = Color(0xFF134E4A)
val TealContainerDark = Color(0xFF0B3B37)
val OnTealContainerDark = Color(0xFF99F6E4)

// ---------------------------------------------------------------- warm neutrals (light)
val PaperLight = Color(0xFFFAF9F7)       // background
val SurfaceLight = Color(0xFFFFFFFF)
val SurfaceVariantLight = Color(0xFFF0EDE8)
val OutlineLight = Color(0xFFD6D0C4)
val OutlineVariantLight = Color(0xFFE7E2D9)
val OnLight = Color(0xFF1C1917)          // stone-900
val OnVariantLight = Color(0xFF57534E)   // stone-600

// ---------------------------------------------------------------- warm neutrals (dark)
val PaperDark = Color(0xFF141210)
val SurfaceDark = Color(0xFF1C1917)      // stone-900
val SurfaceVariantDark = Color(0xFF292524) // stone-800
val OutlineDark = Color(0xFF57534E)
val OutlineVariantDark = Color(0xFF3B3735)
val OnDark = Color(0xFFE7E5E4)           // stone-200
val OnVariantDark = Color(0xFFA8A29E)    // stone-400

// ---------------------------------------------------------------- semantic status
val StatusRunLight = Color(0xFFB45309)       // running (amber-700, work in progress)
val StatusRunDark = Color(0xFFFCD34D)
val StatusOkLight = Color(0xFF15803D)        // completed (green-700)
val StatusOkDark = Color(0xFF4ADE80)
val StatusFailLight = Color(0xFFB3261E)      // failed
val StatusFailDark = Color(0xFFF2B8B5)
val StatusPendingLight = Color(0xFF9A3412)   // approval required (orange-800)
val StatusPendingDark = Color(0xFFFDBA74)
