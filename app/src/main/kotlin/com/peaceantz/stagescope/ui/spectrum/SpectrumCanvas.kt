package com.peaceantz.stagescope.ui.spectrum

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import com.peaceantz.stagescope.data.SpectrumSnapshot
import com.peaceantz.stagescope.dsp.DbScale
import com.peaceantz.stagescope.dsp.FrequencyBands
import com.peaceantz.stagescope.ui.theme.StageScopeColors
import com.peaceantz.stagescope.ui.theme.chartAnnotationStyle
import kotlin.math.ln

private const val DB_TOP = 0.0
private const val DB_BOTTOM = DbScale.FLOOR_DBFS
private val GRID_DB_LINES = listOf(0.0, -20.0, -40.0, -60.0, -80.0)
private val TICK_FREQUENCIES_HZ = listOf(100.0, 1_000.0, 10_000.0)

private fun xFractionForBin(bin: Int, maxBin: Int): Float =
    (ln(bin.toDouble()) / ln(maxBin.toDouble())).toFloat().coerceIn(0f, 1f)

private fun yFractionForDb(db: Double): Float =
    (1f - ((db - DB_BOTTOM) / (DB_TOP - DB_BOTTOM)).toFloat()).coerceIn(0f, 1f)

/**
 * Fixed dBFS/log-frequency axes (no autoscaling -- avoids the "rapid autoscale pumping" the spec
 * warns about). Green solid = live. Red dashed = a held/compared reference, with its own trace so
 * the two are never confused.
 */
@Composable
fun SpectrumCanvas(
    frame: DisplayFrame,
    compareSnapshot: SpectrumSnapshot?,
    isComparisonCompatible: Boolean,
    modifier: Modifier = Modifier,
) {
    val maxBin = frame.magnitudesDbfs.size - 1
    val textMeasurer = rememberTextMeasurer()
    val tickStyle = chartAnnotationStyle()

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val plotBottom = h - 12f // leave room for frequency tick labels

        for (band in FrequencyBands.ALL) {
            val lowBin = (band.lowHz / frame.binWidthHz).toInt().coerceIn(1, maxBin)
            val highBin = (band.highHz / frame.binWidthHz).toInt().coerceIn(1, maxBin)
            val x0 = xFractionForBin(lowBin, maxBin) * w
            val x1 = xFractionForBin(highBin, maxBin) * w
            drawRect(
                color = StageScopeColors.Grid.copy(alpha = 0.5f),
                topLeft = Offset(x0, 0f),
                size = Size(x1 - x0, plotBottom),
            )
        }

        for (db in GRID_DB_LINES) {
            val y = yFractionForDb(db) * plotBottom
            drawLine(StageScopeColors.Grid, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }

        fun xForFreq(hz: Double): Float {
            val bin = (hz / frame.binWidthHz).coerceIn(1.0, maxBin.toDouble())
            return xFractionForBin(bin.toInt(), maxBin) * w
        }
        for (hz in TICK_FREQUENCIES_HZ) {
            if (hz >= frame.nyquistHz) continue
            val x = xForFreq(hz)
            drawLine(StageScopeColors.Grid, Offset(x, 0f), Offset(x, plotBottom), strokeWidth = 1f)
            val label = if (hz >= 1000.0) "${(hz / 1000.0).toInt()}k" else "${hz.toInt()}"
            val measured = textMeasurer.measure(label, tickStyle)
            drawText(
                textMeasurer,
                label,
                topLeft = Offset((x - measured.size.width / 2f).coerceIn(0f, w - measured.size.width), plotBottom + 1f),
                style = tickStyle,
            )
        }

        fun pathFor(mags: DoubleArray): Path {
            val path = Path()
            for (bin in 1..maxBin) {
                val x = xFractionForBin(bin, maxBin) * w
                val y = yFractionForDb(mags[bin]) * plotBottom
                if (bin == 1) path.moveTo(x, y) else path.lineTo(x, y)
            }
            return path
        }

        if (compareSnapshot != null && isComparisonCompatible) {
            drawPath(
                path = pathFor(compareSnapshot.magnitudesDbfs.toDoubleArray()),
                color = StageScopeColors.Held,
                style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
            )
        }

        drawPath(
            path = pathFor(frame.peakHoldDbfs),
            color = StageScopeColors.SecondaryText.copy(alpha = 0.6f),
            style = Stroke(width = 1.2f),
        )

        drawPath(
            path = pathFor(frame.magnitudesDbfs),
            color = StageScopeColors.Live,
            style = Stroke(width = 2.5f),
        )

        val cursorX = xFractionForBin(frame.cursorBinIndex, maxBin) * w
        drawLine(
            color = StageScopeColors.PrimaryText,
            start = Offset(cursorX, 0f),
            end = Offset(cursorX, plotBottom),
            strokeWidth = 1.5f,
        )
    }
}
