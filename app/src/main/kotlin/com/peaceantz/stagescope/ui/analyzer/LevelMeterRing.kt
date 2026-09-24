package com.peaceantz.stagescope.ui.analyzer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import com.peaceantz.stagescope.ui.theme.LocalStageScopePalette

/**
 * The circumference sound-level meter: a thin, nearly-complete circular track sharing the
 * spectrum's own bottom gap ([RadialMapping.GAP_DEGREES], where the action-row buttons sit) so the
 * two rings read as one consistent instrument, with an illuminated arc that grows clockwise from
 * the gap's start as [levelFraction] (already computed by [LevelMeterScale] on a fixed,
 * documented scale) rises.
 *
 * This measures the same RMS quantity as the center reading -- it is not a playback-volume control.
 * Smoothed only here in the render layer via [animateFloatAsState] (a modest, cosmetic ease, never
 * touching the underlying measurement). Keeps rendering whatever [levelFraction] the caller passes
 * even while the spectrum annulus is frozen, since Freeze is spectrum-only (see AnalyzerViewModel).
 * [isClipping] swaps the illuminated color to the same semantic "fault" accent the center CLIP
 * badge already uses -- a supplementary cue, not the sole one (the text badge remains the
 * accessible, non-color signal) -- and is never a graduated hearing-safety gradient.
 */
@Composable
fun LevelMeterRing(
    levelFraction: Float,
    isClipping: Boolean,
    modifier: Modifier = Modifier,
) {
    val palette = LocalStageScopePalette.current
    val animatedFraction by animateFloatAsState(
        targetValue = levelFraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 180),
        label = "levelMeterFraction",
    )

    Canvas(modifier = modifier) {
        val usableRadius = minOf(size.width, size.height) / 2f
        val radii = InstrumentGeometry.compute(usableRadius)
        val center = Offset(size.width / 2f, size.height / 2f)
        val trackRadius = (radii.meterOuter + radii.meterInner) / 2f
        val trackWidth = radii.meterOuter - radii.meterInner
        val topLeft = Offset(center.x - trackRadius, center.y - trackRadius)
        val arcSize = Size(trackRadius * 2, trackRadius * 2)

        drawArc(
            color = palette.Grid,
            startAngle = RadialMapping.START_ANGLE_DEGREES,
            sweepAngle = RadialMapping.SWEEP_DEGREES,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = trackWidth, cap = StrokeCap.Butt),
        )

        if (animatedFraction > 0f) {
            drawArc(
                color = if (isClipping) palette.Held else palette.Live,
                startAngle = RadialMapping.START_ANGLE_DEGREES,
                sweepAngle = RadialMapping.SWEEP_DEGREES * animatedFraction,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = trackWidth, cap = StrokeCap.Round),
            )
        }
    }
}
