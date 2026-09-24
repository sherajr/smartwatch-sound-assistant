package com.peaceantz.stagescope.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SpectrumAnalyzerTest {

    private val sampleRate = 48000
    private val fftSize = SpectrumAnalyzer.DEFAULT_FFT_SIZE
    private val binWidth = sampleRate.toDouble() / fftSize

    private fun sineSamples(frequencyHz: Double, amplitude: Double, count: Int): FloatArray =
        FloatArray(count) { i -> (amplitude * sin(2.0 * PI * frequencyHz * i / sampleRate)).toFloat() }

    @Test
    fun `full-scale bin-aligned tone reads close to 0 dBFS at its bin`() {
        val analyzer = SpectrumAnalyzer(fftSize)
        val targetBin = 100
        val frequency = targetBin * binWidth // exactly bin-aligned, no spectral leakage
        analyzer.pushSamples(sineSamples(frequency, amplitude = 1.0, count = fftSize))

        val frame = analyzer.computeFrame(sampleRate)!!
        assertEquals(0.0, frame.magnitudesDbfs[targetBin], 0.5)
    }

    @Test
    fun `dominant frequency detection is within one bin width of a bin-aligned tone`() {
        val analyzer = SpectrumAnalyzer(fftSize)
        val targetBin = 200
        val frequency = targetBin * binWidth
        analyzer.pushSamples(sineSamples(frequency, amplitude = 0.8, count = fftSize))

        val frame = analyzer.computeFrame(sampleRate)!!
        val dominant = frame.dominantFrequencyHz
        assertTrue("expected a dominant frequency reading", dominant != null)
        assertEquals(frequency, dominant!!, binWidth)
    }

    @Test
    fun `interpolated peak frequency is close to a slightly off-bin tone`() {
        val analyzer = SpectrumAnalyzer(fftSize)
        val targetBin = 150.4 // deliberately not bin-aligned
        val frequency = targetBin * binWidth
        analyzer.pushSamples(sineSamples(frequency, amplitude = 0.8, count = fftSize))

        val frame = analyzer.computeFrame(sampleRate)!!
        assertEquals(frequency, frame.dominantFrequencyHz!!, binWidth * 0.5)
    }

    @Test
    fun `dominant frequency excludes DC and is gated on silence`() {
        val analyzer = SpectrumAnalyzer(fftSize)
        analyzer.pushSamples(FloatArray(fftSize)) // silence
        val frame = analyzer.computeFrame(sampleRate)!!
        assertNull(frame.dominantFrequencyHz)
    }

    @Test
    fun `two-tone signal shows two separate spectral peaks`() {
        val analyzer = SpectrumAnalyzer(fftSize)
        val binA = 80
        val binB = 400
        val freqA = binA * binWidth
        val freqB = binB * binWidth
        val samples = FloatArray(fftSize) { i ->
            (0.5 * sin(2.0 * PI * freqA * i / sampleRate) + 0.3 * sin(2.0 * PI * freqB * i / sampleRate)).toFloat()
        }
        analyzer.pushSamples(samples)
        val frame = analyzer.computeFrame(sampleRate)!!

        val midBin = (binA + binB) / 2
        assertTrue(frame.magnitudesDbfs[binA] > frame.magnitudesDbfs[midBin] + 20.0)
        assertTrue(frame.magnitudesDbfs[binB] > frame.magnitudesDbfs[midBin] + 20.0)
    }

    @Test
    fun `interpolatedBin returns the exact bin for a symmetric peak`() {
        val mags = doubleArrayOf(-40.0, -10.0, 0.0, -10.0, -40.0)
        assertEquals(2.0, SpectrumAnalyzer.interpolatedBin(mags, 2), 1e-9)
    }

    @Test
    fun `interpolatedBin leans toward the larger neighbor`() {
        val mags = doubleArrayOf(-40.0, -12.0, 0.0, -6.0, -40.0)
        val result = SpectrumAnalyzer.interpolatedBin(mags, 2)
        assertTrue("expected interpolation to shift right toward the bigger neighbor", result > 2.0)
    }

    @Test
    fun `findDominantBin never selects DC`() {
        val mags = DoubleArray(50) { -80.0 }
        mags[0] = 10.0 // huge DC component
        mags[30] = -10.0 // the real peak
        val bin = SpectrumAnalyzer.findDominantBin(mags, gateDbfs = SpectrumAnalyzer.SILENCE_GATE_DBFS)
        assertEquals(30, bin)
    }
}
