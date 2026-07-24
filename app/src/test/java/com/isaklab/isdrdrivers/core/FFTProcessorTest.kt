package com.isaklab.isdrdrivers.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FFTProcessorTest {

    private fun tone(pairs: Int, cyclesPerFft: Double, fftSize: Int = 64): FloatArray {
        val iq = FloatArray(pairs * 2)
        for (n in 0 until pairs) {
            val ph = 2.0 * PI * cyclesPerFft * n / fftSize
            iq[2 * n] = cos(ph).toFloat()
            iq[2 * n + 1] = sin(ph).toFloat()
        }
        return iq
    }

    @Test fun emptyBlockDoesNotPoisonWithNaN() {
        val p = FFTProcessor(64)
        val empty = p.computePowerSpectrum(FloatArray(0), 0)
        assertEquals(64, empty.size)
        assertFalse(empty.any { it.isNaN() })
        // A later real block must still produce a finite spectrum — the old
        // code divided 0/0 into the DC IIR and NaN-ed every spectrum forever.
        val s = p.computePowerSpectrum(tone(64, 8.0), 64)
        assertFalse(s.any { it.isNaN() })
    }

    @Test fun tonePeaksAtItsBin() {
        val p = FFTProcessor(64)
        p.setSmoothingFactor(1f)
        val s = p.computePowerSpectrum(tone(64, 8.0), 64)
        // +8 cycles/frame → bin center+8 after fft-shift.
        val peak = s.indices.maxBy { s[it] }
        assertEquals(32 + 8, peak)
    }

    @Test fun welchSeesSignalBeyondTheFirstFftWindow() {
        val fftSize = 64
        val pairs = 640                        // 10 windows' worth
        // Silence for the first window, tone only afterwards: the old
        // implementation FFT'd only pairs [0,64) and showed pure noise floor.
        val iq = FloatArray(pairs * 2)
        val t = tone(pairs, 8.0, fftSize)
        System.arraycopy(t, fftSize * 2, iq, fftSize * 2, (pairs - fftSize) * 2)
        val p = FFTProcessor(fftSize)
        p.setSmoothingFactor(1f)
        val s = p.computePowerSpectrum(iq, pairs)
        val peakBin = 32 + 8
        val floorBin = 32 + 20
        assertTrue(
            "tone after the first window must be visible (peak=${s[peakBin]} floor=${s[floorBin]})",
            s[peakBin] > s[floorBin] + 20f,
        )
    }

    @Test fun fullBlockAverageMatchesSteadyTonePeak() {
        val p = FFTProcessor(64)
        p.setSmoothingFactor(1f)
        val s = p.computePowerSpectrum(tone(6400, 4.0), 6400)  // 100 windows, MAX_SEGMENTS cap
        assertFalse(s.any { it.isNaN() })
        assertEquals(32 + 4, s.indices.maxBy { s[it] })
    }

    @Test fun partialWindowStillWorks() {
        val p = FFTProcessor(64)
        val s = p.computePowerSpectrum(tone(10, 2.0), 10)      // zero-padded
        assertEquals(64, s.size)
        assertFalse(s.any { it.isNaN() })
    }
}
