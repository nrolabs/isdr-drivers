package com.isaklab.libhackrfk

import com.isaklab.isdrdrivers.core.FloatRing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Unkey-ramp contract: releasing PTT must end the envelope with a bounded
 * fade to zero, never a step, and must declare completion so the client
 * knows when the mode switch is safe.
 */
class TxUnkeyTest {

    private val pairs = HackRfProtocol.TX_BLOCK_PAIRS
    private val up = HackRfProtocol.TX_UPSAMPLE

    private fun ringOf(pairCount: Int, value: Float = 0.9f): FloatRing {
        val r = FloatRing(HackRfProtocol.TX_INPUT_RATE * 2 * 2)
        r.write(FloatArray(pairCount * 2) { value })
        return r
    }

    /** Peak |sample| per input pair across one rendered block. */
    private fun pairPeaks(out: ByteArray): FloatArray {
        val peaks = FloatArray(pairs)
        for (p in 0 until pairs) {
            var peak = 0f
            for (k in 0 until up * 2) {
                val v = abs(HackRfProtocol.s8ToFloat(out[p * up * 2 + k]))
                if (v > peak) peak = v
            }
            peaks[p] = peak
        }
        return peaks
    }

    @Test
    fun `unkey ramps the envelope down and completes after the ramp length`() {
        val r = TxBlockRenderer()
        val out = ByteArray(r.blockBytes)
        val q = ringOf(HackRfProtocol.TX_TARGET_PAIRS)
        // Settle the interpolator on the carrier first.
        r.render(r.drain(q), out)
        assertFalse(r.unkeyComplete)
        r.beginUnkey(pairs) // one block long, for a self-contained check
        r.render(r.drain(q), out)
        assertTrue(r.unkeyComplete)
        val peaks = pairPeaks(out)
        // Monotonically non-increasing envelope (small tolerance for the
        // interpolator's ripple) and effectively zero at the end.
        for (p in 1 until pairs) {
            assertTrue("envelope rose at pair $p", peaks[p] <= peaks[p - 1] + 0.05f)
        }
        assertTrue("did not reach zero", peaks[pairs - 1] < 0.05f)
        assertTrue("started from carrier level", peaks[0] > 0.5f)
    }

    @Test
    fun `after the ramp only zeros are rendered`() {
        val r = TxBlockRenderer()
        val out = ByteArray(r.blockBytes)
        val q = ringOf(HackRfProtocol.TX_TARGET_PAIRS * 2)
        r.render(r.drain(q), out)
        r.beginUnkey(pairs)
        r.render(r.drain(q), out) // ramp block
        r.render(r.drain(q), out) // fully post-ramp block
        assertTrue(r.unkeyComplete)
        // The interpolator carries 8 pairs of input history; past twice that
        // the block must be exactly zero even though the queue still has data.
        for (i in 16 * up * 2 until out.size) {
            assertEquals("residual at $i", 0, out[i].toInt())
        }
    }

    @Test
    fun `unkey ramp spans block boundaries`() {
        val r = TxBlockRenderer()
        val out = ByteArray(r.blockBytes)
        val q = ringOf(HackRfProtocol.TX_TARGET_PAIRS * 3)
        r.render(r.drain(q), out)
        r.beginUnkey(HackRfProtocol.TX_UNKEY_FADE_PAIRS) // 480 pairs ~ 2 blocks
        r.render(r.drain(q), out)
        assertFalse("completed too early", r.unkeyComplete)
        val endOfFirst = pairPeaks(out)[pairs - 1]
        r.render(r.drain(q), out)
        assertTrue(r.unkeyComplete)
        val startOfSecond = pairPeaks(out)[0]
        // The ramp must CONTINUE across the boundary, not restart.
        assertTrue(
            "ramp restarted at block boundary",
            startOfSecond <= endOfFirst + 0.02f,
        )
    }

    @Test
    fun `every rendered block still has the exact block size`() {
        val r = TxBlockRenderer()
        val out = ByteArray(r.blockBytes)
        val q = ringOf(HackRfProtocol.TX_TARGET_PAIRS)
        r.beginUnkey()
        r.render(r.drain(q), out)
        assertEquals(HackRfProtocol.TX_BLOCK_BYTES, out.size)
    }

    @Test
    fun `beginUnkey is idempotent while a ramp is running`() {
        val r = TxBlockRenderer()
        val out = ByteArray(r.blockBytes)
        val q = ringOf(HackRfProtocol.TX_TARGET_PAIRS)
        r.render(r.drain(q), out)
        r.beginUnkey(pairs)
        r.render(r.drain(q), out)
        assertTrue(r.unkeyComplete)
        r.beginUnkey(pairs) // must NOT restart the ramp
        assertTrue(r.unkeyComplete)
    }

    @Test
    fun `reset re-arms the renderer for the next over`() {
        val r = TxBlockRenderer()
        val out = ByteArray(r.blockBytes)
        r.beginUnkey(1)
        r.render(0, out)
        assertTrue(r.unkeyComplete)
        r.reset()
        assertFalse(r.unkeyComplete)
    }
}
