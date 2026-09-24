package com.peaceantz.stagescope.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import com.peaceantz.stagescope.data.AppTheme

/**
 * One instrument palette. Field names deliberately match the legacy `StageScopeColors` object so
 * every call site that already read `StageScopeColors.Live` etc. only needs its receiver swapped
 * for `LocalStageScopePalette.current` -- see that object below for why it still exists.
 *
 * Semantics, not decoration: [Live] means live/active, [Held] means held/pinned/captured *or*
 * clipping/faults (the two are told apart by an icon+text label, e.g. "◆ HELD" vs "⚠ CLIP", never
 * by color alone -- see docs/MEASUREMENTS.md). Every palette keeps that same live/held role split
 * so changing themes never breaks that distinction, only its hue.
 */
data class StageScopePalette(
    val Background: Color,
    val Surface: Color,
    val PrimaryText: Color,
    val SecondaryText: Color,
    val Live: Color,
    val Held: Color,
    val Grid: Color,
    val PrimaryTextDim: Color,
    val SecondaryTextDim: Color,
    val LiveDim: Color,
    val HeldDim: Color,
    val GridDim: Color,
)

object StageScopePalettes {
    /** The original palette -- green live accent, contrasting red held/reference accent. */
    val PhosphorGreen = StageScopePalette(
        Background = Color(0xFF000000),
        Surface = Color(0xFF0B100E),
        PrimaryText = Color(0xFFDCE6DF),
        SecondaryText = Color(0xFF99AAA1),
        Live = Color(0xFF68CB94),
        Held = Color(0xFFE47B7B),
        Grid = Color(0xFF24342B),
        PrimaryTextDim = Color(0xFFA8B0AB),
        SecondaryTextDim = Color(0xFF6C7772),
        LiveDim = Color(0xFF3E8F63),
        HeldDim = Color(0xFFA35555),
        GridDim = Color(0xFF161E19),
    )

    /** Cyan live accent, warm amber held/reference accent for contrast. */
    val IceCyan = StageScopePalette(
        Background = Color(0xFF000000),
        Surface = Color(0xFF0A1013),
        PrimaryText = Color(0xFFDCEAEE),
        SecondaryText = Color(0xFF8FA8AF),
        Live = Color(0xFF5FD3E8),
        Held = Color(0xFFE8AA5F),
        Grid = Color(0xFF1E323A),
        PrimaryTextDim = Color(0xFFA5B6BA),
        SecondaryTextDim = Color(0xFF63757B),
        LiveDim = Color(0xFF368B9C),
        HeldDim = Color(0xFFA47638),
        GridDim = Color(0xFF13222A),
    )

    /** Amber live accent, cool cyan-blue held/reference accent for contrast. */
    val WarmAmber = StageScopePalette(
        Background = Color(0xFF000000),
        Surface = Color(0xFF120E09),
        PrimaryText = Color(0xFFEDE3D3),
        SecondaryText = Color(0xFFAB9C85),
        Live = Color(0xFFE8A94F),
        Held = Color(0xFF5FA7E8),
        Grid = Color(0xFF3A2E1E),
        PrimaryTextDim = Color(0xFFB8AC97),
        SecondaryTextDim = Color(0xFF7B715F),
        LiveDim = Color(0xFF9C7434),
        HeldDim = Color(0xFF3E729C),
        GridDim = Color(0xFF261D12),
    )

    /** Violet live accent, gold held/reference accent for contrast. */
    val Violet = StageScopePalette(
        Background = Color(0xFF000000),
        Surface = Color(0xFF100D14),
        PrimaryText = Color(0xFFE4DEEC),
        SecondaryText = Color(0xFFA298AB),
        Live = Color(0xFFA57FE8),
        Held = Color(0xFFE8C25F),
        Grid = Color(0xFF2C2438),
        PrimaryTextDim = Color(0xFFAFA8B8),
        SecondaryTextDim = Color(0xFF6E667B),
        LiveDim = Color(0xFF6C4F9C),
        HeldDim = Color(0xFF9C822E),
        GridDim = Color(0xFF1C1726),
    )

    /** Red live accent (this theme's own "active" hue), cool cyan-white held/reference accent. */
    val NightRed = StageScopePalette(
        Background = Color(0xFF000000),
        Surface = Color(0xFF140A0A),
        PrimaryText = Color(0xFFEDDEDE),
        SecondaryText = Color(0xFFAB9494),
        Live = Color(0xFFE86B5F),
        Held = Color(0xFF6FD6DA),
        Grid = Color(0xFF3A2020),
        PrimaryTextDim = Color(0xFFB89E9E),
        SecondaryTextDim = Color(0xFF7B6363),
        LiveDim = Color(0xFF9C4A3E),
        HeldDim = Color(0xFF3E8F92),
        GridDim = Color(0xFF261414),
    )

    fun forTheme(theme: AppTheme): StageScopePalette = when (theme) {
        AppTheme.PHOSPHOR_GREEN -> PhosphorGreen
        AppTheme.ICE_CYAN -> IceCyan
        AppTheme.WARM_AMBER -> WarmAmber
        AppTheme.VIOLET -> Violet
        AppTheme.NIGHT_RED -> NightRed
    }

    val ALL: List<Pair<AppTheme, String>> = listOf(
        AppTheme.PHOSPHOR_GREEN to "Phosphor Green",
        AppTheme.ICE_CYAN to "Ice Cyan",
        AppTheme.WARM_AMBER to "Warm Amber",
        AppTheme.VIOLET to "Violet",
        AppTheme.NIGHT_RED to "Night Red",
    )
}

/**
 * Non-composable access to the default palette -- kept for the handful of contexts that build a
 * color scheme outside Compose (the Tile builds its own [androidx.wear.protolayout.material3.
 * ColorScheme] from a [StageScopePalette] it reads out of settings directly, not this constant;
 * this alias just preserves the previous name for anything not yet migrated to a live theme).
 */
val StageScopeColors: StageScopePalette = StageScopePalettes.PhosphorGreen

val LocalStageScopePalette = staticCompositionLocalOf { StageScopePalettes.PhosphorGreen }

fun schemeFor(palette: StageScopePalette, dim: Boolean): ColorScheme {
    val primaryText = if (dim) palette.PrimaryTextDim else palette.PrimaryText
    val secondaryText = if (dim) palette.SecondaryTextDim else palette.SecondaryText
    val live = if (dim) palette.LiveDim else palette.Live
    val held = if (dim) palette.HeldDim else palette.Held
    val grid = if (dim) palette.GridDim else palette.Grid

    return ColorScheme(
        primary = live,
        onPrimary = Color.Black,
        secondary = secondaryText,
        onSecondary = Color.Black,
        error = held,
        onError = Color.Black,
        background = palette.Background,
        onBackground = primaryText,
        surfaceContainer = palette.Surface,
        surfaceContainerLow = palette.Surface,
        surfaceContainerHigh = palette.Surface,
        onSurface = primaryText,
        onSurfaceVariant = secondaryText,
        outline = grid,
        outlineVariant = grid,
    )
}

@Composable
fun StageScopeTheme(
    theme: AppTheme = AppTheme.PHOSPHOR_GREEN,
    dimAppearance: Boolean = false,
    content: @Composable () -> Unit,
) {
    val palette = StageScopePalettes.forTheme(theme)
    CompositionLocalProvider(LocalStageScopePalette provides palette) {
        MaterialTheme(
            colorScheme = schemeFor(palette, dimAppearance),
            content = content,
        )
    }
}
