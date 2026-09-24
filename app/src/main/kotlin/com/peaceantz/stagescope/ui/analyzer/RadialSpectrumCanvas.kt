package com.peaceantz.stagescope.ui.analyzer

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import com.peaceantz.stagescope.data.SpectrumSnapshot
import com.peaceantz.stagescope.dsp.DbScale
import com.peaceantz.stagescope.ui.theme.LocalStageScopePalette
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val GRID_DB_LINES = listOf(0.0, -20.0, -40.0, -60.0, -80.0)

/**
 * The radial spectrum annulus: angle = logarithmic frequency (one consistent clockwise direction,
 * DC excluded, capped at the negotiated Nyquist), radius = amplitude on a fixed -90..0 dBFS scale
 * (never autoscaled). Occupies the annulus between [InstrumentGeometry.Radii.spectrumInner] and
 * `.spectrumOuter` -- just inside the separate circumference [LevelMeterRing], with the small
 * documented gap between them -- rather than a small central circle; see [InstrumentGeometry] for
 * how those boundaries are derived from the same full-bleed box this canvas fills.
 *
 * Bands come pre-aggregated from [RadialMapping.aggregateBands] via [AnalyzerViewModel] -- this
 * composable only draws them, so the same band data that fed the on-screen frequency/dB readout is
 * exactly what's rendered here (no separate recomputation that could disagree). Live trace = theme
 * "Live" accent, peak hold = a muted outline, a compared snapshot = a dashed "Held" outline, and the
 * selection cursor = a bright spoke -- four visually distinguishable elements, none of which rely on
 * color alone (peak hold and comparison also differ in dash/line style from the live trace).
 *
 * Deliberately restrained: no frequency tick labels/lines around the outer edge (that information
 * is available via the center cursor readout on tap) and no glow or secondary outlines -- just the
 * sparse dB grid, the live trace, peak hold, and the cursor.
 */
@Composable
fun RadialSpectrumCanvas(
    spectrum: SpectrumDisplay,
    compareSnapshot: SpectrumSnapshot?,
    isComparisonCompatible: Boolean,
    modifier: Modifier = Modifier,
) {
    val palette = LocalStageScopePalette.current
    val bandCount = spectrum.bands.size

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val usableRadius = minOf(size.width, size.height) / 2f
        val radii = InstrumentGeometry.compute(usableRadius)
        val outerRadius = radii.spectrumOuter
        val innerRadius = radii.spectrumInner

        fun pointAt(angleDeg: Float, radius: Float): Offset {
            val rad = angleDeg * (PI.toFloat() / 180f)
            return Offset(center.x + cos(rad) * radius, center.y + sin(rad) * radius)
        }

        fun radiusForDb(db: Double): Float =
            innerRadius + (outerRadius - innerRadius) * RadialMapping.radialFractionForDb(db)

        // Sparse dB grid rings, drawn only across the usable arc (never the excluded gap).
        for (db in GRID_DB_LINES) {
            val r = radiusForDb(db)
            drawArc(
                color = palette.Grid,
                startAngle = RadialMapping.START_ANGLE_DEGREES,
                sweepAngle = RadialMapping.SWEEP_DEGREES,
                useCenter = false,
                topLeft = Offset(center.x - r, center.y - r),
                size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
                style = Stroke(width = 1f),
            )
        }

        val angularStepDeg = RadialMapping.SWEEP_DEGREES / bandCount

        fun polylinePath(dbValues: (Int) -> Double): Path {
            val path = Path()
            for (i in 0 until bandCount) {
                val angle = RadialMapping.angleForFraction(RadialMapping.fractionForBand(i, bandCount))
                val p = pointAt(angle, radiusForDb(dbValues(i)))
                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            return path
        }

        // Comparison snapshot: dashed "Held" outline, aggregated the same way as the live bands.
        if (compareSnapshot != null && isComparisonCompatible) {
            val compareBands = RadialMapping.aggregateBands(compareSnapshot.magnitudesDbfs.toDoubleArray(), bandCount)
            drawPath(
                path = polylinePath { i -> compareBands[i].magnitudeDbfs },
                color = palette.Held,
                style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))),
            )
        }

        // Peak hold: thin muted outline above the live bars.
        drawPath(
            path = polylinePath { i -> spectrum.peakHoldBandsDbfs.getOrElse(i) { DbScale.FLOOR_DBFS } },
            color = palette.SecondaryText.copy(alpha = 0.55f),
            style = Stroke(width = 1.2f),
        )

        // Live spectrum: one radial bar per band, angularly narrower than its slot (visible gaps).
        for (i in 0 until bandCount) {
            val angle = RadialMapping.angleForFraction(RadialMapping.fractionForBand(i, bandCount))
            val db = spectrum.bands[i].magnitudeDbfs
            val from = pointAt(angle, innerRadius)
            val to = pointAt(angle, radiusForDb(db))
            val isCursor = i == spectrum.cursorBandIndex
            drawLine(
                color = if (isCursor) palette.PrimaryText else palette.Live,
                start = from,
                end = to,
                strokeWidth = arcChordWidth(angularStepDeg = angularStepDeg * 0.62f, radius = outerRadius),
                cap = StrokeCap.Round,
            )
        }

        // Selection cursor: a bright spoke over the live bars' own span, with a tip marker just
        // beyond the ring -- deliberately never reaching inward past innerRadius, so it can't cross
        // into the central text "safe zone" (the primary reading/unit/cursor-readout text).
        val cursorAngle = RadialMapping.angleForFraction(RadialMapping.fractionForBand(spectrum.cursorBandIndex, bandCount))
        val cursorInner = pointAt(cursorAngle, innerRadius)
        val cursorOuter = pointAt(cursorAngle, outerRadius * 1.02f)
        drawLine(palette.PrimaryText, cursorInner, cursorOuter, strokeWidth = 1.5f)
        drawCircle(palette.PrimaryText, radius = 3.5f, center = cursorOuter)
    }
}

/** Approximate chord width in px for a given angular width (deg) at [radius] -- keeps radial bars
 *  visually proportioned across the arc without needing true arc-segment geometry per band. */
private fun arcChordWidth(angularStepDeg: Float, radius: Float): Float {
    val rad = angularStepDeg * (PI.toFloat() / 180f)
    return (2f * radius * sin(rad / 2f)).coerceAtLeast(1.5f)
}
