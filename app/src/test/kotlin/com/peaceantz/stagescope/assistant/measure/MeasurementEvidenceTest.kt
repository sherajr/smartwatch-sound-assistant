package com.peaceantz.stagescope.assistant.measure

import com.peaceantz.stagescope.assistant.measure.TestMeasurements.BIN_WIDTH
import com.peaceantz.stagescope.shared.measurement.HistoryPoint
import com.peaceantz.stagescope.shared.measurement.MeasurementContext
import com.peaceantz.stagescope.shared.measurement.RingFreshness
import com.peaceantz.stagescope.shared.measurement.RunState
import com.peaceantz.stagescope.shared.measurement.SnapshotOrigin
import com.peaceantz.stagescope.shared.util.StageScopeJson
import com.peaceantz.stagescope.ui.analyzer.RadialMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectrumEvidenceBuilderTest {

    @Test
    fun `peaks are the strongest local maxima with contrast and neighbouring bins, and adjacent bins are not separate peaks`() {
        val mags = TestMeasurements.magnitudes()
        val peaks = SpectrumEvidenceBuilder.peaks(mags, BIN_WIDTH)
        assertEquals(listOf(180 * BIN_WIDTH, 400 * BIN_WIDTH).map { Math.round(it * 10) / 10.0 }, peaks.map { it.frequencyHz }.take(2))
        assertEquals(-20.0, peaks[0].magnitudeDbfs, 1e-9)
        assertTrue("a narrow 60 dB spike has large contrast: ${peaks[0].contrastDb}", peaks[0].contrastDb > 50.0)
        assertEquals(9, peaks[0].neighbours.size)
        assertTrue("the shoulder bins 179/181 belong to the same lobe", peaks.none { it.frequencyHz == Math.round(181 * BIN_WIDTH * 10) / 10.0 })
        assertTrue(peaks.size <= SpectrumEvidenceBuilder.MAX_PEAKS)
        assertEquals("sorted strongest first", peaks.map { it.magnitudeDbfs }.sortedDescending(), peaks.map { it.magnitudeDbfs })
    }

    @Test
    fun `bands keep their true maximum and their own peak frequency`() {
        val mags = TestMeasurements.magnitudes()
        val evidence = SpectrumEvidenceBuilder.build(mags, BIN_WIDTH, RadialMapping.aggregateBands(mags), held = false)
        assertEquals(RadialMapping.DISPLAY_BAND_COUNT, evidence.bands.size)
        val loud = evidence.bands.single { it.maxDbfs == -20.0 }
        assertEquals(Math.round(180 * BIN_WIDTH * 10) / 10.0, loud.peakHz, 1e-9)
        assertTrue("band edges ascend", evidence.bands.zipWithNext().all { (a, b) -> a.loHz <= b.loHz })
        assertTrue(evidence.bands.all { it.loHz < it.hiHz })
        assertTrue("raw dBFS scale", evidence.scale.contains("raw"))
    }

    @Test
    fun `the noise floor estimate is the median level above 100 Hz and ignores a few strong peaks`() {
        assertEquals(-80.0, SpectrumEvidenceBuilder.noiseFloor(TestMeasurements.magnitudes(), BIN_WIDTH)!!, 1e-9)
        assertNull(SpectrumEvidenceBuilder.noiseFloor(DoubleArray(5) { -50.0 }, BIN_WIDTH))
        assertNull(SpectrumEvidenceBuilder.noiseFloor(TestMeasurements.magnitudes(), 0.0))
    }

    @Test
    fun `a flat or tiny spectrum yields no peaks rather than invented ones`() {
        assertTrue(SpectrumEvidenceBuilder.peaks(DoubleArray(3) { -80.0 }, BIN_WIDTH).isEmpty())
        assertTrue(SpectrumEvidenceBuilder.peaks(TestMeasurements.magnitudes(), BIN_WIDTH, maxPeaks = 0).isEmpty())
    }
}

class MeasurementHistoryBufferTest {
    @Test
    fun `samples closer together than the minimum interval are dropped`() {
        val b = MeasurementHistoryBuffer(windowMs = 10_000, minIntervalMs = 250)
        listOf(0L, 100L, 250L, 400L, 500L).forEach { b.add(it, -30.0, -10.0) }
        assertEquals(listOf(-500L, -250L, 0L), b.snapshot(500L).map { it.offsetMs })
    }

