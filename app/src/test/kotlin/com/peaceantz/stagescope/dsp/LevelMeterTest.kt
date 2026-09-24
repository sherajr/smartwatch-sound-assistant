package com.peaceantz.stagescope.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class LevelMeterTest {

    private fun sineBlock(amplitude: Float, samples: Int, cyclesOverBlock: Double = 10.0): FloatArray =
        FloatArray(samples) { i -> (amplitude * sin(2.0 * PI * cyclesOverBlock * i / samples)).toFloat() }

    @Test
    fun `silence measures at the floor and never clips`() {
        val meter = LevelMeter()
        val result = meter.processBlock(FloatArray(1024))
        assertEquals(DbScale.FLOOR_DBFS, result.rmsDbfs, 1e-6)
        assertEquals(DbScale.FLOOR_DBFS, result.peakDbfs, 1e-6)
        assertFalse(result.clipped)
    }

    @Test
    fun `half-amplitude sine matches the documented dBFS invariant`() {
        val meter = LevelMeter()
        val block = sineBlock(amplitude = 0.5f, samples = 4096, cyclesOverBlock = 40.0)
        val result = meter.processBlock(block)
        assertEquals(-9.03, result.rmsDbfs, 0.05)
        assertEquals(-6.02, result.peakDbfs, 0.05)
    }

    @Test
    fun `full-scale sample triggers clipping`() {
        val meter = LevelMeter()
        val block = FloatArray(512) { 0.1f }
        block[10] = 1.0f
        val result = meter.processBlock(block)
        assertTrue(result.clipped)
    }

    @Test
    fun `session average accumulates energy, not decibels`() {
        val meter = LevelMeter()
        // 100 full-scale samples, then 100 silent samples.
        meter.processBlock(FloatArray(100) { 1.0f })
        meter.processBlock(FloatArray(100) { 0.0f })

        // Correct: meanSquare = (100*1.0 + 100*0.0) / 200 = 0.5 -> 10*log10(0.5) = -3.01 dB.
        // A naive average of the two per-block dB readings (0 dB and the floor) would be
        // nowhere near this, which is exactly the bug this test guards against.
        assertEquals(-3.01, meter.sessionEnergyAverageDbfs(), 0.05)
    }

    @Test
    fun `max RMS tracks the loudest block seen so far`() {
        val meter = LevelMeter()
        meter.processBlock(FloatArray(256) { 0.1f })
        meter.processBlock(FloatArray(256) { 0.9f })
        meter.processBlock(FloatArray(256) { 0.2f })

        val maxRms = meter.maxRmsDbfsSoFar()
        assertEquals(DbScale.rmsDbfs(0.9 * 0.9), maxRms, 1e-6)
    }

    @Test
    fun `reset clears accumulated statistics`() {
        val meter = LevelMeter()
        meter.processBlock(FloatArray(256) { 1.0f })
        meter.reset()
        assertEquals(DbScale.FLOOR_DBFS, meter.sessionEnergyAverageDbfs(), 1e-6)
        assertEquals(DbScale.FLOOR_DBFS, meter.maxRmsDbfsSoFar(), 1e-6)
        assertFalse(meter.hasClippedSinceReset())
    }
}
