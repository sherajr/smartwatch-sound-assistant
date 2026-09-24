package com.peaceantz.stagescope.ui.analyzer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadialMappingTest {

    @Test
    fun `angle and fraction round-trip across the usable arc`() {
        for (i in 0..10) {
            val fraction = i / 10f
            val angle = RadialMapping.angleForFraction(fraction)
            val back = RadialMapping.fractionForAngle(angle)
            assertEquals(fraction, back!!, 1e-3f)
        }
    }

    @Test
    fun `an angle inside the excluded gap maps to no fraction`() {
        // The gap is centered on 90deg (straight down) -- the bottom action-row area.
        assertNull(RadialMapping.fractionForAngle(90f))
    }

    @Test
    fun `bin and frequency mapping respects Nyquist at a real sample rate`() {
        // 48kHz capture, 4096-point FFT -> 2049 bins (0..2048), Nyquist bin = 2048 = 24kHz.
        val minBin = 1
        val maxBin = 2048
        assertEquals(0f, RadialMapping.fractionForBin(minBin, minBin, maxBin), 1e-6f)
        assertEquals(1f, RadialMapping.fractionForBin(maxBin, minBin, maxBin), 1e-6f)

        // Frequency increases strictly with fraction (monotonic, one consistent direction).
        var lastBin = minBin
        for (i in 1..20) {
            val bin = RadialMapping.binForFraction(i / 20f, minBin, maxBin)
            assertTrue("bin must not decrease as fraction increases", bin >= lastBin)
            lastBin = bin
        }
    }

    @Test
    fun `DC bin is never reachable through the mapping`() {
        val minBin = 1
        val maxBin = 2048
        assertEquals(minBin, RadialMapping.binForFraction(0f, minBin, maxBin))
        assertTrue(RadialMapping.binForFraction(0f, minBin, maxBin) > 0)
    }

    @Test
    fun `radial dB mapping is fixed-scale, never exceeding 0 or 1`() {
        assertEquals(0f, RadialMapping.radialFractionForDb(-90.0), 1e-6f)
        assertEquals(1f, RadialMapping.radialFractionForDb(0.0), 1e-6f)
        assertEquals(1f, RadialMapping.radialFractionForDb(20.0), 1e-6f) // clamped, never overshoots
        assertEquals(0f, RadialMapping.radialFractionForDb(-200.0), 1e-6f) // clamped, never undershoots
    }

    @Test
    fun `band aggregation preserves a narrow one-bin peak instead of averaging it away`() {
        val bins = 2049
        val mags = DoubleArray(bins) { -80.0 }
        val spikeBin = 1500
        mags[spikeBin] = -6.0 // a single sharp, narrow peak surrounded by near-silence

        val bands = RadialMapping.aggregateBands(mags, bandCount = 56)
        val spikeBand = RadialMapping.bandForBin(spikeBin, 1, bins - 1, 56)

        assertEquals(-6.0, bands[spikeBand].magnitudeDbfs, 1e-9)
        assertEquals(spikeBin, bands[spikeBand].peakBin)
    }

    @Test
    fun `every display band cites a real bin, never a fabricated center frequency`() {
        val bins = 2049
        val mags = DoubleArray(bins) { it * -0.01 }
        val bands = RadialMapping.aggregateBands(mags, bandCount = RadialMapping.DISPLAY_BAND_COUNT)
        for (band in bands) {
            assertTrue("peakBin must be a real bin index within range", band.peakBin in 1 until bins)
            assertEquals("the reported magnitude must be the actual value at that real bin", mags[band.peakBin], band.magnitudeDbfs, 1e-9)
        }
    }

    @Test
    fun `band count is bounded regardless of FFT size`() {
        val small = RadialMapping.aggregateBands(DoubleArray(513) { -80.0 })
        val large = RadialMapping.aggregateBands(DoubleArray(8193) { -80.0 })
        assertEquals(RadialMapping.DISPLAY_BAND_COUNT, small.size)
        assertEquals(RadialMapping.DISPLAY_BAND_COUNT, large.size)
    }
}
