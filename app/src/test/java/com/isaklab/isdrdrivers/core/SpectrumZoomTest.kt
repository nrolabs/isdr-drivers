package com.isaklab.isdrdrivers.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The window that makes a zoom mean something. Same invariants the console's
 * own front end is held to, because the operator uses both.
 */
class SpectrumZoomTest {

    /** Interleaved IQ of a complex tone at [hz]. */
    private fun tone(hz: Double, rate: Double, pairs: Int): FloatArray {
        val v = FloatArray(2 * pairs)
        for (n in 0 until pairs) {
            val p = 2.0 * PI * hz * n / rate
            v[2 * n] = cos(p).toFloat()
            v[2 * n + 1] = sin(p).toFloat()
        }
        return v
    }

    /** Amplitude of the tone at [hz] in a stream, by correlation. */
    private fun powerAt(iq: FloatArray, floats: Int, hz: Double, rate: Double): Double {
        val pairs = floats / 2
        var re = 0.0
        var im = 0.0
        for (n in 0 until pairs) {
            val p = -2.0 * PI * hz * n / rate
            val c = cos(p)
            val s = sin(p)
            val i = iq[2 * n].toDouble()
            val q = iq[2 * n + 1].toDouble()
            re += i * c - q * s
            im += i * s + q * c
        }
        return sqrt((re * re + im * im) / (pairs.toDouble() * pairs))
    }

    @Test
    fun `one x is the stream itself`() {
        val z = SpectrumZoom()
        z.set(1, 0.0, 2_400_000.0)
        val iq = tone(100_000.0, 2_400_000.0, 512)
        assertTrue("1x must hand back the very block it was given", z.process(iq) === iq)
        assertEquals(2_400_000.0, z.spanHz(2_400_000.0), 0.0)
    }

    @Test
    fun `the window centres on the offset`() {
        val rate = 2_400_000.0
        val z = SpectrumZoom()
        z.set(16, 300_000.0, rate)
        val out = z.process(tone(300_000.0, rate, 16 * 4096))
        assertTrue("short output: ${z.outputFloats / 2}", z.outputFloats >= 2 * 4000)
        val dc = powerAt(out, z.outputFloats, 0.0, rate / 16.0)
        assertTrue("the wanted signal lost ${20 * log10(dc)} dB", dc > 0.9)
    }

    @Test
    fun `a signal outside the span does not fold into it`() {
        val rate = 2_400_000.0
        val span = rate / 16.0
        val z = SpectrumZoom()
        z.set(16, 0.0, rate)
        val out = z.process(tone(span * 3.0, rate, 16 * 4096))
        var worst = 0.0
        for (k in 0 until 64) {
            val hz = -span / 2.0 + span * k / 64.0
            worst = max(worst, powerAt(out, z.outputFloats, hz, span))
        }
        val db = 20 * log10(max(worst, 1e-12))
        assertTrue("out-of-span signal folded in at $db dBc", db < -60.0)
    }

    @Test
    fun `the output rate is the input rate divided by the factor`() {
        val z = SpectrumZoom()
        for (d in intArrayOf(2, 4, 8, 16, 32, 64)) {
            z.set(d, 0.0, 2_400_000.0)
            z.reset()
            val want = 800
            val iq = tone(0.0, 2_400_000.0, z.inputPairsFor(want) + 4096)
            z.process(iq)
            assertTrue("${d}x produced ${z.outputFloats / 2} pairs, wanted $want",
                z.outputFloats / 2 >= want)
            assertEquals(2_400_000.0 / d, z.spanHz(2_400_000.0), 1e-9)
        }
    }

    @Test
    fun `blocks join without an edge`() {
        val rate = 48_000.0
        val z = SpectrumZoom()
        z.set(4, 0.0, rate)
        // A filter that forgot its history rings at every block boundary and
        // paints a stripe down the waterfall.
        val whole = tone(1_000.0, rate, 8192)
        val a = z.process(whole.copyOfRange(0, 2 * 4096)).copyOf(z.outputFloats)
        val b = z.process(whole.copyOfRange(2 * 4096, whole.size)).copyOf(z.outputFloats)
        val joined = a + b
        val tail = joined.copyOfRange(2 * 64, joined.size)
        val p = powerAt(tail, tail.size, 1_000.0, rate / 4.0)
        assertTrue("the join cost ${20 * log10(p)} dB", p > 0.9)
    }

    @Test
    fun `the factor is clamped to what is offered`() {
        val z = SpectrumZoom()
        z.set(0, 0.0, 48_000.0)
        assertEquals(1, z.decimation)
        z.set(4096, 0.0, 48_000.0)
        assertEquals(SpectrumZoom.MAX_DECIMATION, z.decimation)
    }

    /** A gain change with zoom would make the same signal read at a different
     *  level depending on how far in the operator happened to be. */
    @Test
    fun `zooming does not change how loud the display reads`() {
        val rate = 2_400_000.0
        val z = SpectrumZoom()
        val flat = powerAt(tone(0.0, rate, 4096), 2 * 4096, 0.0, rate)
        for (d in intArrayOf(2, 8, 32)) {
            z.set(d, 0.0, rate)
            z.reset()
            z.process(tone(0.0, rate, z.inputPairsFor(2048) + 4096))
            val out = z.process(tone(0.0, rate, z.inputPairsFor(2048)))
            val p = powerAt(out, z.outputFloats, 0.0, rate / d)
            assertTrue("${d}x moved the level by ${20 * log10(p / flat)} dB",
                abs(20 * log10(p / flat)) < 0.5)
        }
    }
}
