package com.peaceantz.stagescope.ui.analyzer

import com.peaceantz.stagescope.dsp.DbScale

/**
 * Fixed, documented scale for the circumference sound-level meter -- one range for raw dBFS, a
 * separate fixed "Estimated SPL" range for calibrated readings, so a positive SPL number is never
 * plotted against the negative dBFS scale that the raw range uses (see
 * [com.peaceantz.stagescope.ui.analyzer.AnalyzerReading.rmsDisplayDbfs]/`rmsRawDbfs`, which exist
 * specifically to prevent that mixup). The SPL range is an initial engineering value (a typical
 * theatre monitoring band), not derived from acoustic research -- see docs/MEASUREMENTS.md, same
 * caveat as the calibration/ring-hysteresis constants elsewhere in this app.
 */
object LevelMeterScale {
    const val DBFS_FLOOR: Double = DbScale.FLOOR_DBFS
    const val DBFS_CEILING: Double = 0.0
    const val SPL_FLOOR: Double = 30.0
    const val SPL_CEILING: Double = 120.0

    /** [displayDbfs] is the SAME quantity the center reading shows (offset-applied when
     *  calibrated) -- the meter must never be fed the always-raw `rmsRawDbfs` while calibrated. */
    fun fraction(displayDbfs: Double, isCalibrated: Boolean): Float {
        val floor = if (isCalibrated) SPL_FLOOR else DBFS_FLOOR
        val ceiling = if (isCalibrated) SPL_CEILING else DBFS_CEILING
        return RadialMapping.radialFractionForDb(displayDbfs, floorDb = floor, topDb = ceiling)
    }

    fun rangeLabel(isCalibrated: Boolean): String =
        if (isCalibrated) "$SPL_FLOOR–$SPL_CEILING dB SPL" else "$DBFS_FLOOR–$DBFS_CEILING dBFS"
}
