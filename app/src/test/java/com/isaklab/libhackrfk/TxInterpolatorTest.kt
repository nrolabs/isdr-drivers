package com.isaklab.libhackrfk

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spectral contract of the HackRF TX interpolator: the whole reason it exists
 * is suppressing the 48 kHz-spaced images a cheap interpolation would
 * transmit, so the tests measure exactly that.
 */
class TxInterpolatorTest {

    private val inputRate = HackRfProtocol.TX_INPUT_RATE
    private val boardRate = HackRfProtocol.TX_BOARD_RATE
    private val up = HackRfProtocol.TX_UPSAMPLE

    /** Complex-tone power (Goertzel-style correlation) at [freqHz], in dB. */
    private fun toneDb(iq: FloatArray, sampleRate: Int, freqHz: Double): Double {
        var re = 0.0
        var im = 0.0
        val pairs = iq.size / 2
        for (n in 0 until pairs) {
            val phi = -2.0 * PI * freqHz * n / sampleRate
            val c = cos(phi)
            val s = sin(phi)
            val i = iq[2 * n].toDouble()
            val q = iq[2 * n + 1].toDouble()
            // (i + jq) · e^{-jωn}
            re += i * c - q * s
            im += i * s + q * c
        }
        val mag = sqrt(re * re + im * im) / pairs
        return 20.0 * log10(mag.coerceAtLeast(1e-12))
    }

    private fun upsampledTone(toneHz: Double, inputPairs: Int): FloatArray {
        val interp = TxInterpolator()
        val out = FloatArray(inputPairs * up * 2)
        var offset = 0
        for (n in 0 until inputPairs) {
            val phi = 2.0 * PI * toneHz * n / inputRate
            offset = interp.process(
                (0.9 * cos(phi)).toFloat(), (0.9 * sin(phi)).toFloat(), out, offset,
            )
        }
        return out
    }

    @Test
    fun `output length is exactly the upsampling factor`() {
        val interp = TxInterpolator()
        val out = FloatArray(up * 2)
        assertEquals(up * 2, interp.process(0.1f, -0.1f, out, 0))
    }

    @Test
    fun `constant input reproduces exactly - keyed cw carrier has zero ripple`() {
        val interp = TxInterpolator()
        val out = FloatArray(up * 2)
        // Warm the history up with the constant, then check steady state.
        repeat(16) { interp.process(0.7f, -0.3f, out, 0) }
        for (k in 0 until up) {
            assertEquals(0.7f, out[2 * k], 1e-5f)
            assertEquals(-0.3f, out[2 * k + 1], 1e-5f)
        }
    }

    @Test
    fun `tone passes through the passband without loss`() {
        // 3 kHz — top of the speech band the modulator produces.
        val out = upsampledTone(3_000.0, 4800)   // 0.1 s
        val carrier = toneDb(out, boardRate, 3_000.0)
        // 0.9 amplitude tone → −0.9 dB reference; allow ±1 dB through the filter.
        assertTrue("carrier level $carrier dB", carrier > -2.0)
    }

    @Test
    fun `first images at 48 khz offsets are suppressed below -55 dbc`() {
        val out = upsampledTone(3_000.0, 4800)
        val carrier = toneDb(out, boardRate, 3_000.0)
        // Zero-order-hold/linear images land at ±48 kHz around the tone:
        // 48k + 3k and 48k − 3k (and mirrors); measure the worst offenders.
        for (image in doubleArrayOf(51_000.0, 45_000.0, -45_000.0, 99_000.0)) {
            val level = toneDb(out, boardRate, image) - carrier
            assertTrue("image at $image Hz only ${level.toInt()} dBc", level < -55.0)
        }
    }

    @Test
    fun `silence in produces silence out after flush`() {
        val interp = TxInterpolator()
        val out = FloatArray(up * 2)
        repeat(4) { interp.process(0.9f, 0.9f, out, 0) }
        // Feed zeros for longer than the 8-tap history.
        repeat(16) { interp.process(0f, 0f, out, 0) }
        for (v in out) assertEquals(0f, v, 1e-6f)
    }

    @Test
    fun `reset clears the history`() {
        val interp = TxInterpolator()
        val out = FloatArray(up * 2)
        repeat(8) { interp.process(0.9f, 0.9f, out, 0) }
        interp.reset()
        interp.process(0f, 0f, out, 0)
        // Nothing of the old signal may leak through after a reset.
        for (v in out) assertEquals(0f, v, 1e-6f)
    }
}
