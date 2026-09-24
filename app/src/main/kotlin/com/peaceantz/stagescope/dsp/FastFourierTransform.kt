package com.peaceantz.stagescope.dsp

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

/**
 * Iterative in-place radix-2 Cooley-Tukey FFT, pure Kotlin, no external DSP dependency.
 *
 * Operates on parallel real/imaginary arrays of length N (a power of two). Uses the convention
 * X[k] = sum_n x[n] * exp(-2*pi*i*k*n/N), i.e. no 1/N normalization -- normalization is applied
 * by the caller ([SpectrumAnalyzer]) because the correct scale factor depends on the analysis
 * window applied beforehand.
 */
object FastFourierTransform {

    fun isPowerOfTwo(n: Int): Boolean = n > 0 && (n and (n - 1)) == 0

    /** Transforms [real]/[imag] in place. Both arrays must have the same power-of-two length. */
    fun transform(real: DoubleArray, imag: DoubleArray) {
        val n = real.size
        require(imag.size == n) { "real and imag must have equal length" }
        require(isPowerOfTwo(n)) { "FFT size must be a power of two, was $n" }
        if (n == 1) return

        bitReverse(real, imag)

        var len = 2
        while (len <= n) {
            val angle = -2.0 * PI / len
            val wReal = cos(angle)
            val wImag = sin(angle)
            var blockStart = 0
            while (blockStart < n) {
                var curReal = 1.0
                var curImag = 0.0
                val half = len / 2
                for (k in 0 until half) {
                    val evenIdx = blockStart + k
                    val oddIdx = evenIdx + half
                    val oddReal = real[oddIdx] * curReal - imag[oddIdx] * curImag
                    val oddImag = real[oddIdx] * curImag + imag[oddIdx] * curReal

                    real[oddIdx] = real[evenIdx] - oddReal
                    imag[oddIdx] = imag[evenIdx] - oddImag
                    real[evenIdx] += oddReal
                    imag[evenIdx] += oddImag

                    val nextReal = curReal * wReal - curImag * wImag
                    val nextImag = curReal * wImag + curImag * wReal
                    curReal = nextReal
                    curImag = nextImag
                }
                blockStart += len
            }
            len = len shl 1
        }
    }

    private fun bitReverse(real: DoubleArray, imag: DoubleArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
        }
    }
}
