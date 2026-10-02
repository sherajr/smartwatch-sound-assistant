package com.peaceantz.stagescope.shared.measurement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasurementComparisonTest {

    private fun ctx(
        id: String = "a",
        at: Long = 1_000_000L,
        demo: Boolean = false,
        source: String = "Unprocessed",
        sampleRate: Int = 48_000,
        fft: Int = 4096,
        calibrated: Boolean = false,
        offset: Double = 0.0,
        rms: Double = -30.0,
        peak: Double = -10.0,
        bandDb: Double = -60.0,
        position: String? = null,
        rings: List<RingEvidence> = emptyList(),
        frozen: Boolean = false,
    ) = MeasurementContext(
        snapshotId = id, capturedAtEpochMs = at, capturedAtElapsedMs = at,
        origin = SnapshotOrigin.ANALYZER,
        device = DeviceEvidence(inputSourceLabel = source, isDemo = demo),
        run = RunEvidence(RunState.RUNNING, spectrumFrozen = frozen),
        config = ConfigEvidence(sampleRate, source, "$sampleRate|6", fft, sampleRate.toDouble() / fft, sampleRate / 2.0),
        level = LevelEvidence(rmsDbfs = rms, peakDbfs = peak),
        calibration = CalibrationEvidence(applied = calibrated, offsetDb = if (calibrated) offset else null),
        spectrum = SpectrumEvidence(bands = List(4) { i -> BandEvidence(100.0 * (i + 1), 100.0 * (i + 2), 150.0 * (i + 1), bandDb + i) }),
        rings = rings,
        userContext = position?.let { UserContext(micPosition = it) },
    )

    private fun ring(hz: Double, prom: Double, freshness: RingFreshness = RingFreshness.LIVE_NOW) = RingEvidence(
        captureId = hz.toLong(), frequencyHz = hz, prominenceDb = prom, lastObservedAgoMs = 10, trackingDurationMs = 2000,
        pinned = false, restoredFromDisk = false, mostProminent = false, freshness = freshness,
    )

    @Test
    fun `compatible snapshots produce locally computed numeric deltas`() {
        val before = ctx("b", at = 1_000_000L, rms = -30.0, peak = -12.0, bandDb = -60.0, position = "FOH, seat C12")
        val after = ctx("a", at = 1_060_000L, rms = -34.5, peak = -15.0, bandDb = -70.0, position = "foh,  seat c12")
        val result = MeasurementComparison.compare(before, after)
        result as ComparisonResult.Compatible
        assertEquals(-4.5, result.summary.rmsDeltaDb, 0.001)
        assertEquals(-3.0, result.summary.peakDeltaDb, 0.001)
        assertEquals(60, result.summary.secondsBetween)
        assertTrue("every band moved by -10 dB", result.summary.bandDeltas.all { it.deltaDb == -10.0 })
        assertEquals(4, result.summary.bandDeltas.size)
    }

    @Test
    fun `demo versus real is never compared`() {
        val r = MeasurementComparison.compare(ctx("b", demo = true), ctx("a", at = 2_000_000L, demo = false))
        r as ComparisonResult.Incompatible
        assertTrue(IncompatibilityReason.DEMO_MISMATCH in r.reasons)
        assertTrue("incompatible results ask for a new measurement", r.requestedAction.contains("new"))
    }

    @Test
    fun `different sample rate FFT size or input source is incompatible`() {
        val r = MeasurementComparison.compare(
            ctx("b", sampleRate = 48_000, fft = 4096, source = "Unprocessed"),
            ctx("a", at = 2_000_000L, sampleRate = 44_100, fft = 2048, source = "Mic (default)"),
        )
        r as ComparisonResult.Incompatible
        assertTrue(IncompatibilityReason.SAMPLE_RATE_MISMATCH in r.reasons)
        assertTrue(IncompatibilityReason.FFT_MISMATCH in r.reasons)
        assertTrue(IncompatibilityReason.INPUT_SOURCE_MISMATCH in r.reasons)
    }

    @Test
    fun `calibrated versus uncalibrated, or different offsets, is incompatible`() {
        val a = MeasurementComparison.compare(ctx("b", calibrated = false), ctx("a", at = 2_000_000L, calibrated = true, offset = 100.0))
        assertTrue((a as ComparisonResult.Incompatible).reasons.contains(IncompatibilityReason.CALIBRATION_MISMATCH))
        val b = MeasurementComparison.compare(ctx("b", calibrated = true, offset = 100.0), ctx("a", at = 2_000_000L, calibrated = true, offset = 104.0))
        assertTrue((b as ComparisonResult.Incompatible).reasons.contains(IncompatibilityReason.CALIBRATION_MISMATCH))
        val same = MeasurementComparison.compare(ctx("b", calibrated = true, offset = 100.0), ctx("a", at = 2_000_000L, calibrated = true, offset = 100.0))
        assertTrue(same is ComparisonResult.Compatible)
    }

    @Test
    fun `after must be later than before and not absurdly far apart`() {
        val reversed = MeasurementComparison.compare(ctx("b", at = 5_000_000L), ctx("a", at = 1_000_000L))
        assertTrue((reversed as ComparisonResult.Incompatible).reasons.contains(IncompatibilityReason.ORDER_INVALID))
        val dayApart = MeasurementComparison.compare(ctx("b", at = 0L), ctx("a", at = 13L * 3_600_000L))
        assertTrue((dayApart as ComparisonResult.Incompatible).reasons.contains(IncompatibilityReason.TOO_FAR_APART))
    }

    @Test
    fun `an explicitly different microphone position is incompatible but an undescribed one only warns`() {
        val moved = MeasurementComparison.compare(ctx("b", position = "front of house"), ctx("a", at = 2_000_000L, position = "stage left"))
        assertTrue((moved as ComparisonResult.Incompatible).reasons.contains(IncompatibilityReason.POSITION_DIFFERS))

        val unknown = MeasurementComparison.compare(ctx("b"), ctx("a", at = 2_000_000L))
        unknown as ComparisonResult.Compatible
        assertTrue(unknown.caveats.any { it.contains("position was not described") })
    }

    @Test
    fun `missing snapshot or missing level data is reported not guessed`() {
        assertTrue(MeasurementComparison.compare(null, ctx()) is ComparisonResult.Incompatible)
        val noLevel = ctx("a", at = 2_000_000L).copy(level = null)
        val r = MeasurementComparison.compare(ctx("b"), noLevel)
        assertTrue((r as ComparisonResult.Incompatible).reasons.contains(IncompatibilityReason.NO_LEVEL_DATA))
    }

    @Test
    fun `ring changes are matched within the bin tolerance`() {
        val before = ctx("b", rings = listOf(ring(1000.0, 20.0), ring(3150.0, 18.0)))
        val after = ctx("a", at = 2_000_000L, rings = listOf(ring(1003.0, 12.0), ring(5000.0, 22.0)))
        val r = MeasurementComparison.compare(before, after) as ComparisonResult.Compatible
        val kinds = r.summary.ringChanges.associate { it.frequencyHz.toInt() to it.kind }
        assertEquals(RingChangeKind.CHANGED, kinds[1003])
        assertEquals(RingChangeKind.DISAPPEARED, kinds[3150])
        assertEquals(RingChangeKind.APPEARED, kinds[5000])
        assertEquals(-8.0, r.summary.ringChanges.first { it.frequencyHz.toInt() == 1003 }.deltaDb!!, 0.001)
    }
}
