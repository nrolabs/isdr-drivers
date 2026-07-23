package com.isaklab.isdrdrivers.core

import org.jtransforms.fft.DoubleFFT_1D
import kotlin.math.*

/**
 * Computes a windowed power spectrum (in dB) from interleaved IQ samples for the
 * spectrum/waterfall display. Removes the DC component with a single-pole IIR,
 * applies a Hann window, FFT-shifts to center DC, and exponentially smooths the
 * result across frames. Reuses its FFT buffer to avoid per-call allocation.
 */
class FFTProcessor(private val fftSize: Int = SDRConfig.FFT_SIZE) {

    private val fft = DoubleFFT_1D(fftSize.toLong())
    private val window = DoubleArray(fftSize) { i ->
        0.5 * (1.0 - cos(2.0 * PI * i / (fftSize - 1)))
    }

    private var previousSpectrum: FloatArray? = null
    private var smoothingAlpha = 0.3f

    private var dcI = 0.0
    private var dcQ = 0.0
    private val dcAlpha = 0.01

    private val fftInput = DoubleArray(fftSize * 2)

    /**
     * Computes the shifted power spectrum in dB from interleaved IQ samples
     * (i0, q0, i1, q1, ...). Uses at most [fftSize] pairs. Tracks and removes
     * the DC component with a single-pole IIR before windowing.
     */
    fun computePowerSpectrum(iq: FloatArray, pairs: Int = iq.size / 2): FloatArray {
        val size = min(fftSize, pairs)
        java.util.Arrays.fill(fftInput, 0.0)

        var sumI = 0.0
        var sumQ = 0.0
        for (i in 0 until size) {
            sumI += iq[2 * i]
            sumQ += iq[2 * i + 1]
        }
        val avgI = sumI / size
        val avgQ = sumQ / size

        dcI = dcI * (1 - dcAlpha) + avgI * dcAlpha
        dcQ = dcQ * (1 - dcAlpha) + avgQ * dcAlpha

        for (i in 0 until size) {
            fftInput[2 * i] = (iq[2 * i] - dcI) * window[i]
            fftInput[2 * i + 1] = (iq[2 * i + 1] - dcQ) * window[i]
        }

        fft.complexForward(fftInput)

        val spectrum = FloatArray(fftSize)
        val centerBin = fftSize / 2

        for (i in 0 until fftSize) {
            val shiftedIdx = (i + fftSize / 2) % fftSize
            val real = fftInput[2 * i]
            val imag = fftInput[2 * i + 1]
            val power = (real * real + imag * imag) / fftSize

            var dbValue = if (power > 1e-20) {
                (10.0 * log10(power) - 60.0).toFloat()
            } else {
                -120f
            }

            if (shiftedIdx == centerBin && fftSize > 2) {
                val leftPower = run {
                    val li = (i - 1 + fftSize) % fftSize
                    val lr = fftInput[2 * li]
                    val lq = fftInput[2 * li + 1]
                    (lr * lr + lq * lq) / fftSize
                }
                val rightPower = run {
                    val ri = (i + 1) % fftSize
                    val rr = fftInput[2 * ri]
                    val rq = fftInput[2 * ri + 1]
                    (rr * rr + rq * rq) / fftSize
                }
                val avgPower = (leftPower + rightPower) / 2
                dbValue = if (avgPower > 1e-20) {
                    (10.0 * log10(avgPower) - 60.0).toFloat()
                } else {
                    -120f
                }
            }

            spectrum[shiftedIdx] = dbValue
        }

        return applySmoothing(spectrum)
    }

    private fun applySmoothing(spectrum: FloatArray): FloatArray {
        val prev = previousSpectrum
        return if (prev != null && prev.size == spectrum.size) {
            FloatArray(spectrum.size) { i ->
                smoothingAlpha * spectrum[i] + (1 - smoothingAlpha) * prev[i]
            }.also { previousSpectrum = it }
        } else {
            spectrum.also { previousSpectrum = it.copyOf() }
        }
    }

    fun reset() {
        previousSpectrum = null
        dcI = 0.0
        dcQ = 0.0
    }

    fun resetSmoothing() {
        previousSpectrum = null
    }

    fun setSmoothingFactor(alpha: Float) {
        smoothingAlpha = alpha.coerceIn(0f, 1f)
    }
}
