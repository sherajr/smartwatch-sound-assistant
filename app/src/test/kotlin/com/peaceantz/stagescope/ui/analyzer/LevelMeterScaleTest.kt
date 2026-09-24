package com.peaceantz.stagescope.ui.analyzer

import org.junit.Assert.assertEquals
import org.junit.Test

class LevelMeterScaleTest {

    @Test
    fun `uncalibrated fraction uses the fixed -90 to 0 dBFS range`() {
        assertEquals(0f, LevelMeterScale.fraction(-90.0, isCalibrated = false), 1e-6f)
        assertEquals(1f, LevelMeterScale.fraction(0.0, isCalibrated = false), 1e-6f)
        assertEquals(0.5f, LevelMeterScale.fraction(-45.0, isCalibrated = false), 1e-6f)
    }

    @Test
    fun `calibrated fraction uses the fixed Estimated SPL range, never the dBFS range`() {
        // A positive SPL value would clamp to 1.0 (fully lit) on the negative dBFS range -- the
        // whole point of a separate range is that it does NOT do that here.
        assertEquals(0f, LevelMeterScale.fraction(30.0, isCalibrated = true), 1e-6f)
        assertEquals(1f, LevelMeterScale.fraction(120.0, isCalibrated = true), 1e-6f)
        assertEquals(0.5f, LevelMeterScale.fraction(75.0, isCalibrated = true), 1e-6f)
    }

    @Test
    fun `fraction clamps safely past either endpoint`() {
        assertEquals(0f, LevelMeterScale.fraction(-200.0, isCalibrated = false), 1e-6f)
        assertEquals(1f, LevelMeterScale.fraction(20.0, isCalibrated = false), 1e-6f)
        assertEquals(0f, LevelMeterScale.fraction(-10.0, isCalibrated = true), 1e-6f)
        assertEquals(1f, LevelMeterScale.fraction(200.0, isCalibrated = true), 1e-6f)
    }
}
