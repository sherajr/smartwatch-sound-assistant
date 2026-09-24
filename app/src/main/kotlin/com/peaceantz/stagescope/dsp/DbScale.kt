package com.peaceantz.stagescope.dsp

import kotlin.math.log10

/**
 * Decibel-full-scale conversions shared by the level meter and spectrum analyzer.
 *
 * Invariant (verified in [com.peaceantz.stagescope.dsp.LevelMeterTest]): a sine wave with peak
 * amplitude 0.5 measures ~-9.03 dBFS RMS and ~-6.02 dBFS sample peak.
 */
object DbScale {
    /** Finite display floor for silence; nothing is ever shown below this. */
    const val FLOOR_DBFS: Double = -90.0

    /** Normalized sample magnitude (0..1) at/above which we call it digital clipping. */
    const val CLIP_THRESHOLD: Double = 0.998

    /** RMS dBFS = 10*log10(meanSquare), floored. */
    fun rmsDbfs(meanSquare: Double): Double {
        if (meanSquare <= 0.0 || meanSquare.isNaN()) return FLOOR_DBFS
        return (10.0 * log10(meanSquare)).coerceAtLeast(FLOOR_DBFS)
    }

    /** Sample peak dBFS = 20*log10(peakAbs), floored. */
    fun peakDbfs(peakAbs: Double): Double {
        if (peakAbs <= 0.0 || peakAbs.isNaN()) return FLOOR_DBFS
        return (20.0 * log10(peakAbs)).coerceAtLeast(FLOOR_DBFS)
    }

    /** Amplitude-domain dBFS = 20*log10(amplitude), floored. Used for spectrum bins. */
    fun amplitudeDbfs(amplitude: Double): Double = peakDbfs(amplitude)
}
