package com.isaklab.isdrdrivers.core

import org.jtransforms.fft.DoubleFFT_1D
import kotlin.math.*

/**
 * Computes a windowed power spectrum (in dB) from interleaved IQ samples for the
 * spectrum/waterfall display. Removes the DC component with a single-pole IIR,
 * applies a Hann window, FFT-shifts to center DC, and exponentially smooths the
 * result across frames. Reuses its FFT buffers to avoid per-call allocation.
 *
 * Blocks larger than [fftSize] are Welch-averaged: 50 %-overlapped Hann
 * segments spanning the WHOLE block, powers averaged before the dB
 * conversion. The old single-segment path silently discarded everything past
 * the first [fftSize] pairs — up to 7680-pair blocks showed only their first
 * ~10 % on screen, and short signals landing later in a block never appeared.
 * Same output size and call cadence as before; only the estimate improved
 * (variance drops ~1/segments).
 */
class FFTProcessor(private val fftSize: Int = SDRConfig.FFT_SIZE) {

    companion object {
        /** CPU ceiling: 32 segments of 800 pairs cover > 13k-pair blocks. */
        private const val MAX_SEGMENTS = 32
    }

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
    private val powerAcc = DoubleArray(fftSize)

    /**
     * Computes the shifted power spectrum in dB from interleaved IQ samples
     * (i0, q0, i1, q1, ...), Welch-averaging over the whole block. Tracks and
     * removes the DC component with a single-pole IIR before windowing.
     * An empty block returns the previous spectrum (or a floor spectrum) —
     * it must never poison the DC IIR with NaN (0/0).
     */
    fun computePowerSpectrum(iq: FloatArray, pairs: Int = iq.size / 2): FloatArray {
        val total = min(pairs, iq.size / 2)
        if (total <= 0) {
            // Guard: size==0 previously computed 0.0/0 → NaN into the DC IIR,
            // permanently NaN-ing every later spectrum.
            return previousSpectrum?.copyOf() ?: FloatArray(fftSize) { -120f }
        }

        val hop = maxOf(1, fftSize / 2)
        val segments = if (total <= fftSize) 1
        else min(1 + (total - fftSize) / hop, MAX_SEGMENTS)

        java.util.Arrays.fill(powerAcc, 0.0)
        for (seg in 0 until segments) {
            val off = seg * hop
            val size = min(fftSize, total - off)
            accumulateSegment(iq, off, size)
        }
        val norm = 1.0 / segments

        val spectrum = FloatArray(fftSize)
        val centerBin = fftSize / 2
        for (i in 0 until fftSize) {
            val shiftedIdx = (i + fftSize / 2) % fftSize
            var power = powerAcc[i] * norm

            if (shiftedIdx == centerBin && fftSize > 2) {
                // Residual DC spike: replace the center bin with its
                // neighbours' average, on the Welch-averaged powers.
                val left = powerAcc[(i - 1 + fftSize) % fftSize] * norm
                val right = powerAcc[(i + 1) % fftSize] * norm
                power = (left + right) / 2
            }

            spectrum[shiftedIdx] = if (power > 1e-20) {
                (10.0 * log10(power) - 60.0).toFloat()
            } else {
                -120f
            }
        }

        return applySmoothing(spectrum)
    }

    /** One windowed, DC-corrected FFT of iq[off..off+size) into [powerAcc]. */
    private fun accumulateSegment(iq: FloatArray, off: Int, size: Int) {
        var sumI = 0.0
        var sumQ = 0.0
        for (i in 0 until size) {
            sumI += iq[2 * (off + i)]
            sumQ += iq[2 * (off + i) + 1]
        }
        dcI = dcI * (1 - dcAlpha) + (sumI / size) * dcAlpha
        dcQ = dcQ * (1 - dcAlpha) + (sumQ / size) * dcAlpha

        java.util.Arrays.fill(fftInput, 0.0)
        for (i in 0 until size) {
            fftInput[2 * i] = (iq[2 * (off + i)] - dcI) * window[i]
            fftInput[2 * i + 1] = (iq[2 * (off + i) + 1] - dcQ) * window[i]
        }
        fft.complexForward(fftInput)
        for (i in 0 until fftSize) {
            val re = fftInput[2 * i]
            val im = fftInput[2 * i + 1]
            powerAcc[i] += (re * re + im * im) / fftSize
        }
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
