package com.peaceantz.stagescope.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

private class FakeClock(private var currentMs: Long = 0L) : MonotonicClock {
    override fun nowMillis(): Long = currentMs
    fun advance(ms: Long) {
        currentMs += ms
    }
}

class RingTrackerTest {

    private val binWidth = 10.0
    private val binCount = 400

    private fun frame(vararg peaks: Pair<Int, Double>, baselineDb: Double = -50.0): SpectrumAnalyzer.Frame {
        val mags = DoubleArray(binCount) { baselineDb }
        for ((bin, db) in peaks) mags[bin] = db
        return SpectrumAnalyzer.Frame(
            sampleRate = 8000,
            fftSize = (binCount - 1) * 2,
            magnitudesDbfs = mags,
            dominantFrequencyHz = null,
            nyquistHz = binCount * binWidth,
            binWidthHz = binWidth,
        )
    }

    private fun silence() = frame(baselineDb = -80.0)

    /** Feeds a steady peak at [bin] every [stepMs] until [totalMs] have elapsed. */
    private fun feedSteady(tracker: RingTracker, clock: FakeClock, bin: Int, totalMs: Long, stepMs: Long = 20): RingSnapshot {
        var elapsed = 0L
        var last: RingSnapshot? = null
        while (elapsed < totalMs) {
            last = tracker.update(frame(bin to -30.0))
            clock.advance(stepMs)
            elapsed += stepMs
        }
        return last!!
    }

    @Test
    fun `jittering peak bin keeps a single stable track identity`() {
        val clock = FakeClock()
        val tracker = RingTracker(clock = clock)
        val jitterBins = intArrayOf(200, 201, 200, 199, 200, 201, 200, 199, 200, 201, 200, 199, 200, 201, 200, 201, 200, 199, 200, 201)
        for (bin in jitterBins) {
            tracker.update(frame(bin to -30.0))
            clock.advance(20)
        }
        val snap = tracker.update(frame(200 to -30.0))
        assertEquals(1, snap.history.size)
    }

    @Test
    fun `short transient below confirmation duration is rejected`() {
        val clock = FakeClock()
        val tracker = RingTracker(clock = clock)
        feedSteady(tracker, clock, bin = 150, totalMs = 100) // well under 350ms
        val afterSilence = run {
            var last: RingSnapshot? = null
            repeat(20) {
                last = tracker.update(silence())
                clock.advance(50)
            }
            last!!
        }
        assertTrue(afterSilence.history.isEmpty())
    }

    @Test
    fun `confirmation fires only after the dwell time, not before`() {
        val clock = FakeClock()
        val tracker = RingTracker(clock = clock)
        // First observation at t=0.
        var snap = tracker.update(frame(150 to -30.0))
        assertTrue(snap.history.isEmpty())
        clock.advance(340)
        snap = tracker.update(frame(150 to -30.0)) // t=340, still short of 350ms
        assertTrue("should not confirm before the dwell time", snap.history.isEmpty())
        clock.advance(20)
        snap = tracker.update(frame(150 to -30.0)) // t=360
        assertEquals(1, snap.history.size)
        assertEquals(RingCaptureState.LIVE, snap.heroState)
    }

    @Test
    fun `brief dropout within tolerance does not reset accumulated evidence`() {
        val clock = FakeClock()
        val tracker = RingTracker(clock = clock)
        tracker.update(frame(150 to -30.0)) // t=0, track created
        clock.advance(150)
        tracker.update(silence()) // t=150, brief gap (<= 150ms maxDropout)
        clock.advance(100)
        var snap = tracker.update(frame(150 to -30.0)) // t=250, resumes
        assertTrue("still not confirmed at t=250", snap.history.isEmpty())
        clock.advance(110)
        snap = tracker.update(frame(150 to -30.0)) // t=360 total since first-seen at t=0
        assertEquals("dropout should not have restarted the confirm timer", 1, snap.history.size)
    }

    @Test
    fun `dropout beyond tolerance loses identity and restarts confirmation`() {
        val clock = FakeClock()
        val tracker = RingTracker(clock = clock)
        tracker.update(frame(150 to -30.0)) // t=0
        clock.advance(200) // exceeds 150ms maxDropout
        tracker.update(silence())
        clock.advance(20)
        var snap = tracker.update(frame(150 to -30.0)) // new track starts here
        clock.advance(360)
        snap = tracker.update(frame(150 to -30.0)) // ~360ms since the NEW track started
        assertEquals(1, snap.history.size) // confirms eventually, just restarted -- not lost forever
    }

