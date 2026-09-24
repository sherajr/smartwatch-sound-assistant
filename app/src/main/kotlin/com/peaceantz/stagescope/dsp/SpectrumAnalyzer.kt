package com.peaceantz.stagescope.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Hann-windowed real FFT spectrum analyzer with 50% overlap.
 *
 * Amplitude/power convention: bins are one-sided amplitude spectra expressed in dBFS, i.e. a
 * full-scale sine wave landing exactly on a bin reads ~0 dBFS. Normalization divides by the sum
 * of the Hann window coefficients (coherent gain) and doubles all bins except DC and Nyquist to
 * fold the negative-frequency half of the spectrum in (one-sided normalization).
 */
class SpectrumAnalyzer(val fftSize: Int = DEFAULT_FFT_SIZE) {

    init {
        require(FastFourierTransform.isPowerOfTwo(fftSize)) { "fftSize must be a power of two" }
    }

    private val window = DoubleArray(fftSize) { i -> 0.5 - 0.5 * cos(2.0 * PI * i / (fftSize - 1)) }
    private val windowSum = window.sum()
    private val ring = FloatArray(fftSize)
    private var filledSamples = 0

    private val real = DoubleArray(fftSize)
    private val imag = DoubleArray(fftSize)

    data class Frame(
        val sampleRate: Int,
        val fftSize: Int,
        /** One-sided amplitude spectrum in dBFS, index 0 = DC, index size-1 = Nyquist. */
        val magnitudesDbfs: DoubleArray,
        /** Null when the signal is too quiet to trust a dominant-frequency reading. */
        val dominantFrequencyHz: Double?,
        val nyquistHz: Double,
        val binWidthHz: Double,
    )

    /** Feeds newly-captured normalized samples into the sliding analysis window. */
    fun pushSamples(block: FloatArray) {
        val n = block.size
        if (n <= 0) return
        if (n >= fftSize) {
            System.arraycopy(block, n - fftSize, ring, 0, fftSize)
            filledSamples = fftSize
        } else {
            System.arraycopy(ring, n, ring, 0, fftSize - n)
            System.arraycopy(block, 0, ring, fftSize - n, n)
            filledSamples = minOf(fftSize, filledSamples + n)
        }
    }

    /** Returns null until the sliding window has accumulated a full [fftSize] samples. */
    fun computeFrame(sampleRate: Int, gateDbfs: Double = SILENCE_GATE_DBFS): Frame? {
        if (filledSamples < fftSize) return null
        for (i in 0 until fftSize) {
            real[i] = ring[i] * window[i]
            imag[i] = 0.0
        }
        FastFourierTransform.transform(real, imag)

        val half = fftSize / 2
        val mags = DoubleArray(half + 1)
        val oneSidedFactor = 2.0 / windowSum
        val edgeFactor = 1.0 / windowSum
        for (k in 0..half) {
            val magnitudeRaw = sqrt(real[k] * real[k] + imag[k] * imag[k])
            val amplitude = if (k == 0 || k == half) magnitudeRaw * edgeFactor else magnitudeRaw * oneSidedFactor
            mags[k] = DbScale.amplitudeDbfs(amplitude)
        }

        val binWidth = sampleRate.toDouble() / fftSize
        val dominantBin = findDominantBin(mags, gateDbfs)
        val dominantFrequencyHz = dominantBin?.let { interpolatedBin(mags, it) * binWidth }

        return Frame(
            sampleRate = sampleRate,
            fftSize = fftSize,
            magnitudesDbfs = mags,
            dominantFrequencyHz = dominantFrequencyHz,
            nyquistHz = sampleRate / 2.0,
            binWidthHz = binWidth,
        )
    }

    fun reset() {
        ring.fill(0f)
        filledSamples = 0
    }

    companion object {
        const val DEFAULT_FFT_SIZE = 4096
        const val SILENCE_GATE_DBFS = -60.0

        /**
         * Finds the strongest bin excluding DC (index 0), gated by [gateDbfs]. Returns null if
         * the loudest non-DC bin is quieter than the gate -- this is what keeps random noise
         * floor from producing a confident-looking frequency reading.
         */
        fun findDominantBin(magnitudesDbfs: DoubleArray, gateDbfs: Double): Int? {
            var bestBin = -1
            var bestVal = Double.NEGATIVE_INFINITY
            for (k in 1 until magnitudesDbfs.size) {
                if (magnitudesDbfs[k] > bestVal) {
                    bestVal = magnitudesDbfs[k]
                    bestBin = k
                }
            }
            if (bestBin < 1 || bestVal < gateDbfs) return null
            return bestBin
        }

        /**
         * Quadratic (parabolic) interpolation on the log-magnitude spectrum around bin [k] for a
         * sub-bin frequency estimate. Falls back to the exact bin at the spectrum edges.
         */
        fun interpolatedBin(magnitudesDbfs: DoubleArray, k: Int): Double {
            if (k <= 0 || k >= magnitudesDbfs.size - 1) return k.toDouble()
            val alpha = magnitudesDbfs[k - 1]
            val beta = magnitudesDbfs[k]
            val gamma = magnitudesDbfs[k + 1]
            val denominator = alpha - 2.0 * beta + gamma
            if (denominator == 0.0) return k.toDouble()
            val p = 0.5 * (alpha - gamma) / denominator
            return k + p.coerceIn(-1.0, 1.0)
        }
    }
}
