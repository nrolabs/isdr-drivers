package com.isaklab.libhackrfk

import com.isaklab.isdrdrivers.core.FloatRing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Contract tests for the transmit block renderer. What is pinned here is the
 * behaviour that separates a clean carrier from the splatter the old loop
 * produced: the queue cushion never collapses, corrections are single pairs,
 * and a genuine starve fades instead of stepping.
 */
class TxBlockRendererTest {

    private val pairs = HackRfProtocol.TX_BLOCK_PAIRS
    private val target = HackRfProtocol.TX_TARGET_PAIRS
    private val slack = HackRfProtocol.TX_SLACK_PAIRS

    private fun ringOf(pairCount: Int, value: Float = 0.5f): FloatRing {
        val r = FloatRing(HackRfProtocol.TX_INPUT_RATE * 2 * 2)
        r.write(FloatArray(pairCount * 2) { value })
        return r
    }

    @Test
    fun `renders exactly one usb block regardless of how much was drained`() {
        val r = TxBlockRenderer()
        val out = ByteArray(r.blockBytes)
        assertEquals(HackRfProtocol.TX_BLOCK_BYTES, r.blockBytes)
        // Full block, half a block and nothing at all all produce blockBytes.
        for (available in intArrayOf(target, pairs / 2, 0)) {
            val q = ringOf(available)
            val got = r.drain(q)
            out.fill(0x7F)
            r.render(got, out)
            assertEquals(HackRfProtocol.TX_BLOCK_BYTES, out.size)
        }
    }

    @Test
    fun `at the target depth it takes a whole block and trims nothing`() {
        val r = TxBlockRenderer()
        val q = ringOf(target)
        val got = r.drain(q)
        assertEquals(pairs, got)
        assertEquals(0L, r.trimmedPairs)
        assertEquals(0L, r.heldPairs)
        assertEquals(target - pairs, q.size / 2)
    }

    @Test
    fun `an over-long queue is trimmed by exactly one pair per block`() {
        val r = TxBlockRenderer()
        val q = ringOf(target + slack + 100)
        val before = q.size / 2
        val got = r.drain(q)
        assertEquals(pairs, got)
        assertEquals(1L, r.trimmedPairs)
        // One pair swallowed on top of the block that was consumed.
        assertEquals(before - pairs - 1, q.size / 2)
    }

    @Test
    fun `an over-short queue holds one pair per block`() {
        val r = TxBlockRenderer()
        val q = ringOf(target - slack - 100)
        val before = q.size / 2
        val got = r.drain(q)
        assertEquals(pairs - 1, got)
        assertEquals(1L, r.heldPairs)
        assertEquals(before - (pairs - 1), q.size / 2)
    }

    @Test
    fun `the cushion converges to the target from both sides`() {
        // Producer and consumer at exactly the same nominal rate: without the
        // correction the queue would simply stay wherever it started.
        for (start in intArrayOf(50, target * 3)) {
            val r = TxBlockRenderer()
            val q = ringOf(start)
            val out = ByteArray(r.blockBytes)
            repeat(4000) {
                val got = r.drain(q)
                r.render(got, out)
                q.write(FloatArray(pairs * 2) { 0.25f })   // one block back in
            }
            val depth = q.size / 2
            assertTrue(
                "converged to $depth from $start, target $target",
                abs(depth - target) <= slack + pairs,
            )
        }
    }

    @Test
    fun `a dry queue fades the last sample out instead of stepping to zero`() {
        val r = TxBlockRenderer()
        val out = ByteArray(r.blockBytes)
        // One full block of a constant, near-full-scale carrier...
        r.render(r.drain(ringOf(target, 0.9f)), out)
        val lastByte = out[out.size - 2]      // final I sample of the block
        assertTrue("carrier should be near full scale", abs(lastByte.toInt()) > 100)

        // ...then nothing at all. The envelope must ramp, not cliff.
        r.render(r.drain(ringOf(0)), out)
        val firstByte = out[0]
        assertTrue(
            "first starved sample stepped from $lastByte to $firstByte",
            abs(firstByte - lastByte) < abs(lastByte.toInt()) / 2,
        )
        // And it must reach silence, not hang on a constant unmodulated
        // carrier. Allow the interpolator's own tail: its history is 8 input
        // pairs deep, so the output only goes exactly zero that far after the
        // last non-zero input.
        val silentAfterPairs = HackRfProtocol.TX_FADE_PAIRS + TxInterpolator.TAPS_PER_PHASE
        val fadeEndByte = silentAfterPairs * HackRfProtocol.TX_UPSAMPLE * 2
        for (k in fadeEndByte until out.size) {
            assertEquals("sample $k after the fade window", 0.toByte(), out[k])
        }
        assertTrue(r.starvedPairs > 0)
    }

    @Test
    fun `the fade keeps running across a block boundary`() {
        val r = TxBlockRenderer()
        val out = ByteArray(r.blockBytes)
        // Fade shorter than a block would finish inside the first starved
        // block, so use a renderer whose fade spans several blocks.
        val slow = TxBlockRenderer(fadePairs = HackRfProtocol.TX_BLOCK_PAIRS * 3)
        slow.render(slow.drain(ringOf(target, 0.9f)), out)
        slow.render(slow.drain(ringOf(0)), out)
        val endOfFirstStarve = abs(out[out.size - 2].toInt())
        slow.render(slow.drain(ringOf(0)), out)
        val startOfSecondStarve = abs(out[0].toInt())
        assertTrue(
            "ramp restarted: $endOfFirstStarve -> $startOfSecondStarve",
            startOfSecondStarve <= endOfFirstStarve,
        )
    }

    @Test
    fun `reset clears fade state and counters between transmissions`() {
        val r = TxBlockRenderer()
        val out = ByteArray(r.blockBytes)
        r.render(r.drain(ringOf(target, 0.9f)), out)
        r.render(r.drain(ringOf(0)), out)
        assertTrue(r.starvedPairs > 0)
        r.reset()
        assertEquals(0L, r.starvedPairs)
        assertEquals(0L, r.trimmedPairs)
        assertEquals(0L, r.heldPairs)
        // After a reset a starved block must be pure silence — no leftover
        // carrier from the previous transmission leaking onto the air.
        r.render(r.drain(ringOf(0)), out)
        assertTrue(out.all { it == 0.toByte() })
    }

    @Test
    fun `a steady carrier reproduces without ripple through the whole block`() {
        // The polyphase branches are unit-DC-gain, so a keyed CW carrier must
        // come out flat; any per-phase ripple here would be audible buzz.
        val r = TxBlockRenderer()
        val out = ByteArray(r.blockBytes)
        // Prime the filter history first, then measure the second block.
        r.render(r.drain(ringOf(target, 0.5f)), out)
        r.render(r.drain(ringOf(target, 0.5f)), out)
        val expected = HackRfProtocol.toS8(0.5f)
        for (k in out.indices) {
            assertTrue(
                "sample $k = ${out[k]}, expected ~$expected",
                abs(out[k] - expected) <= 1,
            )
        }
    }
}