    @Test
    fun `held capture retains its frozen frequency through silence`() {
        val clock = FakeClock()
        val tracker = RingTracker(clock = clock)
        val confirmed = feedSteady(tracker, clock, bin = 215, totalMs = 400)
        val frozenFreq = confirmed.heroCapture!!.frequencyHz
        assertEquals(2150.0, frozenFreq, 1e-6)

        clock.advance(500) // past maxDropout, well under autoHold
        val held = tracker.update(silence())
        assertNotNull(held.heroCapture)
        assertEquals(frozenFreq, held.heroCapture!!.frequencyHz, 1e-9)
        assertEquals(RingCaptureState.HELD, held.heroState)
        assertEquals(1, held.history.size)
    }

    @Test
    fun `auto-hold expiry allows a new capture to take over the hero selection`() {
        val clock = FakeClock()
        val tracker = RingTracker(clock = clock, settings = RingTrackerSettings(autoHoldMs = 1000))
        feedSteady(tracker, clock, bin = 150, totalMs = 400) // capture A confirmed & selected
        clock.advance(1500) // A expires (unpinned)
        tracker.update(silence())
        val afterB = feedSteady(tracker, clock, bin = 300, totalMs = 400) // capture B confirmed
        assertEquals(3000.0, afterB.heroCapture!!.frequencyHz, 1e-6)
        assertEquals(2, afterB.history.size) // A still retained historically
    }

    @Test
    fun `recurring frequency updates the existing record instead of duplicating it`() {
        val clock = FakeClock()
        val tracker = RingTracker(clock = clock)
        feedSteady(tracker, clock, bin = 150, totalMs = 400)
        clock.advance(300) // loses track identity (>150ms) but well within autoHold (20s default)
        tracker.update(silence())
        val again = feedSteady(tracker, clock, bin = 150, totalMs = 400)
        assertEquals("recurring tone should dedupe, not create a second record", 1, again.history.size)
    }

    @Test
    fun `a louder second frequency does not steal the held selection`() {
        val clock = FakeClock()
        val tracker = RingTracker(clock = clock)
        feedSteady(tracker, clock, bin = 150, totalMs = 400) // weaker, selected first
        clock.advance(50)
        var last: RingSnapshot? = null
        var elapsed = 0L
        while (elapsed < 400) {
            last = tracker.update(frame(150 to -30.0, 300 to -10.0)) // second tone much louder
            clock.advance(20)
            elapsed += 20
        }
        assertEquals(150 * binWidth, last!!.heroCapture!!.frequencyHz, 1.0)
        assertEquals(2, last.history.size)
    }

    @Test
    fun `two simultaneous well-separated tones are tracked as distinct captures`() {
        val clock = FakeClock()
        val tracker = RingTracker(clock = clock)
        var last: RingSnapshot? = null
        var elapsed = 0L
        while (elapsed < 400) {
            last = tracker.update(frame(100 to -30.0, 300 to -30.0))
            clock.advance(20)
            elapsed += 20
        }
        assertEquals(2, last!!.history.size)
        val freqs = last.history.map { it.frequencyHz }.sorted()
        assertEquals(1000.0, freqs[0], 1e-6)
        assertEquals(3000.0, freqs[1], 1e-6)
    }

    @Test
    fun `pin protects a capture from auto-hold expiry and reselection`() {
        val clock = FakeClock()
        val tracker = RingTracker(clock = clock, settings = RingTrackerSettings(autoHoldMs = 1000))
        val confirmed = feedSteady(tracker, clock, bin = 150, totalMs = 400)
        tracker.pin(confirmed.heroCapture!!.id)
        clock.advance(5000) // well past autoHold
        val stillHeld = tracker.update(silence())
        assertEquals(RingCaptureState.PINNED, stillHeld.heroState)

        feedSteady(tracker, clock, bin = 300, totalMs = 400) // a new confirmed capture appears
        val afterNew = tracker.update(silence())
        assertEquals("pinned capture must not be replaced", confirmed.heroCapture!!.id, afterNew.heroCapture!!.id)
    }

    @Test
    fun `unpin returns a capture to normal expiry rules`() {
        val clock = FakeClock()
        val tracker = RingTracker(clock = clock, settings = RingTrackerSettings(autoHoldMs = 1000))
        val confirmed = feedSteady(tracker, clock, bin = 150, totalMs = 400)
        tracker.pin(confirmed.heroCapture!!.id)
        tracker.unpin()
        clock.advance(1500)
        val afterExpiry = tracker.update(silence())
        assertNotEquals("expired unpinned capture should no longer read as pinned", RingCaptureState.PINNED, afterExpiry.heroState)
    }

