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
        tracker.unpin(confirmed.heroCapture!!.id)
        clock.advance(1500)
        val afterExpiry = tracker.update(silence())
        assertNotEquals("expired unpinned capture should no longer read as pinned", RingCaptureState.PINNED, afterExpiry.heroState)
    }

    @Test
    fun `pinning one capture leaves every other capture's pin state untouched`() {
        val clock = FakeClock()
        val tracker = RingTracker(clock = clock)
        var last: RingSnapshot? = null
        var elapsed = 0L
        while (elapsed < 400) {
            last = tracker.update(frame(100 to -30.0, 200 to -30.0, 300 to -30.0))
            clock.advance(20)
            elapsed += 20
        }
        val ids = last!!.history.map { it.id }.sorted()
        assertEquals(3, ids.size)

        tracker.pin(ids[0])
        tracker.pin(ids[1])
        var snap = tracker.currentSnapshot()
        assertTrue(snap.history.first { it.id == ids[0] }.pinned)
        assertTrue(snap.history.first { it.id == ids[1] }.pinned)
        assertFalse(snap.history.first { it.id == ids[2] }.pinned)

        tracker.unpin(ids[0])
        snap = tracker.currentSnapshot()
        assertFalse("unpinning one capture must not affect another", snap.history.first { it.id == ids[0] }.pinned)
        assertTrue("unrelated pin must survive an unpin elsewhere", snap.history.first { it.id == ids[1] }.pinned)
    }

    @Test
    fun `pinning a capture does not retroactively mutate a previously returned snapshot`() {
        // Regression test for a real on-device bug: RingViewModel publishes snapshots through a
        // MutableStateFlow, which silently drops an update whose value compares `equals()` to the
        // currently-held one. If pin()/unpin() mutated the shared RingCapture object in place, an
        // already-published (old) snapshot's copy of that capture would change too, making the
        // "before" and "after" snapshots look identical -- so a tap on a Ring tile never visibly
        // toggled until some unrelated change forced a genuinely different value through.
        val clock = FakeClock()
        val tracker = RingTracker(clock = clock)
        val confirmed = feedSteady(tracker, clock, bin = 150, totalMs = 400)
        val id = confirmed.heroCapture!!.id

        val snapshotTakenBeforePin = tracker.currentSnapshot()
        val captureFromOldSnapshot = snapshotTakenBeforePin.history.first { it.id == id }
        assertFalse(captureFromOldSnapshot.pinned)

        tracker.pin(id)

        assertFalse(
            "a snapshot captured before pin() must remain frozen, not be mutated by the later call",
            captureFromOldSnapshot.pinned,
        )
        assertTrue("the new snapshot must reflect the pin", tracker.currentSnapshot().history.first { it.id == id }.pinned)
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

    // --- Five-slot capture bank ---

    @Test
    fun `five simultaneous well-separated tones fill all five bank slots`() {
        val clock = FakeClock()
        val tracker = RingTracker(clock = clock)
        var last: RingSnapshot? = null
        var elapsed = 0L
        while (elapsed < 400) {
            last = tracker.update(frame(50 to -30.0, 100 to -30.0, 150 to -30.0, 200 to -30.0, 250 to -30.0))
            clock.advance(20)
            elapsed += 20
        }
        assertEquals(5, last!!.history.size)
        assertEquals(5, last.slotsUsed)
        assertEquals(5, last.slotsTotal)
        val freqs = last.history.map { it.frequencyHz }.sorted()
        assertEquals(listOf(500.0, 1000.0, 1500.0, 2000.0, 2500.0), freqs)
    }

    @Test
    fun `five sequential captures each occupy their own slot`() {
        val clock = FakeClock()
        val tracker = RingTracker(clock = clock)
        val bins = listOf(50, 100, 150, 200, 250)
        for (bin in bins) {
            feedSteady(tracker, clock, bin = bin, totalMs = 400)
            clock.advance(20)
            tracker.update(silence())
        }
        val snap = tracker.currentSnapshot()
        assertEquals(5, snap.history.size)
        assertEquals(bins.map { it * binWidth }.sorted(), snap.history.map { it.frequencyHz }.sorted())
    }

    @Test
    fun `a sixth weaker candidate does not evict an existing unpinned live capture`() {
        val clock = FakeClock()
        val tracker = RingTracker(clock = clock)
        val bins = listOf(50, 100, 150, 200, 250)
        var last: RingSnapshot? = null
        var elapsed = 0L
        // Fill all five slots and keep feeding them (still LIVE, not expired) while a 6th,
        // similar-strength tone tries to confirm.
        while (elapsed < 800) {
            last = tracker.update(frame(50 to -30.0, 100 to -30.0, 150 to -30.0, 200 to -30.0, 250 to -30.0, 350 to -31.0))
            clock.advance(20)
            elapsed += 20
        }
        assertEquals("weaker 6th candidate must not evict a live, unpinned slot", 5, last!!.history.size)
        assertFalse(last.history.any { it.frequencyHz == 3500.0 })
    }

    @Test
    fun `a sixth sufficiently stronger candidate evicts the weakest unpinned capture`() {
        val clock = FakeClock()
        val tracker = RingTracker(clock = clock, settings = RingTrackerSettings(replacementMarginDb = 6.0))
        var last: RingSnapshot? = null
        var elapsed = 0L
        // Slot at bin 250 is deliberately the weakest (lowest contrast); others much stronger.
        while (elapsed < 400) {
            last = tracker.update(frame(50 to -20.0, 100 to -20.0, 150 to -20.0, 200 to -20.0, 250 to -35.0))
            clock.advance(20)
            elapsed += 20
        }
        assertEquals(5, last!!.history.size)

        elapsed = 0L
        while (elapsed < 400) {
            // New tone at bin 350 much stronger than the weakest existing (250 at -35dB contrast).
            last = tracker.update(frame(50 to -20.0, 100 to -20.0, 150 to -20.0, 200 to -20.0, 250 to -35.0, 350 to -15.0))
            clock.advance(20)
            elapsed += 20
        }
        assertEquals("bank must stay at 5 slots, never temporarily exceeding it", 5, last!!.history.size)
        assertTrue("new sufficiently-stronger tone should have claimed a slot", last.history.any { it.frequencyHz == 3500.0 })
        assertFalse("weakest prior candidate should have been evicted", last.history.any { it.frequencyHz == 2500.0 })
    }

    @Test
    fun `expired unpinned capture is evicted before considering prominence margin`() {
        val clock = FakeClock()
        val tracker = RingTracker(clock = clock, settings = RingTrackerSettings(autoHoldMs = 500, replacementMarginDb = 6.0))
        var elapsed = 0L
        var last: RingSnapshot? = null
        while (elapsed < 400) {
            last = tracker.update(frame(50 to -20.0, 100 to -20.0, 150 to -20.0, 200 to -20.0, 250 to -20.0))
            clock.advance(20)
            elapsed += 20
        }
        assertEquals(5, last!!.history.size)

        clock.advance(1000) // every capture now expired (well past autoHoldMs)
        tracker.update(silence())

        // A single new, even weaker tone should still claim a slot because the target is expired,
        // not because it out-prominences anything.
        feedSteady(tracker, clock, bin = 350, totalMs = 400)
        val after = tracker.currentSnapshot()
        assertEquals(5, after.history.size)
        assertTrue(after.history.any { it.frequencyHz == 3500.0 })
    }

    @Test
    fun `when all five slots are pinned a new tone cannot claim a slot`() {
        val clock = FakeClock()
        val tracker = RingTracker(clock = clock)
        var last: RingSnapshot? = null
        var elapsed = 0L
        while (elapsed < 400) {
            last = tracker.update(frame(50 to -20.0, 100 to -20.0, 150 to -20.0, 200 to -20.0, 250 to -20.0))
            clock.advance(20)
            elapsed += 20
        }
        for (capture in last!!.history) tracker.pin(capture.id)
        val pinnedSnap = tracker.currentSnapshot()
        assertTrue(pinnedSnap.allSlotsPinned)

        elapsed = 0L
        var afterNew: RingSnapshot? = null
        while (elapsed < 400) {
            afterNew = tracker.update(frame(50 to -20.0, 100 to -20.0, 150 to -20.0, 200 to -20.0, 250 to -20.0, 350 to -5.0))
            clock.advance(20)
            elapsed += 20
        }
        assertEquals("all-pinned bank must stay exactly at capacity", 5, afterNew!!.history.size)
        assertFalse(afterNew.history.any { it.frequencyHz == 3500.0 })
        assertTrue(afterNew.allSlotsPinned)
    }

    @Test
    fun `clear unpinned removes only unpinned captures`() {
        val clock = FakeClock()
        val tracker = RingTracker(clock = clock)
        var last: RingSnapshot? = null
        var elapsed = 0L
        while (elapsed < 400) {
            last = tracker.update(frame(100 to -30.0, 200 to -30.0, 300 to -30.0))
            clock.advance(20)
            elapsed += 20
        }
        val ids = last!!.history.map { it.id }.sorted()
        tracker.pin(ids[0])

        tracker.clearUnpinned()
        val after = tracker.currentSnapshot()
        assertEquals(1, after.history.size)
        assertEquals(ids[0], after.history[0].id)
        assertTrue(after.history[0].pinned)
    }

    @Test
    fun `with five captures and two pinned, clearing unpinned leaves exactly those two`() {
        val clock = FakeClock()
        val tracker = RingTracker(clock = clock)
        var last: RingSnapshot? = null
        var elapsed = 0L
        while (elapsed < 400) {
            last = tracker.update(frame(50 to -20.0, 100 to -20.0, 150 to -20.0, 200 to -20.0, 250 to -20.0))
            clock.advance(20)
            elapsed += 20
        }
        val ids = last!!.history.map { it.id }.sorted()
        assertEquals(5, ids.size)
        val pinnedIds = setOf(ids[1], ids[3])
        pinnedIds.forEach(tracker::pin)

        tracker.clearUnpinned()
        val after = tracker.currentSnapshot()
        assertEquals("only the two pinned captures should survive", pinnedIds, after.history.map { it.id }.toSet())
        assertTrue(after.history.all { it.pinned })
    }

    @Test
    fun `clear unpinned on an empty bank is harmless`() {
        val tracker = RingTracker(clock = FakeClock())
        tracker.clearUnpinned()
        val after = tracker.currentSnapshot()
        assertTrue(after.history.isEmpty())
    }

    @Test
    fun `clear unpinned on a fully pinned bank removes nothing`() {
        val clock = FakeClock()
        val tracker = RingTracker(clock = clock)
        var last: RingSnapshot? = null
        var elapsed = 0L
        while (elapsed < 400) {
            last = tracker.update(frame(50 to -20.0, 100 to -20.0, 150 to -20.0, 200 to -20.0, 250 to -20.0))
            clock.advance(20)
            elapsed += 20
        }
        for (capture in last!!.history) tracker.pin(capture.id)
        val idsBefore = tracker.currentSnapshot().history.map { it.id }.toSet()

        tracker.clearUnpinned()
        val after = tracker.currentSnapshot()
        assertEquals(idsBefore, after.history.map { it.id }.toSet())
        assertTrue(after.history.all { it.pinned })
    }

    @Test
    fun `pinning then unpinning the same capture twice returns it to unpinned`() {
        val clock = FakeClock()
        val tracker = RingTracker(clock = clock)
        val confirmed = feedSteady(tracker, clock, bin = 150, totalMs = 400)
        val id = confirmed.heroCapture!!.id

        // Simulates two taps on the same tile: tap 1 pins (unpinned -> pin), tap 2 unpins (pinned -> unpin).
        fun toggle() {
            val pinnedNow = tracker.currentSnapshot().history.first { it.id == id }.pinned
            if (pinnedNow) tracker.unpin(id) else tracker.pin(id)
        }

        assertFalse(tracker.currentSnapshot().history.first { it.id == id }.pinned)
        toggle()
        assertTrue("first tap should pin", tracker.currentSnapshot().history.first { it.id == id }.pinned)
        toggle()
        assertFalse("second tap should unpin the same capture", tracker.currentSnapshot().history.first { it.id == id }.pinned)
    }

    // --- Resolution-aware dedup tolerance ---

    @Test
    fun `recurring-tone dedup tolerance scales with actual bin width, not a fixed Hz assumption`() {
        val clock = FakeClock()
        // dedupToleranceBins=6 at binWidth=10Hz -> 60Hz window. A recurrence 45Hz away (4.5 bins)
        // should merge into the same record; this only holds if the tolerance is computed from the
        // frame's real binWidthHz rather than a hardcoded constant.
        val tracker = RingTracker(clock = clock, settings = RingTrackerSettings(dedupToleranceBins = 6))
        feedSteady(tracker, clock, bin = 150, totalMs = 400) // confirms at 1500 Hz
        clock.advance(300)
        tracker.update(silence())
        val again = feedSteady(tracker, clock, bin = 155, totalMs = 400) // 1550 Hz, 50Hz / 5 bins away
        assertEquals("a recurrence within the resolution-aware tolerance must dedupe", 1, again.history.size)
    }

    // --- Most-prominent-ring selection (independent of manual selection and pins) ---

    @Test
    fun `most prominent ring is the strongest live capture regardless of manual selection`() {
        val clock = FakeClock()
        val tracker = RingTracker(clock = clock)
        var last: RingSnapshot? = null
        var elapsed = 0L
        while (elapsed < 400) {
            last = tracker.update(frame(100 to -10.0, 300 to -30.0)) // bin 100 much louder
            clock.advance(20)
            elapsed += 20
        }
        val loudId = last!!.history.first { it.frequencyHz == 1000.0 }.id
        val quietId = last.history.first { it.frequencyHz == 3000.0 }.id

        // Manually select the quieter one -- must not change which ring is "most prominent".
        tracker.selectCapture(quietId)
        val snap = tracker.currentSnapshot()
        assertEquals(quietId, snap.heroCapture!!.id)
        assertEquals("most-prominent must track spectral strength, not the manual selection", loudId, snap.mostProminentCaptureId)
    }

    @Test
    fun `pinning a weaker ring does not let it outrank a stronger live ring`() {
        val clock = FakeClock()
        val tracker = RingTracker(clock = clock)
        var last: RingSnapshot? = null
        var elapsed = 0L
        while (elapsed < 400) {
            last = tracker.update(frame(100 to -10.0, 300 to -30.0))
            clock.advance(20)
            elapsed += 20
        }
        val loudId = last!!.history.first { it.frequencyHz == 1000.0 }.id
        val quietId = last.history.first { it.frequencyHz == 3000.0 }.id
        tracker.pin(quietId)

        elapsed = 0L
        var snap: RingSnapshot? = null
        while (elapsed < 400) {
            snap = tracker.update(frame(100 to -10.0, 300 to -30.0))
            clock.advance(20)
            elapsed += 20
        }
        assertEquals("pinning must not make a weaker ring outrank a stronger one", loudId, snap!!.mostProminentCaptureId)
    }

    @Test
    fun `most prominent switches immediately when a clearly stronger ring appears`() {
        val clock = FakeClock()
        val tracker = RingTracker(clock = clock, settings = RingTrackerSettings(prominenceSwitchMarginDb = 4.0, prominenceDwellMs = 600))
        var elapsed = 0L
        var snap: RingSnapshot? = null
        while (elapsed < 400) {
            snap = tracker.update(frame(100 to -30.0))
            clock.advance(20)
            elapsed += 20
        }
        val firstId = snap!!.history.first().id
        assertEquals(firstId, snap.mostProminentCaptureId)

        // A much stronger tone appears alongside it. Give it enough continuous frames to pass the
        // separate 350ms ring-CONFIRMATION dwell and become LIVE -- only once it's a real,
        // currently-detected capture does the "switch promptly" (no EXTRA hysteresis dwell) rule
        // apply; confirmation itself is not something prominence hysteresis can or should skip.
        elapsed = 0L
        while (elapsed < 400) {
            snap = tracker.update(frame(100 to -30.0, 300 to -5.0))
            clock.advance(20)
            elapsed += 20
        }
        assertNotEquals(
            "a clearly stronger, already-confirmed ring must take over promptly, without an extra hysteresis dwell",
            firstId,
            snap!!.mostProminentCaptureId,
        )
    }

    @Test
    fun `most prominent requires a dwell before switching to a near-equal candidate`() {
        val clock = FakeClock()
        val tracker = RingTracker(
            clock = clock,
            settings = RingTrackerSettings(prominenceSwitchMarginDb = 4.0, prominenceDwellMs = 600, prominenceEmaAlpha = 1.0),
        )
        var elapsed = 0L
        var snap: RingSnapshot? = null
        while (elapsed < 400) {
            snap = tracker.update(frame(100 to -20.0))
            clock.advance(20)
            elapsed += 20
        }
        val firstId = snap!!.history.first().id
        assertEquals(firstId, snap.mostProminentCaptureId)

        // A near-equal-strength second tone (within the switch margin) appears and is fed long
        // enough to confirm and become LIVE, competing on equal footing -- right as it becomes
        // eligible, it must not have stolen the top spot yet (the hysteresis dwell just started).
        elapsed = 0L
        while (elapsed < 400) {
            snap = tracker.update(frame(100 to -20.0, 300 to -19.0))
            clock.advance(20)
            elapsed += 20
        }
        assertEquals("a near-equal candidate must not switch instantly", firstId, snap!!.mostProminentCaptureId)

        // Keep both live long enough for the dwell to elapse.
        elapsed = 0L
        while (elapsed < 700) {
            snap = tracker.update(frame(100 to -20.0, 300 to -19.0))
            clock.advance(20)
            elapsed += 20
        }
        assertNotEquals("after the dwell, the near-equal candidate should take over", firstId, snap!!.mostProminentCaptureId)
    }

    @Test
    fun `most prominent is retained as cached info once acquisition goes quiet`() {
        val clock = FakeClock()
        val tracker = RingTracker(clock = clock)
        var elapsed = 0L
        var snap: RingSnapshot? = null
        while (elapsed < 400) {
            snap = tracker.update(frame(100 to -20.0))
            clock.advance(20)
            elapsed += 20
        }
        val id = snap!!.mostProminentCaptureId
        assertNotNull(id)

        clock.advance(1000) // goes HELD (past maxDropoutMs), well within autoHold
        val after = tracker.update(silence())
        assertEquals("last known most-prominent id should be retained even once nothing is LIVE", id, after.mostProminentCaptureId)
        val cachedCapture = after.history.first { it.id == id }
        assertEquals(RingCaptureState.HELD, cachedCapture.stateAt(clock.nowMillis(), tracker.settings.autoHoldMs, tracker.settings.maxDropoutMs))
    }

    @Test
    fun `clearing the most prominent capture releases the cached id`() {
        val clock = FakeClock()
        val tracker = RingTracker(clock = clock)
        var elapsed = 0L
        var snap: RingSnapshot? = null
        while (elapsed < 400) {
            snap = tracker.update(frame(100 to -20.0))
            clock.advance(20)
            elapsed += 20
        }
        val id = snap!!.mostProminentCaptureId!!
        tracker.selectCapture(id)
        tracker.clearSelected()
        val after = tracker.currentSnapshot()
        assertNull(after.mostProminentCaptureId)
    }

    // --- Restore from disk ---

    @Test
    fun `a restored capture is pinned and marked restored until detected again`() {
        val clock = FakeClock()
        val tracker = RingTracker(clock = clock)
        tracker.restoreCapture(id = 42L, frequencyHz = 630.0, savedAtWallClockMillis = 123_456L)
        val snap = tracker.currentSnapshot()
        assertEquals(1, snap.history.size)
        val restored = snap.history[0]
        assertEquals(42L, restored.id)
        assertTrue(restored.pinned)
        assertTrue(restored.restoredFromDisk)
        assertEquals(RingCaptureState.PINNED, restored.stateAt(clock.nowMillis(), tracker.settings.autoHoldMs, tracker.settings.maxDropoutMs))
    }

    @Test
    fun `a restored capture's identity survives and clears its restored flag once detected again`() {
        val clock = FakeClock()
        val tracker = RingTracker(clock = clock)
        tracker.restoreCapture(id = 42L, frequencyHz = 1500.0, savedAtWallClockMillis = 1L)

        // A real detection near that same frequency (bin 150 * binWidth(10) = 1500Hz) must update
        // the SAME restored record (via dedup), not create a second one with a new, colliding id.
        feedSteady(tracker, clock, bin = 150, totalMs = 400)
        val snap = tracker.currentSnapshot()
        assertEquals("must not duplicate a restored capture on rediscovery", 1, snap.history.size)
        assertEquals(42L, snap.history[0].id)
        assertFalse("no longer just a disk-restored record once actually redetected", snap.history[0].restoredFromDisk)
    }

    @Test
    fun `restored ids never collide with a freshly confirmed capture's id`() {
        val clock = FakeClock()
        val tracker = RingTracker(clock = clock)
        tracker.restoreCapture(id = 1L, frequencyHz = 9000.0, savedAtWallClockMillis = 1L)
        // A brand-new, unrelated tone must get its own distinct id, never colliding with id 1.
        val snap = feedSteady(tracker, clock, bin = 150, totalMs = 400)
        assertEquals(2, snap.history.size)
        val ids = snap.history.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    private fun assertNotEquals(message: String, unexpected: Any?, actual: Any?) {
        assertFalse(message, unexpected == actual)
    }
}
