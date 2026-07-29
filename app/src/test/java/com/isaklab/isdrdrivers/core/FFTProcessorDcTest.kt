package com.isaklab.isdrdrivers.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A direct-conversion front end delivers a large constant offset along with
 * the signal. If the spectrum's DC estimate ramps up from zero, everything it
 * has not yet subtracted piles into the centre bin — a spike taller than any
 * real signal, present from the very first frame the operator sees.
 */
class FFTProcessorDcTest {

    private val bins = 1024

    /** A tone at a quarter of the rate, plus a large constant offset. */
    private fun block(offset: Float, pairs: Int = bins * 2): FloatArray {
        val iq = FloatArray(pairs * 2)
        for (n in 0 until pairs) {
            val phase = 2.0 * PI * 0.25 * n
            iq[2 * n] = (0.1 * cos(phase)).toFloat() + offset
            iq[2 * n + 1] = (0.1 * sin(phase)).toFloat() + offset
        }
        return iq
    }

    @Test
    fun offsetIsRemovedOnTheVeryFirstSpectrum() {
        val fft = FFTProcessor(bins)
        val spectrum = fft.computePowerSpectrum(block(offset = 0.4f))
        val centre = spectrum[bins / 2]
        val peak = spectrum.max()
        // The tone must dominate, not the offset sitting at the centre.
        assertTrue(
            "centre bin $centre dB is not below the peak $peak dB",
            centre < peak - 20f,
        )
    }

    @Test
    fun aCleanBlockIsUnharmed() {
        val fft = FFTProcessor(bins)
        val spectrum = fft.computePowerSpectrum(block(offset = 0f))
        val peak = spectrum.max()
        assertTrue("no signal survived: peak $peak dB", peak > -60f)
    }
}