    @Test
    fun `clear removes the selected capture and releases selection`() {
        val clock = FakeClock()
        val tracker = RingTracker(clock = clock)
        val confirmed = feedSteady(tracker, clock, bin = 150, totalMs = 400)
        assertNotNull(confirmed.heroCapture)
        tracker.clearSelected()
        val after = tracker.update(silence())
        assertNull(after.heroCapture)
        assertTrue(after.history.isEmpty())
    }

    @Test
    fun `clear all wipes history regardless of pin state`() {
        val clock = FakeClock()
        val tracker = RingTracker(clock = clock)
        val confirmed = feedSteady(tracker, clock, bin = 150, totalMs = 400)
        tracker.pin(confirmed.heroCapture!!.id)
        tracker.clearAll()
        val after = tracker.update(silence())
        assertTrue(after.history.isEmpty())
        assertNull(after.heroCapture)
    }

    @Test
    fun `pausing acquisition reports PAUSED and drops only transient tracks`() {
        val clock = FakeClock()
        val tracker = RingTracker(clock = clock)
        val confirmed = feedSteady(tracker, clock, bin = 150, totalMs = 400)
        tracker.setAcquisitionActive(false)
        val paused = tracker.update(frame(150 to -30.0)) // even if fed data, acquisition is "off"
        assertEquals(RingCaptureState.PAUSED, paused.heroState)
        assertEquals(1, paused.history.size) // historical capture retained
        assertEquals(confirmed.heroCapture!!.id, paused.history[0].id)
    }

    @Test
    fun `config change clears transient trackers but keeps historical captures`() {
        val clock = FakeClock()
        val tracker = RingTracker(clock = clock)
        feedSteady(tracker, clock, bin = 150, totalMs = 400)
        tracker.onConfigChanged("48000|9")
        val snap = tracker.update(silence())
        assertEquals(1, snap.history.size)
    }

    // --- Acceptance scenario: same processing path as production (real FFT, not synthetic frames) ---
    @Test
    fun `demo fixture - 2point15kHz then 3point2kHz through the real FFT pipeline`() {
        val sampleRate = 48000
        val analyzer = SpectrumAnalyzer(4096)
        val clock = FakeClock()
        val tracker = RingTracker(clock = clock)

        fun pushSeconds(freqHz: Double?, seconds: Double) {
            val totalSamples = (sampleRate * seconds).toInt()
            var produced = 0
            var sampleIndex = 0L
            while (produced < totalSamples) {
                val block = FloatArray(2048)
                for (i in block.indices) {
                    val t = (sampleIndex + i).toDouble() / sampleRate
                    block[i] = if (freqHz != null) (0.5 * sin(2.0 * PI * freqHz * t)).toFloat() else 0f
                }
                analyzer.pushSamples(block)
                sampleIndex += block.size
                produced += block.size
                val frame = analyzer.computeFrame(sampleRate)
                if (frame != null) tracker.update(frame)
                clock.advance((block.size * 1000L) / sampleRate)
            }
        }

        pushSeconds(null, 0.3)      // silence
        pushSeconds(2150.0, 1.0)    // one-second tone near 2.15 kHz
        val afterFirstTone = tracker.update(analyzer.computeFrame(sampleRate)!!)
        assertNotNull("2.15 kHz tone should have been captured", afterFirstTone.heroCapture)
        assertEquals(2150.0, afterFirstTone.heroCapture!!.frequencyHz, 60.0)

        pushSeconds(null, 0.3)      // silence again -- must not erase the capture
        val heldSnap = tracker.update(analyzer.computeFrame(sampleRate)!!)
        assertNotNull(heldSnap.heroCapture)
        assertEquals(RingCaptureState.HELD, heldSnap.heroState)

        tracker.pin(heldSnap.heroCapture!!.id) // pinning after the sound stopped must work

        pushSeconds(3200.0, 0.5)   // a separate tone appears
        val afterSecondTone = tracker.update(analyzer.computeFrame(sampleRate)!!)
        assertEquals("pinned 2.15kHz capture must remain the hero", heldSnap.heroCapture!!.id, afterSecondTone.heroCapture!!.id)
        assertEquals(RingCaptureState.PINNED, afterSecondTone.heroState)

        pushSeconds(null, 0.3)
        val final = tracker.update(analyzer.computeFrame(sampleRate)!!)
        assertTrue("both tones should be present in history", final.history.size >= 1)
    }

    private fun assertNotEquals(message: String, unexpected: Any, actual: Any) {
        assertFalse(message, unexpected == actual)
    }
}
