package com.isaklab.libhackrfk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the transmit quantizer's rounding behaviour and the sweep resync
 * header validation.
 */
class HackRfQuantAndSweepSyncTest {

    // ---- toS8 rounding ----------------------------------------------------

    @Test
    fun `quantizer rounds to nearest instead of truncating toward zero`() {
        // 0.5 * 127 = 63.5 -> 64; truncation would give 63.
        assertEquals(64, HackRfProtocol.toS8(0.5f).toInt())
        // -0.5 * 127 = -63.5; symmetric rounding, not a bias toward zero.
        assertEquals(-63, HackRfProtocol.toS8(-0.5f).toInt())
        // Small values must not collapse into a dead zone around zero.
        assertEquals(1, HackRfProtocol.toS8(0.006f).toInt())
        assertEquals(-1, HackRfProtocol.toS8(-0.006f).toInt())
    }

    @Test
    fun `quantizer clamps full scale without overflow`() {
        assertEquals(127, HackRfProtocol.toS8(1f).toInt())
        assertEquals(-127, HackRfProtocol.toS8(-1f).toInt())
        assertEquals(127, HackRfProtocol.toS8(3.5f).toInt())
        assertEquals(-127, HackRfProtocol.toS8(-3.5f).toInt())
        assertEquals(0, HackRfProtocol.toS8(0f).toInt())
    }

    @Test
    fun `round trip error stays within one lsb`() {
        var v = -1f
        while (v <= 1f) {
            val rt = HackRfProtocol.s8ToFloat(HackRfProtocol.toS8(v))
            assertTrue("v=$v rt=$rt", Math.abs(rt - v * 127f / 128f) <= 0.5f / 127f + 1e-6f)
            v += 0.01f
        }
    }

    // ---- sweep header plausibility -----------------------------------------

    private fun headerAt(offset: Int, freqHz: Long, size: Int = 64): ByteArray {
        val b = ByteArray(size)
        b[offset] = HackRfProtocol.SWEEP_MAGIC_0
        b[offset + 1] = HackRfProtocol.SWEEP_MAGIC_1
        for (k in 0 until 8) {
            b[offset + 2 + k] = ((freqHz ushr (8 * k)) and 0xFF).toByte()
        }
        return b
    }

    @Test
    fun `genuine header is accepted`() {
        val b = headerAt(0, 145_000_000L)
        assertTrue(HackRfProtocol.sweepHeaderPlausible(b, 0))
        assertEquals(145_000_000L, HackRfProtocol.sweepBlockFreqHz(b, 0))
    }

    @Test
    fun `saturated samples that mimic the marker are rejected`() {
        // A run of positive full-scale samples IS 0x7F 0x7F...: the marker
        // check alone would re-lock here and shift every following block.
        val b = ByteArray(64) { 0x7F }
        assertTrue(HackRfProtocol.isSweepBlock(b, 0))
        assertFalse(HackRfProtocol.sweepHeaderPlausible(b, 0))
    }

    @Test
    fun `marker followed by out-of-range frequency is rejected`() {
        val b = headerAt(0, HackRfProtocol.SWEEP_FREQ_MAX_HZ + 1)
        assertFalse(HackRfProtocol.sweepHeaderPlausible(b, 0))
        val neg = headerAt(0, -1L)
        assertFalse(HackRfProtocol.sweepHeaderPlausible(neg, 0))
    }

    @Test
    fun `header at the buffer edge does not read out of bounds`() {
        val b = ByteArray(HackRfProtocol.SWEEP_HEADER_SIZE - 1) { 0x7F }
        assertFalse(HackRfProtocol.sweepHeaderPlausible(b, 0))
    }
}
