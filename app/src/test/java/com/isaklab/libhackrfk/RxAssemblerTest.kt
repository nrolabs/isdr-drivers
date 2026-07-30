package com.isaklab.libhackrfk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the receive-stream assembler: every byte the bus
 * delivered reaches the DSP side exactly once and in order, whatever the
 * transfer sizes — including the short and odd-length transfers the old loop
 * used to discard, which tore I/Q phase alignment.
 */
class RxAssemblerTest {

    private val blockPairs = 16
    private val blockBytes = blockPairs * 2

    private fun rampBytes(count: Int, start: Int = 0): ByteArray =
        ByteArray(count) { ((start + it) % 251 - 125).toByte() }

    private fun drainAll(a: RxAssembler): MutableList<Float> {
        val out = FloatArray(blockPairs * 2)
        val all = mutableListOf<Float>()
        while (a.awaitBlock(out, 0)) all.addAll(out.toList())
        return all
    }

    private fun assertStream(expectedBytes: ByteArray, got: List<Float>) {
        val wholeBlocks = expectedBytes.size / blockBytes * blockBytes
        assertEquals(wholeBlocks, got.size)
        for (i in 0 until wholeBlocks) {
            assertEquals(
                "byte $i",
                HackRfProtocol.s8ToFloat(expectedBytes[i]),
                got[i],
                0f,
            )
        }
    }

    @Test
    fun `contiguous stream survives arbitrary transfer splits`() {
        val a = RxAssembler(blockPairs, 1024)
        val src = rampBytes(7 * blockBytes)
        // Deliberately awkward split sizes: sub-block, over-block, prime.
        val splits = intArrayOf(5, 31, 64, 1, 17, 90, 3)
        var off = 0
        var s = 0
        while (off < src.size) {
            val n = minOf(splits[s % splits.size], src.size - off)
            val chunk = ByteArray(n)
            System.arraycopy(src, off, chunk, 0, n)
            a.feed(chunk, n)
            off += n
            s++
        }
        assertStream(src, drainAll(a))
        assertEquals(0L, a.dropEvents)
    }

    @Test
    fun `short transfers are kept not discarded`() {
        val a = RxAssembler(blockPairs, 1024)
        // Two short transfers that together make exactly one block: the old
        // path (`if (pairs < 256) continue`) threw both away.
        val src = rampBytes(blockBytes)
        a.feed(src.copyOfRange(0, 10), 10)
        a.feed(src.copyOfRange(10, blockBytes), blockBytes - 10)
        assertStream(src, drainAll(a))
    }

    @Test
    fun `odd-length transfer does not swap I and Q`() {
        val a = RxAssembler(blockPairs, 1024)
        // Stream where every I is positive and every Q negative; feed it
        // with an odd split so the residue byte crosses the transfer
        // boundary. Any parity slip makes an I land on a Q slot.
        val src = ByteArray(2 * blockBytes) { if (it % 2 == 0) 10 else -10 }
        a.feed(src, 11)
        val rest = ByteArray(src.size - 11)
        System.arraycopy(src, 11, rest, 0, rest.size)
        a.feed(rest, rest.size)
        val got = drainAll(a)
        assertEquals(2 * blockBytes, got.size)
        for (i in got.indices) {
            if (i % 2 == 0) assertTrue("I at $i", got[i] > 0f)
            else assertTrue("Q at $i", got[i] < 0f)
        }
    }

    @Test
    fun `no block is delivered until one is complete`() {
        val a = RxAssembler(blockPairs, 1024)
        a.feed(rampBytes(blockBytes - 1), blockBytes - 1)
        val out = FloatArray(blockPairs * 2)
        assertFalse(a.awaitBlock(out, 0))
        a.feed(byteArrayOf(1), 1)
        assertTrue(a.awaitBlock(out, 0))
    }

    @Test
    fun `overflow drops whole old blocks and counts the event`() {
        val capacity = 4 * blockBytes
        val a = RxAssembler(blockPairs, capacity)
        val first = rampBytes(capacity)
        a.feed(first, capacity)
        // One more block than fits: the OLDEST block must go, newest stays.
        val extra = rampBytes(blockBytes, start = 100)
        a.feed(extra, blockBytes)
        assertTrue(a.dropEvents > 0)
        val got = drainAll(a)
        assertEquals(capacity, got.size)
        // The tail of what we read must be the newest data, uncorrupted.
        val tail = got.takeLast(blockBytes)
        for (i in tail.indices) {
            assertEquals(HackRfProtocol.s8ToFloat(extra[i]), tail[i], 0f)
        }
        // Parity check: dropping must have removed a whole number of blocks,
        // so the stream still starts on a block boundary of the original.
        val expectedStart = first.copyOfRange(blockBytes, 2 * blockBytes)
        for (i in 0 until blockBytes) {
            assertEquals(HackRfProtocol.s8ToFloat(expectedStart[i]), got[i], 0f)
        }
    }

    @Test
    fun `reset clears data and counters`() {
        val a = RxAssembler(blockPairs, 1024)
        a.feed(rampBytes(3 * blockBytes), 3 * blockBytes)
        a.reset()
        assertEquals(0, a.pending)
        assertEquals(0L, a.dropEvents)
        val out = FloatArray(blockPairs * 2)
        assertFalse(a.awaitBlock(out, 0))
    }
}
