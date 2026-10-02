package com.peaceantz.stagescope.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TapTempoTest {

    private fun TapTempo.tapAll(vararg times: Long) = times.forEach { tap(it) }

    private fun TapTempo.bpm(): Double = requireNotNull(estimate) { "no tempo yet" }.bpm

    /** [count] taps [beatMillis] apart, starting at [startMillis]. */
    private fun grid(count: Int, beatMillis: Long, startMillis: Long = 0L): LongArray =
        LongArray(count) { startMillis + it * beatMillis }

    @Test
    fun `one tap gives no tempo, two taps give one`() {
        val tempo = TapTempo()
        tempo.tap(1_000L)
        assertNull(tempo.estimate)
        tempo.tap(1_500L)
        assertEquals(120.0, tempo.bpm(), 1e-9)
        assertEquals(500.0, tempo.estimate!!.beatMillis, 1e-9)
        assertEquals(2, tempo.estimate!!.beatCount)
    }

    @Test
    fun `steady taps read the exact tempo and count every tap`() {
        val tempo = TapTempo()
        tempo.tapAll(*grid(12, 500L))
        assertEquals(120.0, tempo.bpm(), 1e-9)
        assertEquals(12, tempo.estimate!!.beatCount)
    }

    @Test
    fun `jittery taps still land close to the true tempo`() {
        // Up to +-40 ms on every beat -- the sort of spread a hand gesture recognizer adds.
        val jitter = longArrayOf(0, 30, -25, 40, -35, 10, -20, 35, -40, 15, -10, 25, -30, 5, -15, 20)
        val tempo = TapTempo()
        jitter.forEachIndexed { i, offset -> tempo.tap(10_000L + i * 500L + offset) }
        // The least-squares fit lands at 120.08; first-to-last averaging of the same taps would say 119.68.
        assertEquals(120.0, tempo.bpm(), 0.15)
        assertEquals(16, tempo.estimate!!.beatCount)
    }

    @Test
    fun `only the most recent window of taps is fitted`() {
        val tempo = TapTempo(TapTempo.Settings(window = 8))
        tempo.tapAll(*grid(20, 500L))
        assertEquals(8, tempo.estimate!!.beatCount)
    }

    @Test
    fun `a missed beat is bridged, not read as a slower tempo`() {
        val tempo = TapTempo()
        tempo.tapAll(0, 500, 1_000) // 1 500 was missed
        tempo.tap(2_000)
        assertEquals(120.0, tempo.bpm(), 1e-9)
        tempo.tapAll(2_500, 3_000)
        assertEquals(120.0, tempo.bpm(), 1e-9)
        assertEquals(6, tempo.estimate!!.beatCount)
    }

    @Test
    fun `two missed beats in a row break the chain instead of being guessed`() {
        val tempo = TapTempo()
        tempo.tapAll(*grid(5, 500L)) // 0..2 000
        tempo.tap(3_500) // 2 500 and 3 000 both missed
        assertEquals(120.0, tempo.bpm(), 1e-9)
        assertEquals(5, tempo.estimate!!.beatCount) // the earlier run still stands
        tempo.tap(4_000)
        assertEquals(120.0, tempo.bpm(), 1e-9)
        assertEquals(2, tempo.estimate!!.beatCount) // a fresh chain from the new taps only
    }

    @Test
    fun `a stray extra tap between beats is ignored`() {
        val tempo = TapTempo()
        tempo.tapAll(0, 500, 1_000)
        tempo.tap(1_250) // a phantom detection half a beat in
        assertEquals(120.0, tempo.bpm(), 1e-9)
        tempo.tapAll(1_500, 2_000)
        assertEquals(120.0, tempo.bpm(), 1e-9)
        assertEquals(5, tempo.estimate!!.beatCount)
    }

    @Test
    fun `one sloppy tap leaves the reading alone`() {
        val tempo = TapTempo()
        tempo.tapAll(*grid(8, 500L)) // 0..3 500
        tempo.tap(4_200) // 700 ms: way late
        assertEquals(120.0, tempo.bpm(), 1e-9)
        assertEquals(8, tempo.estimate!!.beatCount)
        tempo.tap(4_500) // back on the beat
        assertEquals(120.0, tempo.bpm(), 1e-9)
    }

    @Test
    fun `a real tempo change takes over after two consistent taps`() {
        val tempo = TapTempo()
        tempo.tapAll(*grid(8, 500L)) // 120 BPM, last tap at 3 500
        tempo.tap(4_250)
        assertEquals("one different interval could be a mistake", 120.0, tempo.bpm(), 1e-9)
        tempo.tap(5_000)
        assertEquals(80.0, tempo.bpm(), 1e-9)
        assertEquals(3, tempo.estimate!!.beatCount)
    }

    @Test
    fun `a run that starts with a missed beat is not stuck at half tempo`() {
        val tempo = TapTempo()
        tempo.tapAll(0, 1_000) // 500 was missed: reads 60 for now
        assertEquals(60.0, tempo.bpm(), 1e-9)
        tempo.tap(1_500)
        assertEquals(120.0, tempo.bpm(), 1e-9)
        tempo.tapAll(2_000, 2_500)
        assertEquals(120.0, tempo.bpm(), 1e-9)
    }

    @Test
    fun `a run that starts with a phantom tap is not stuck at double tempo`() {
        val tempo = TapTempo()
        tempo.tapAll(0, 250, 500) // 250 was a phantom: indistinguishable from 240 BPM so far
        tempo.tapAll(1_000, 1_500)
        assertEquals(120.0, tempo.bpm(), 1e-9)
        tempo.tap(2_000)
        assertEquals(120.0, tempo.bpm(), 1e-9)
    }

    @Test
    fun `a duplicate report of the same beat is ignored`() {
        val tempo = TapTempo()
        tempo.tapAll(0, 500)
        assertFalse(tempo.tap(560))
        assertEquals(120.0, tempo.bpm(), 1e-9)
        assertTrue(tempo.tap(1_000))
        assertEquals(120.0, tempo.bpm(), 1e-9)
        assertEquals(3, tempo.estimate!!.beatCount)
    }

    @Test
    fun `a tap stamped earlier than the previous one is ignored`() {
        val tempo = TapTempo()
        tempo.tapAll(1_000, 1_500)
        assertFalse(tempo.tap(1_400))
        assertEquals(120.0, tempo.bpm(), 1e-9)
    }

    @Test
    fun `after a pause the last tempo stays until two new taps replace it`() {
        val tempo = TapTempo()
        tempo.tapAll(*grid(8, 500L)) // 120 BPM
        tempo.tap(10_000)
        assertEquals(120.0, tempo.bpm(), 1e-9)
        tempo.tap(10_600)
        assertEquals(100.0, tempo.bpm(), 1e-9)
        assertEquals(2, tempo.estimate!!.beatCount)
    }

    @Test
    fun `slow tempos bridge a missed beat even past the two-second pause limit`() {
        val tempo = TapTempo()
        tempo.tapAll(*grid(4, 1_500L)) // 40 BPM, last tap at 4 500
        tempo.tap(7_500) // one missed: a 3 s gap
        assertEquals(40.0, tempo.bpm(), 1e-9)
        assertEquals(5, tempo.estimate!!.beatCount)
    }

    @Test
    fun `intervals longer than the slowest beat never make a tempo`() {
        val tempo = TapTempo()
        tempo.tapAll(0, 2_500, 5_000)
        assertNull(tempo.estimate)
    }

    @Test
    fun `clear forgets the taps and the tempo`() {
        val tempo = TapTempo()
        tempo.tapAll(*grid(6, 500L))
        tempo.clear()
        assertNull(tempo.estimate)
        tempo.tap(10_000)
        assertNull(tempo.estimate)
        tempo.tap(10_400)
        assertEquals(150.0, tempo.bpm(), 1e-9)
    }
}
