package com.peaceantz.stagescope.assistant.measure

import com.peaceantz.stagescope.shared.measurement.BandEvidence
import com.peaceantz.stagescope.shared.measurement.BinPoint
import com.peaceantz.stagescope.shared.measurement.PeakDetail
import com.peaceantz.stagescope.shared.measurement.SpectrumEvidence
import com.peaceantz.stagescope.ui.analyzer.RadialMapping
import kotlin.math.ceil

/**
 * Turns the raw one-sided spectrum into evidence an assistant can reason about: the display bands (each the
 * MAX of its raw bins, so a narrow peak keeps its true level), the strongest local peaks at native
 * resolution with their neighbouring bins, and a rough noise-floor estimate. Everything stays raw
 * dBFS -- a calibration offset is never applied to a spectrum bin.
 */
object SpectrumEvidenceBuilder {
    const val MAX_PEAKS = 8
    private const val NEIGHBOUR_HALF_WIDTH = 4
    private const val CONTRAST_HALF_WIDTH = 6
    private const val MIN_PEAK_SEPARATION_BINS = 3
    private const val NOISE_FLOOR_FROM_HZ = 100.0

    fun build(magnitudesDbfs: DoubleArray, binWidthHz: Double, bands: Array<RadialMapping.BandValue>, held: Boolean, maxPeaks: Int = MAX_PEAKS): SpectrumEvidence {
        val maxBin = magnitudesDbfs.size - 1
        val bandEvidence = bands.mapIndexed { index, band ->
            val range = RadialMapping.binRangeForBand(index, 1, maxBin, bands.size)
            BandEvidence(
                loHz = round1(range.first * binWidthHz), hiHz = round1((range.last + 1) * binWidthHz),
                peakHz = round1(band.peakBin * binWidthHz), maxDbfs = round1(band.magnitudeDbfs),
            )
        }
        return SpectrumEvidence(
            bands = bandEvidence,
            peaks = peaks(magnitudesDbfs, binWidthHz, maxPeaks),
            noiseFloorEstimateDbfs = noiseFloor(magnitudesDbfs, binWidthHz),
            held = held,
        )
    }

    /** Strongest local maxima (>= [MIN_PEAK_SEPARATION_BINS] apart), each with its contrast and neighbouring bins. */
    fun peaks(m: DoubleArray, binWidthHz: Double, maxPeaks: Int = MAX_PEAKS): List<PeakDetail> {
        val minBin = 2
        val maxBin = m.size - 2
        if (maxBin <= minBin || maxPeaks <= 0) return emptyList()
        val candidates = ArrayList<Int>()
        for (k in minBin..maxBin) if (m[k] >= m[k - 1] && m[k] >= m[k + 1]) candidates += k
        val chosen = ArrayList<Int>()
        for (k in candidates.sortedByDescending { m[it] }) {
            if (chosen.none { kotlin.math.abs(it - k) < MIN_PEAK_SEPARATION_BINS }) chosen += k
            if (chosen.size == maxPeaks) break
        }
        return chosen.map { k ->
            val lo = maxOf(minBin, k - CONTRAST_HALF_WIDTH)
            val hi = minOf(maxBin, k + CONTRAST_HALF_WIDTH)
            var sum = 0.0
            var n = 0
            for (j in lo..hi) if (j != k) { sum += m[j]; n++ }
            val contrast = if (n == 0) 0.0 else m[k] - sum / n
            val nLo = maxOf(0, k - NEIGHBOUR_HALF_WIDTH)
            val nHi = minOf(m.size - 1, k + NEIGHBOUR_HALF_WIDTH)
            PeakDetail(
                frequencyHz = round1(k * binWidthHz), magnitudeDbfs = round1(m[k]), contrastDb = round1(contrast),
                neighbours = (nLo..nHi).map { BinPoint(round1(it * binWidthHz), round1(m[it])) },
            )
        }.sortedByDescending { it.magnitudeDbfs }
    }

    /** Median bin level above [NOISE_FLOOR_FROM_HZ] -- a crude floor estimate, labelled as such by its field name. */
    fun noiseFloor(m: DoubleArray, binWidthHz: Double): Double? {
        if (binWidthHz <= 0.0) return null
        val from = ceil(NOISE_FLOOR_FROM_HZ / binWidthHz).toInt().coerceAtLeast(1)
        if (m.size - from < 8) return null
        val sorted = m.copyOfRange(from, m.size).also { it.sort() }
        return round1(sorted[sorted.size / 2])
    }

    private fun round1(v: Double) = Math.round(v * 10.0) / 10.0
}