    @Test
    fun `only the last window is kept and offsets are never in the future`() {
        val b = MeasurementHistoryBuffer(windowMs = 10_000, minIntervalMs = 250, maxPoints = 100)
        for (t in 0L..20_000L step 250L) b.add(t, -30.0, -10.0)
        val snap = b.snapshot(20_000L)
        assertTrue(snap.first().offsetMs >= -10_000L)
        assertEquals(0L, snap.last().offsetMs)
        assertTrue(snap.all { it.offsetMs <= 0L })
        assertEquals(snap.map { it.offsetMs }.sorted(), snap.map { it.offsetMs })
    }

    @Test
    fun `size is bounded`() {
        val b = MeasurementHistoryBuffer(windowMs = 1_000_000, minIntervalMs = 1, maxPoints = 40)
        for (t in 0L until 500L) b.add(t, -30.0, -10.0)
        assertEquals(40, b.snapshot(500L).size)
    }

    @Test
    fun `a pause leaves a gap that ages out instead of inventing points`() {
        val b = MeasurementHistoryBuffer()
        for (t in 0L..2_000L step 250L) b.add(t, -30.0, -10.0)
        assertTrue(b.snapshot(30_000L).isEmpty())
    }

    @Test
    fun `values are rounded and peak details carried`() {
        val b = MeasurementHistoryBuffer()
        b.add(0, -30.04, -10.06, topPeakHz = 2109.37, topPeakDbfs = -19.96, liveRingCount = 2)
        val p = b.snapshot(0).single()
        assertEquals(HistoryPoint(0, -30.0, -10.1, 2109.4, -20.0, 2), p)
    }

    @Test
    fun `clear empties it`() {
        val b = MeasurementHistoryBuffer()
        b.add(0, -30.0, -10.0)
        b.clear()
        assertTrue(b.snapshot(0).isEmpty())
    }
}

class MeasurementSnapshotBuilderTest {
    private val t = TestMeasurements

    @Test
    fun `uncalibrated levels are raw dBFS and there is no estimated SPL`() {
        val ctx = MeasurementSnapshotBuilder.build(t.inputs(analyzer = t.analyzerSample(t.reading(rmsRaw = -30.0))))
        assertEquals(-30.0, ctx.level!!.rmsDbfs, 1e-9)
        assertEquals(-12.5, ctx.level!!.peakDbfs, 1e-9)
        assertEquals(-20.0, ctx.level!!.maxRmsDbfs!!, 1e-9)
        assertFalse(ctx.calibration!!.applied)
        assertNull(ctx.calibration!!.estimatedSplDb)
        assertTrue(ctx.notes.any { it.contains("uncalibrated dBFS") })
    }

    @Test
    fun `calibration offsets only the RMS-derived estimate - level evidence and every spectrum value stay raw`() {
        val raw = MeasurementSnapshotBuilder.build(t.inputs(analyzer = t.analyzerSample(t.reading(rmsRaw = -30.0))))
        val cal = MeasurementSnapshotBuilder.build(
            t.inputs(analyzer = t.analyzerSample(t.reading(rmsRaw = -30.0, offset = 94.0, calibrated = true), t.calibration(94.0))),
        )
        assertEquals("level stays raw even when the screen shows SPL", -30.0, cal.level!!.rmsDbfs, 1e-9)
        assertEquals("max and average are un-offset back to raw", -20.0, cal.level!!.maxRmsDbfs!!, 1e-9)
        assertEquals(-34.0, cal.level!!.sessionAverageRmsDbfs!!, 1e-9)
        assertEquals("sample peak is never offset", -12.5, cal.level!!.peakDbfs, 1e-9)
        assertTrue(cal.calibration!!.applied)
        assertEquals(94.0, cal.calibration!!.offsetDb!!, 1e-9)
        assertEquals(70.0, cal.calibration!!.referenceMeterReadingDb!!, 1e-9)
        assertEquals("estimated SPL = raw RMS + offset", 64.0, cal.calibration!!.estimatedSplDb!!, 1e-9)
        assertEquals("the spectrum is identical with and without calibration", raw.spectrum, cal.spectrum)
        assertTrue(cal.notes.any { it.contains("Estimated SPL is one broadband offset") })
    }

    @Test
    fun `a stored calibration that doesn't apply to this reading is not reported as applied`() {
        val ctx = MeasurementSnapshotBuilder.build(t.inputs(analyzer = t.analyzerSample(t.reading(calibrated = false), t.calibration())))
        assertFalse(ctx.calibration!!.applied)
        assertNull(ctx.calibration!!.offsetDb)
    }

