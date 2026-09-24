package com.peaceantz.stagescope.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class FastFourierTransformTest {

    @Test
    fun `rejects non power of two sizes`() {
        assertThrows(IllegalArgumentException::class.java) {
            FastFourierTransform.transform(DoubleArray(100), DoubleArray(100))
        }
    }

    @Test
    fun `impulse transforms to a flat spectrum`() {
        val n = 64
        val real = DoubleArray(n)
        val imag = DoubleArray(n)
        real[0] = 1.0
        FastFourierTransform.transform(real, imag)
        for (k in 0 until n) {
            assertEquals(1.0, hypot(real[k], imag[k]), 1e-9)
        }
    }

    @Test
    fun `DC-only signal concentrates all energy in bin zero`() {
        val n = 64
        val real = DoubleArray(n) { 2.0 }
        val imag = DoubleArray(n)
        FastFourierTransform.transform(real, imag)
        assertEquals(2.0 * n, hypot(real[0], imag[0]), 1e-6)
        for (k in 1 until n) {
            assertEquals(0.0, hypot(real[k], imag[k]), 1e-6)
        }
    }

    @Test
    fun `bin-aligned sine produces energy only at that bin and its mirror`() {
        val n = 256
        val targetBin = 20
        val real = DoubleArray(n) { i -> cos(2.0 * PI * targetBin * i / n) }
        val imag = DoubleArray(n)
        FastFourierTransform.transform(real, imag)

        val expectedMagnitude = n / 2.0
        assertEquals(expectedMagnitude, hypot(real[targetBin], imag[targetBin]), 1e-6)
        assertEquals(expectedMagnitude, hypot(real[n - targetBin], imag[n - targetBin]), 1e-6)

        // A bin far from the tone and its mirror should carry negligible energy.
        val quietBin = targetBin + n / 4
        assertEquals(0.0, hypot(real[quietBin], imag[quietBin]), 1e-6)
    }
}
