package com.peaceantz.stagescope.data

import com.peaceantz.stagescope.audio.CaptureConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationStateTest {

    @Test
    fun `calibration stays valid when configuration is unchanged`() {
        val config = CaptureConfig(48000, 9, "Unprocessed", true, emptyList())
        val calibration = CalibrationState(
            offsetDb = 3.5,
            referenceMeterReadingDb = 72.0,
            configFingerprint = config.fingerprint(),
            timestampMillis = 0L,
        )
        assertTrue(calibration.isValidFor(config.fingerprint()))
    }

    @Test
    fun `calibration is invalidated when the input configuration changes`() {
        val originalConfig = CaptureConfig(48000, 9, "Unprocessed", true, emptyList())
        val calibration = CalibrationState(
            offsetDb = 3.5,
            referenceMeterReadingDb = 72.0,
            configFingerprint = originalConfig.fingerprint(),
            timestampMillis = 0L,
        )
        val changedConfig = CaptureConfig(44100, 1, "Mic (default)", false, emptyList())
        assertFalse(calibration.isValidFor(changedConfig.fingerprint()))
    }
}