    @Test
    fun `a pinned or held or restored ring is history - freshness comes from when it was last heard`() {
        val now = 900_000L
        val captures = listOf(
            t.capture(1, 2100.0, confirmedAt = now - 5_000, lastSeen = now - 100, prominence = 24.0),
            t.capture(2, 3150.0, confirmedAt = now - 9_000, lastSeen = now - 5_000, prominence = 18.0),
            t.capture(3, 800.0, confirmedAt = now - 90_000, lastSeen = now - 40_000, prominence = 30.0, pinned = true),
            t.capture(4, 1250.0, confirmedAt = 0, lastSeen = 0, prominence = 0.0, pinned = true, restored = true, savedAt = 1_799_000_000_000L),
        )
        val ctx = MeasurementSnapshotBuilder.build(t.inputs(ring = t.ringSample(t.ringSnapshot(captures, mostProminent = 3)), nowMono = now))
        val byId = ctx.rings.associateBy { it.captureId }
        assertEquals(RingFreshness.LIVE_NOW, byId[1]!!.freshness)
        assertEquals(100L, byId[1]!!.lastObservedAgoMs)
        assertEquals(4_900L, byId[1]!!.trackingDurationMs)
        assertEquals(RingFreshness.RECENTLY_SEEN, byId[2]!!.freshness)
        assertEquals("pinned but not heard for 40 s: stale, not live", RingFreshness.STALE, byId[3]!!.freshness)
        assertTrue(byId[3]!!.pinned && byId[3]!!.mostProminent)
        assertEquals(RingFreshness.RESTORED_NOT_REOBSERVED, byId[4]!!.freshness)
        assertNull("a restored ring has no observation age", byId[4]!!.lastObservedAgoMs)
        assertEquals(1_799_000_000_000L, byId[4]!!.restoredSavedAtEpochMs)
        assertEquals("live first, restored last", listOf(1L, 2L, 3L, 4L), ctx.rings.map { it.captureId })
        assertTrue(ctx.notes.any { it.contains("were not sounding at snapshot time") })
        assertTrue(ctx.notes.any { it.contains("restored from a previous session") })
        assertTrue(ctx.notes.any { it.contains("not a probability of acoustic feedback") })
    }

    @Test
    fun `when measurement is stopped nothing is live - even a ring heard a moment ago`() {
        val now = 900_000L
        val captures = listOf(t.capture(1, 2100.0, now - 5_000, now - 100))
        val ctx = MeasurementSnapshotBuilder.build(
            t.inputs(ring = t.ringSample(t.ringSnapshot(captures)), analyzer = t.analyzerSample(t.reading(), atElapsed = 480_000L), runState = RunState.STOPPED, nowMono = now, nowElapsed = 500_000L),
        )
        assertEquals(RingFreshness.RECENTLY_SEEN, ctx.rings.single().freshness)
        assertFalse(ctx.ringBank!!.acquisitionActive)
        assertEquals(RunState.STOPPED, ctx.run.state)
        assertTrue(ctx.notes.any { it.contains("Capture was not running") })
        assertTrue("says how old the readings are", ctx.notes.any { it.contains("about 20 s before this snapshot") })
    }

    @Test
    fun `a pause for voice is reported as such`() {
        val ctx = MeasurementSnapshotBuilder.build(t.inputs(analyzer = t.analyzerSample(t.reading()), runState = RunState.PAUSED_FOR_SPEECH))
        assertEquals(RunState.PAUSED_FOR_SPEECH, ctx.run.state)
        assertTrue(ctx.notes.any { it.contains("paused for voice") })
    }

    @Test
    fun `demo data is labelled as demo`() {
        val ctx = MeasurementSnapshotBuilder.build(t.inputs(analyzer = t.analyzerSample(t.reading(demo = true))))
        assertTrue(ctx.device.isDemo)
        assertTrue(ctx.notes.any { it.startsWith("DEMO") })
    }

    @Test
    fun `a frozen spectrum is flagged and level keeps its own value`() {
        val ctx = MeasurementSnapshotBuilder.build(t.inputs(analyzer = t.analyzerSample(t.reading(held = true))))
        assertTrue(ctx.run.spectrumFrozen)
        assertTrue(ctx.spectrum!!.held)
        assertTrue(ctx.notes.any { it.contains("spectrum was frozen") })
    }

    @Test
    fun `an error state carries its message`() {
        val ctx = MeasurementSnapshotBuilder.build(t.inputs(runState = RunState.ERROR, error = "Microphone permission denied"))
        assertEquals("Microphone permission denied", ctx.run.errorMessage)
        assertTrue(ctx.notes.any { it.contains("Capture reported an error") })
    }

