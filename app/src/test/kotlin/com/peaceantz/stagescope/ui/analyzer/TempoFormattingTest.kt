package com.peaceantz.stagescope.ui.analyzer

import com.peaceantz.stagescope.dsp.TapTempo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TempoFormattingTest {

    private val estimate = TapTempo.Estimate(bpm = 119.62, beatMillis = 501.6, beatCount = 9)

    @Test
    fun `nothing is added to the clock until there is a tempo or a pinch would count`() {
        assertNull(TempoFormatting.timeTextLabel(TapTempoState()))
    }

    @Test
    fun `a placeholder shows while a double pinch would count`() {
        assertEquals("-- BPM", TempoFormatting.timeTextLabel(TapTempoState(gestureArmed = true)))
    }

    @Test
    fun `the clock shows whole BPM, whether or not the gesture is armed`() {
        assertEquals("120 BPM", TempoFormatting.timeTextLabel(TapTempoState(estimate = estimate, gestureArmed = true)))
        assertEquals("120 BPM", TempoFormatting.timeTextLabel(TapTempoState(estimate = estimate)))
    }

    @Test
    fun `details show a decimal, the beat length and how many taps it rests on`() {
        assertEquals("119.6 BPM", TempoFormatting.bpm(estimate))
        assertEquals("502 ms/beat · 9 taps", TempoFormatting.detail(estimate))
    }
}
