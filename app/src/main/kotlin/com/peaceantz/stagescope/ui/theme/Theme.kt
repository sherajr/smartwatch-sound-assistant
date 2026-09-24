package com.peaceantz.stagescope.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme

/**
 * Instrument palette -- the single source of truth for every color in the app. Every value below
 * is a hardcoded literal (never derived from wallpaper/dynamic color, never system-themed), so
 * there is no path by which a platform dynamic-color feature could reintroduce the old amber.
 *
 * Semantics, not decoration: green means live/active, red means held/pinned/captured *or*
 * clipping/faults (the two are told apart by an icon+text label, e.g. "◆ HELD" vs
 * "⚠ CLIP", never by color alone -- see docs/MEASUREMENTS.md).
 */
object StageScopeColors {
    val Background = Color(0xFF000000)
    val Surface = Color(0xFF0B100E)
    val PrimaryText = Color(0xFFDCE6DF)
    val SecondaryText = Color(0xFF99AAA1)
    val Live = Color(0xFF68CB94)
    val Held = Color(0xFFE47B7B)
    val Grid = Color(0xFF24342B)

    /** Dim appearance: same hues, lower luminance, still meets contrast on black. */
    val PrimaryTextDim = Color(0xFFA8B0AB)
    val SecondaryTextDim = Color(0xFF6C7772)
    val LiveDim = Color(0xFF3E8F63)
    val HeldDim = Color(0xFFA35555)
    val GridDim = Color(0xFF161E19)
}

private fun schemeFor(dim: Boolean): ColorScheme {
    val primaryText = if (dim) StageScopeColors.PrimaryTextDim else StageScopeColors.PrimaryText
    val secondaryText = if (dim) StageScopeColors.SecondaryTextDim else StageScopeColors.SecondaryText
    val live = if (dim) StageScopeColors.LiveDim else StageScopeColors.Live
    val held = if (dim) StageScopeColors.HeldDim else StageScopeColors.Held
    val grid = if (dim) StageScopeColors.GridDim else StageScopeColors.Grid

    return ColorScheme(
        primary = live,
        onPrimary = Color.Black,
        secondary = secondaryText,
        onSecondary = Color.Black,
        error = held,
        onError = Color.Black,
        background = StageScopeColors.Background,
        onBackground = primaryText,
        surfaceContainer = StageScopeColors.Surface,
        surfaceContainerLow = StageScopeColors.Surface,
        surfaceContainerHigh = StageScopeColors.Surface,
        onSurface = primaryText,
        onSurfaceVariant = secondaryText,
        outline = grid,
        outlineVariant = grid,
    )
}

@Composable
fun StageScopeTheme(dimAppearance: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = schemeFor(dimAppearance),
        content = content,
    )
}
