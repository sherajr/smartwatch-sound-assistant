package com.peaceantz.stagescope.ui.analyzer

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import com.peaceantz.stagescope.data.SpectrumSnapshot
import com.peaceantz.stagescope.dsp.DbScale
import com.peaceantz.stagescope.ui.theme.LocalStageScopePalette
import com.peaceantz.stagescope.ui.theme.chartAnnotationStyle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val GRID_DB_LINES = listOf(0.0, -20.0, -40.0, -60.0, -80.0)
private val TICK_FREQUENCIES_HZ = listOf(100.0, 1_000.0, 10_000.0)

/**
 * The radial spectrum annulus: angle = logarithmic frequency (one consistent clockwise direction,
 * DC excluded, capped at the negotiated Nyquist), radius = amplitude on a fixed -90..0 dBFS scale
 * (never autoscaled). Bands come pre-aggregated from [RadialMapping.aggregateBands] via
 * [AnalyzerViewModel] -- this composable only draws them, so the same band data that fed the
 * on-screen frequency/dB readout is exactly what's rendered here (no separate recomputation that
 * could disagree). Live trace = theme "Live" accent, peak hold = a muted outline, a compared
 * snapshot = a dashed "Held" outline, and the selection cursor = a bright spoke -- four visually
 * distinguishable elements, none of which rely on color alone (peak hold and comparison also
 * differ in dash/line style from the live trace).
 */
@Composable
fun RadialSpectrumCanvas(
    spectrum: SpectrumDisplay,
    compareSnapshot: SpectrumSnapshot?,
    isComparisonCompatible: Boolean,
    modifier: Modifier = Modifier,
) {
    val palette = LocalStageScopePalette.current
    val textMeasurer = rememberTextMeasurer()
    val tickStyle = chartAnnotationStyle()
    val bandCount = spectrum.bands.size

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val outerBound = (minOf(size.width, size.height) / 2f) * 0.96f
        val outerRadius = outerBound * 0.86f
        // Leaves a generously sized central "safe zone" for the primary reading + unit + cursor
        // readout text, which together need more room than a thin annulus would otherwise leave.
        val innerRadius = outerBound * 0.62f
        val tickRadius = outerBound * 0.985f

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

        // Sparse frequency tick labels around the outer edge.
        for (hz in TICK_FREQUENCIES_HZ) {
            if (hz >= spectrum.nyquistHz) continue
            val minBin = 1
            val maxBin = (spectrum.magnitudesDbfs.size - 1).coerceAtLeast(minBin + 1)
            val bin = (hz / spectrum.binWidthHz).toInt().coerceIn(minBin, maxBin)
            val band = RadialMapping.bandForBin(bin, minBin, maxBin, bandCount)
            val angle = RadialMapping.angleForFraction(RadialMapping.fractionForBand(band, bandCount))
            val tickStart = pointAt(angle, outerRadius)
            val tickEnd = pointAt(angle, tickRadius)
            drawLine(palette.Grid, tickStart, tickEnd, strokeWidth = 1.5f)
            val label = if (hz >= 1000.0) "${(hz / 1000.0).toInt()}k" else "${hz.toInt()}"
            val measured = textMeasurer.measure(label, tickStyle)
            val labelPoint = pointAt(angle, tickRadius + 6f)
            drawText(
                textMeasurer,
                label,
                topLeft = Offset(labelPoint.x - measured.size.width / 2f, labelPoint.y - measured.size.height / 2f),
                style = tickStyle,
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
        val cursorOuter = pointAt(cursorAngle, outerRadius * 1.04f)
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
