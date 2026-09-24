package com.peaceantz.stagescope.dsp

import kotlin.math.abs

/**
 * Stateful RMS/peak accumulator for the LEVEL page.
 *
 * Session energy average is computed by accumulating sample *energy* (sum of squares) and a
 * sample count, then converting to dB once at read time -- never by averaging dB values, which
 * would be mathematically wrong (dB is a log scale).
 */
class LevelMeter {

    private var energySum = 0.0
    private var energyCount = 0L
    private var maxRmsDbfs = DbScale.FLOOR_DBFS
    private var everClipped = false

    data class BlockResult(
        val rmsDbfs: Double,
        val peakDbfs: Double,
        val clipped: Boolean,
    )

    /** Processes one block of normalized samples (range approximately -1..1). */
    fun processBlock(samples: FloatArray): BlockResult {
        if (samples.isEmpty()) {
            return BlockResult(DbScale.FLOOR_DBFS, DbScale.FLOOR_DBFS, false)
        }
        var sumSquares = 0.0
        var peak = 0.0
        for (sample in samples) {
            val magnitude = abs(sample.toDouble())
            sumSquares += magnitude * magnitude
            if (magnitude > peak) peak = magnitude
        }
        val meanSquare = sumSquares / samples.size
        val rmsDbfs = DbScale.rmsDbfs(meanSquare)
        val peakDbfs = DbScale.peakDbfs(peak)
        val clipped = peak >= DbScale.CLIP_THRESHOLD

        energySum += sumSquares
        energyCount += samples.size
        if (rmsDbfs > maxRmsDbfs) maxRmsDbfs = rmsDbfs
        if (clipped) everClipped = true

        return BlockResult(rmsDbfs, peakDbfs, clipped)
    }

    fun sessionEnergyAverageDbfs(): Double {
        if (energyCount <= 0L) return DbScale.FLOOR_DBFS
        return DbScale.rmsDbfs(energySum / energyCount)
    }

    fun maxRmsDbfsSoFar(): Double = maxRmsDbfs

    fun hasClippedSinceReset(): Boolean = everClipped

    /** Clears accumulated statistics. Does not affect whether capture is running. */
    fun reset() {
        energySum = 0.0
        energyCount = 0L
        maxRmsDbfs = DbScale.FLOOR_DBFS
        everClipped = false
    }
}
