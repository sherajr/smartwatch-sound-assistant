package com.peaceantz.stagescope.shared.measurement

import com.peaceantz.stagescope.shared.util.StageScopeJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasurementQualityTest {

    @Test
    fun `a pinned ring that was last seen long ago is not live`() {
        // pinned = true must not matter: freshness comes only from observation timing.
        assertEquals(
            RingFreshness.STALE,
            RingFreshnessClassifier.classify(restoredFromDisk = false, lastObservedAgoMs = 45_000, acquisitionActive = true, liveGraceMs = 150, autoHoldMs = 20_000),
        )
        assertEquals(
            RingFreshness.LIVE_NOW,
            RingFreshnessClassifier.classify(false, 100, true, 150, 20_000),
        )
        assertEquals(
            RingFreshness.RECENTLY_SEEN,
            RingFreshnessClassifier.classify(false, 5_000, true, 150, 20_000),
        )
    }

    @Test
    fun `a ring restored from disk stays history until it is re-observed`() {
        assertEquals(
            RingFreshness.RESTORED_NOT_REOBSERVED,
            RingFreshnessClassifier.classify(true, 0, true, 150, 20_000),
        )
    }

    @Test
    fun `capture that is not running cannot report a ring as live`() {
        assertEquals(
            RingFreshness.RECENTLY_SEEN,
            RingFreshnessClassifier.classify(false, 100, acquisitionActive = false, liveGraceMs = 150, autoHoldMs = 20_000),
        )
    }

    private fun base(demo: Boolean = false, calibrated: Boolean = false, rings: List<RingEvidence> = emptyList()) = MeasurementContext(
        snapshotId = "s", capturedAtEpochMs = 1_000L, capturedAtElapsedMs = 1_000L, origin = SnapshotOrigin.RING,
        device = DeviceEvidence(inputSourceLabel = "Unprocessed", isDemo = demo),
        run = RunEvidence(RunState.RUNNING),
        config = ConfigEvidence(48_000, "Unprocessed", "48000|9", 4096, 11.71875, 24_000.0),
        level = LevelEvidence(-30.0, -10.0, clippingNow = true),
        calibration = CalibrationEvidence(applied = calibrated, offsetDb = if (calibrated) 100.0 else null, estimatedSplDb = if (calibrated) 70.0 else null),
        rings = rings,
    )

    @Test
    fun `notes keep detector contrast and single microphone limits explicit`() {
        val ring = RingEvidence(1, 1000.0, 15.0, 10, 1000, pinned = true, restoredFromDisk = true, mostProminent = true, freshness = RingFreshness.RESTORED_NOT_REOBSERVED)
        val notes = MeasurementQuality.notes(base(rings = listOf(ring)))
        assertTrue(notes.any { it.contains("Single microphone") })
        assertTrue(notes.any { it.contains("not a probability of acoustic feedback") })
        assertTrue(notes.any { it.contains("watch microphone input only") })
        assertTrue(notes.any { it.contains("restored from a previous session") })
        assertTrue(notes.any { it.contains("uncalibrated dBFS") })
    }

    @Test
    fun `demo and calibrated notes are labelled`() {
        assertTrue(MeasurementQuality.notes(base(demo = true)).any { it.startsWith("DEMO") })
        val calibrated = MeasurementQuality.notes(base(calibrated = true))
        assertTrue(calibrated.any { it.contains("Estimated SPL is one broadband offset") })
        assertFalse(calibrated.any { it.contains("uncalibrated dBFS") })
    }

    @Test
    fun `snapshot age is measured from capture time, never relabelled`() {
        val c = base()
        assertEquals(SnapshotAge.JUST_TAKEN, MeasurementQuality.age(c, c.capturedAtEpochMs + 5_000))
        assertEquals(SnapshotAge.RECENT, MeasurementQuality.age(c, c.capturedAtEpochMs + 60_000))
        assertEquals(SnapshotAge.OLD, MeasurementQuality.age(c, c.capturedAtEpochMs + 10 * 60_000))
        assertEquals(30L, MeasurementQuality.ageSeconds(c, c.capturedAtEpochMs + 30_000))
    }

    @Test
    fun `measurement context round-trips through json without losing the capture time`() {
        val c = base().copy(
            notes = listOf("n"),
            spectrum = SpectrumEvidence(bands = listOf(BandEvidence(10.0, 20.0, 15.0, -50.0)), peaks = listOf(PeakDetail(15.0, -40.0, 12.0, listOf(BinPoint(14.0, -55.0)))))
        )
        val text = StageScopeJson.encodeToString(MeasurementContext.serializer(), c)
        val back = StageScopeJson.decodeFromString(MeasurementContext.serializer(), text)
        assertEquals(c, back)
        assertEquals(1_000L, back.capturedAtEpochMs)
    }
}