    @Test
    fun `with nothing measured there is no evidence to send`() {
        val ctx = MeasurementSnapshotBuilder.build(t.inputs(runState = RunState.NOT_STARTED))
        assertFalse(MeasurementSnapshotBuilder.hasEvidence(ctx))
        assertNull(ctx.level)
        assertNull(ctx.spectrum)
        assertTrue(ctx.rings.isEmpty())
        assertEquals("unknown", ctx.device.inputSourceLabel)
    }

    @Test
    fun `the configuration is the negotiated one, not an assumption`() {
        val ctx = MeasurementSnapshotBuilder.build(t.inputs(analyzer = t.analyzerSample(t.reading())))
        val c = ctx.config!!
        assertEquals(48_000, c.sampleRateHz)
        assertEquals("48000|9", c.fingerprint)
        assertEquals(4096, c.fftSize)
        assertEquals(BIN_WIDTH, c.binWidthHz, 1e-9)
        assertEquals(24_000.0, c.nyquistHz, 1e-9)
        assertEquals(listOf("Noise Suppressor: disabled", "Automatic Gain Control: not present"), ctx.device.effects)
    }

    @Test
    fun `a snapshot is a deep copy - later changes to live arrays never reach it`() {
        val mags = t.magnitudes()
        val before = MeasurementSnapshotBuilder.build(t.inputs(analyzer = t.analyzerSample(t.reading(mags = mags))))
        val frozenCopy = StageScopeJson.encodeToString(MeasurementContext.serializer(), before)
        mags.fill(-10.0)
        assertEquals(frozenCopy, StageScopeJson.encodeToString(MeasurementContext.serializer(), before))
    }

    @Test
    fun `the capture time is exactly the time it was taken`() {
        val ctx = MeasurementSnapshotBuilder.build(t.inputs(analyzer = t.analyzerSample(t.reading()), nowEpoch = 1_800_000_123_456L, nowElapsed = 77L, origin = SnapshotOrigin.RING))
        assertEquals(1_800_000_123_456L, ctx.capturedAtEpochMs)
        assertEquals(77L, ctx.capturedAtElapsedMs)
        assertEquals(SnapshotOrigin.RING, ctx.origin)
        assertEquals(MeasurementContext.SCHEMA_VERSION, ctx.schemaVersion)
    }

    @Test
    fun `a full realistic snapshot serializes comfortably under the message budget and round-trips`() {
        val now = 900_000L
        val captures = (1L..5L).map { t.capture(it, 500.0 * it, now - 8_000, now - 100 * it, prominence = 30.0 - it) }
        val history = (0 until 40).map { HistoryPoint(-(it * 250L), -30.0 - it * 0.1, -10.0, 2100.0, -20.0, 1) }
        val ctx = MeasurementSnapshotBuilder.build(
            t.inputs(analyzer = t.analyzerSample(t.reading(rmsRaw = -30.0, offset = 94.0, calibrated = true), t.calibration()), ring = t.ringSample(t.ringSnapshot(captures)), history = history, nowMono = now),
        )
        val json = StageScopeJson.encodeToString(MeasurementContext.serializer(), ctx)
        assertTrue("size ${json.length}", json.toByteArray().size < MeasurementSnapshotBuilder.MAX_CONTEXT_BYTES)
        assertEquals(ctx, StageScopeJson.decodeFromString(MeasurementContext.serializer(), json))
        assertEquals(MeasurementSnapshotBuilder.fitToBudget(ctx), ctx)
    }

    @Test
    fun `when over budget the least valuable detail goes first and the loss is stated`() {
        val now = 900_000L
        val history = (0 until 40).map { HistoryPoint(-(it * 250L), -30.0, -10.0, 2100.0, -20.0, 1) }
        val ctx = MeasurementSnapshotBuilder.build(
            t.inputs(analyzer = t.analyzerSample(t.reading()), ring = t.ringSample(t.ringSnapshot(listOf(t.capture(1, 2100.0, now - 5_000, now - 100)))), history = history, nowMono = now),
        )
        fun bytes(c: MeasurementContext) = StageScopeJson.encodeToString(MeasurementContext.serializer(), c).toByteArray().size

        val noHistory = MeasurementSnapshotBuilder.fitToBudget(ctx, bytes(ctx) - 1)
        assertTrue(noHistory.history.isEmpty())
        assertNotNull(noHistory.spectrum)
        assertTrue(noHistory.notes.last().contains("recent history"))

        val tiny = MeasurementSnapshotBuilder.fitToBudget(ctx, 1_000)
        assertNotNull("level and rings always survive", tiny.level)
        assertEquals(1, tiny.rings.size)
        assertTrue(tiny.spectrum!!.bands.isEmpty())
        assertTrue(tiny.notes.last().contains("left out"))
    }
}
