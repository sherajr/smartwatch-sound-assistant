package com.peaceantz.stagescope.dsp

import kotlin.math.abs

/**
 * Pure energy-accumulation math for calibration's ~3s reference measurement -- kept separate from
 * [com.peaceantz.stagescope.ui.settings.CalibrationViewModel] (which has Android/`SystemClock`
 * dependencies not available to a plain JVM unit test) so the core rule -- energy-average across
 * the whole window and convert to dB exactly once, never average per-block dB readings -- is
 * directly unit-testable. Also tracks the per-block dB spread and any clipping, the two raw
 * signal-quality facts [com.peaceantz.stagescope.ui.settings.CalibrationViewModel] turns into
 * "too variable" / "clipped, retry" rejections.
 */
class CalibrationSampleAccumulator {
    private var energySum = 0.0
    private var sampleCount = 0L
    private var minBlockDb = Double.POSITIVE_INFINITY
    private var maxBlockDb = Double.NEGATIVE_INFINITY
    private var clippedAnyBlock = false

    val sampleCountSoFar: Long get() = sampleCount
    val clipped: Boolean get() = clippedAnyBlock

    fun accumulate(block: FloatArray) {
        if (block.isEmpty()) return
        var sumSquares = 0.0
        var peak = 0.0
        for (sample in block) {
            val magnitude = abs(sample.toDouble())
            sumSquares += magnitude * magnitude
            if (magnitude > peak) peak = magnitude
        }
        val blockRmsDb = DbScale.rmsDbfs(sumSquares / block.size)
        energySum += sumSquares
        sampleCount += block.size
        if (blockRmsDb < minBlockDb) minBlockDb = blockRmsDb
        if (blockRmsDb > maxBlockDb) maxBlockDb = blockRmsDb
        if (peak >= DbScale.CLIP_THRESHOLD) clippedAnyBlock = true
    }

    /** Energy-averaged across every accumulated sample, converted to dB exactly once. */
    fun averagedRmsDbfs(): Double = if (sampleCount <= 0L) DbScale.FLOOR_DBFS else DbScale.rmsDbfs(energySum / sampleCount)

    /** Spread between the loudest and quietest per-block reading seen -- the stability signal. */
    fun blockSpreadDb(): Double = if (maxBlockDb < minBlockDb) 0.0 else maxBlockDb - minBlockDb
}
