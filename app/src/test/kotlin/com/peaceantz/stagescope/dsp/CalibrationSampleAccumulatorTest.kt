package com.peaceantz.stagescope.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class CalibrationSampleAccumulatorTest {

    private fun sineBlock(amplitude: Float, size: Int = 480): FloatArray =
        FloatArray(size) { i -> (amplitude * sin(2.0 * PI * i / 48.0)).toFloat() }

    @Test
    fun `averages energy across blocks and converts to dB exactly once`() {
        // Two blocks of identical amplitude 0.5 -- energy-averaging them should land on the same
        // -9.03 dBFS invariant as a single block (LevelMeterTest's reference value), not something
        // skewed by averaging two already-converted dB readings.
        val acc = CalibrationSampleAccumulator()
        acc.accumulate(sineBlock(0.5f))
        acc.accumulate(sineBlock(0.5f))
        assertEquals(-9.03, acc.averagedRmsDbfs(), 0.05)
    }

    @Test
    fun `a single quiet block does not average away a louder block that preceded it`() {
        // If this were averaging per-block dB values (arithmetic mean of decibels) instead of
        // energy, the result would differ measurably from the energy-correct answer.
        val acc = CalibrationSampleAccumulator()
        acc.accumulate(sineBlock(1.0f)) // ~-3.01 dBFS block
        acc.accumulate(sineBlock(0.01f)) // ~-43 dBFS block, tiny energy contribution
        // Energy-correct: totalEnergy dominated by the loud block, so the average stays close to it,
        // not halfway between -3 and -43 (which naive dB-averaging would produce, ~-23 dBFS).
        assertTrue("energy averaging must not land near the naive dB-average midpoint", acc.averagedRmsDbfs() > -10.0)
    }

    @Test
    fun `an empty accumulator reports the floor, not NaN or a crash`() {
        val acc = CalibrationSampleAccumulator()
        assertEquals(DbScale.FLOOR_DBFS, acc.averagedRmsDbfs(), 1e-9)
        assertEquals(0.0, acc.blockSpreadDb(), 1e-9)
        assertFalse(acc.clipped)
    }

    @Test
    fun `clipping in any single block is flagged for the whole window`() {
        val acc = CalibrationSampleAccumulator()
        acc.accumulate(sineBlock(0.3f))
        assertFalse(acc.clipped)
        acc.accumulate(sineBlock(1.0f)) // full-scale sine clips at its peaks
        assertTrue(acc.clipped)
    }

    @Test
    fun `block spread reflects how much per-block level varied during the window`() {
        val acc = CalibrationSampleAccumulator()
        acc.accumulate(sineBlock(0.5f)) // steady
        acc.accumulate(sineBlock(0.5f))
        acc.accumulate(sineBlock(0.5f))
        assertEquals("a perfectly steady tone should show ~0 spread", 0.0, acc.blockSpreadDb(), 0.01)

        val varying = CalibrationSampleAccumulator()
        varying.accumulate(sineBlock(0.5f))
        varying.accumulate(sineBlock(0.05f)) // a much quieter block mid-window
        assertTrue("a variable signal must show a nonzero spread", varying.blockSpreadDb() > 5.0)
    }
}
