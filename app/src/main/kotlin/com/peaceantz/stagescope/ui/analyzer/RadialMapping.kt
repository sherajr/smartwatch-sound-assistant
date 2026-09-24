package com.peaceantz.stagescope.ui.analyzer

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Pure geometry for the circular combined analyzer -- no Android/Compose types, so this is
 * directly unit-testable from the JVM test source set. [RadialSpectrumCanvas] and
 * [AnalyzerViewModel]'s cursor/tap handling both go through these same functions so a screen
 * position, a display band, and a readout can never disagree about what they refer to.
 *
 * Geometry: a partial circle with a deliberate gap at the bottom (where the two lower-row action
 * controls sit, per the round-safe-layout rule elsewhere in this app). Angle convention matches
 * [androidx.compose.ui.graphics.drawscope.DrawScope.drawArc]: 0deg = 3 o'clock, increasing
 * clockwise. The arc starts just past the gap's bottom-left edge and sweeps clockwise the long way
 * around (through 9 o'clock, 12 o'clock, 3 o'clock) to the gap's bottom-right edge.
 */
object RadialMapping {
    const val GAP_DEGREES = 90f
    const val START_ANGLE_DEGREES = 90f + GAP_DEGREES / 2f // 135deg: bottom-left of the gap
    const val SWEEP_DEGREES = 360f - GAP_DEGREES // 270deg of usable arc

    /** Bounded number of display bands the raw FFT spectrum is aggregated into (see [aggregateBands]). */
    const val DISPLAY_BAND_COUNT = 56

    const val TOP_DBFS = 0.0
    const val AMPLITUDE_DB_SPAN = 90.0 // matches DbScale.FLOOR_DBFS's magnitude

    /** Linear arc-fraction (0..1) -> absolute angle in degrees, per the gap/sweep above. */
    fun angleForFraction(fraction: Float): Float =
        START_ANGLE_DEGREES + fraction.coerceIn(0f, 1f) * SWEEP_DEGREES

    /**
     * Absolute angle (degrees, any range) -> arc fraction, or null if the angle falls inside the
     * excluded gap (e.g. a tap on the lower action-button area rather than the spectrum ring).
     */
    fun fractionForAngle(angleDegrees: Float): Float? {
        var normalized = angleDegrees % 360f
        if (normalized < 0f) normalized += 360f
        var relative = normalized - START_ANGLE_DEGREES
        if (relative < 0f) relative += 360f
        if (relative > SWEEP_DEGREES) return null
        return (relative / SWEEP_DEGREES).coerceIn(0f, 1f)
    }

    /**
     * Logarithmic-frequency bin index -> arc fraction (0..1). [minBin] is the lowest bin shown
     * (excludes DC, bin 0, which carries no meaningful frequency); [maxBin] is the Nyquist bin for
     * the negotiated sample rate/FFT size -- never a fixed assumption.
     */
    fun fractionForBin(bin: Int, minBin: Int, maxBin: Int): Float {
        if (maxBin <= minBin) return 0f
        val clamped = bin.coerceIn(minBin, maxBin)
        return (ln(clamped.toDouble() / minBin) / ln(maxBin.toDouble() / minBin)).toFloat().coerceIn(0f, 1f)
    }

    /** Inverse of [fractionForBin]: an arc fraction back to the nearest whole bin index. */
    fun binForFraction(fraction: Float, minBin: Int, maxBin: Int): Int {
        if (maxBin <= minBin) return minBin
        val logMin = ln(minBin.toDouble())
        val logMax = ln(maxBin.toDouble())
        val logBin = logMin + fraction.coerceIn(0f, 1f) * (logMax - logMin)
        return exp(logBin).roundToInt().coerceIn(minBin, maxBin)
    }

    /** dBFS -> radial fraction (0 at the floor, 1 at 0 dBFS), a fixed scale -- never autoscaled. */
    fun radialFractionForDb(db: Double, floorDb: Double = -AMPLITUDE_DB_SPAN, topDb: Double = TOP_DBFS): Float =
        ((db - floorDb) / (topDb - floorDb)).toFloat().coerceIn(0f, 1f)

    /** Arc fraction (0..1, e.g. from a tap's angle) -> the display band whose slice contains it. */
    fun bandForFraction(fraction: Float, bandCount: Int = DISPLAY_BAND_COUNT): Int =
        (fraction.coerceIn(0f, 1f) * bandCount).toInt().coerceIn(0, bandCount - 1)

    /** A display band index -> the arc fraction at that band's angular center (inverse-ish of [bandForFraction]). */
    fun fractionForBand(bandIndex: Int, bandCount: Int = DISPLAY_BAND_COUNT): Float =
        ((bandIndex + 0.5f) / bandCount).coerceIn(0f, 1f)

    /** The display-band index (0 until [DISPLAY_BAND_COUNT]-1) an absolute bin belongs to. */
    fun bandForBin(bin: Int, minBin: Int, maxBin: Int, bandCount: Int = DISPLAY_BAND_COUNT): Int {
        val fraction = fractionForBin(bin, minBin, maxBin)
        return (fraction * bandCount).toInt().coerceIn(0, bandCount - 1)
    }

    /** A display band's [loBin, hiBin] raw-bin range (inclusive), each spanning at least one bin. */
    fun binRangeForBand(bandIndex: Int, minBin: Int, maxBin: Int, bandCount: Int = DISPLAY_BAND_COUNT): IntRange {
        val loFraction = bandIndex.toFloat() / bandCount
        val hiFraction = (bandIndex + 1).toFloat() / bandCount
        val lo = binForFraction(loFraction, minBin, maxBin)
        val hi = maxOf(lo, binForFraction(hiFraction, minBin, maxBin) - 1).coerceAtLeast(lo)
        return lo..maxOf(lo, hi)
    }

    /**
     * Aggregates a raw one-sided magnitude spectrum ([magnitudesDbfs], index 0 = DC) into
     * [bandCount] log-frequency-spaced display bands, MAX-aggregating each band's underlying raw
     * bins (never averaging) so a narrow one- or two-bin peak still reads at its true amplitude
     * instead of being smeared down by neighboring quieter bins. DC (bin 0) is always excluded.
     * Each output entry also carries the frequency (in bins) of whichever raw bin produced that
     * band's max, so a selected band's readout can cite one exact, real bin -- never a fabricated
     * "center frequency" for the band.
     */
    fun aggregateBands(magnitudesDbfs: DoubleArray, bandCount: Int = DISPLAY_BAND_COUNT): Array<BandValue> {
        val maxBin = magnitudesDbfs.size - 1
        val minBin = 1
        if (maxBin <= minBin) return Array(bandCount) { BandValue(minBin, magnitudesDbfs.getOrElse(minBin) { -AMPLITUDE_DB_SPAN }) }
        return Array(bandCount) { bandIndex ->
            val range = binRangeForBand(bandIndex, minBin, maxBin, bandCount)
            var bestBin = range.first
            var bestDb = magnitudesDbfs[bestBin]
            for (bin in range) {
                if (magnitudesDbfs[bin] > bestDb) {
                    bestDb = magnitudesDbfs[bin]
                    bestBin = bin
                }
            }
            BandValue(bestBin, bestDb)
        }
    }

    /** One aggregated display band: [peakBin] is the real raw bin that produced [magnitudeDbfs]. */
    data class BandValue(val peakBin: Int, val magnitudeDbfs: Double)
}
