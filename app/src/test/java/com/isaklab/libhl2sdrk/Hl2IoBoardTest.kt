/*
 * libhl2sdrk - N2ADR IO board codec tests.
 *
 * Copyright (C) 2026 Isak Ruas <isakruas@gmail.com>. GPL v2 or later.
 */

package com.isaklab.libhl2sdrk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Hl2IoBoardTest {

    /** The logarithmic frequency code must match the board firmware's table. */
    @Test
    fun fcodeMatchesBoardFirmware() {
        assertEquals(0, IoBoard.fcode(0L))
        assertEquals(1, IoBoard.fcode(10_000L))
        assertEquals(255, IoBoard.fcode(280_000_000_000L))
        assertEquals(71, IoBoard.fcode(1_900_000L))    // 160 m
        assertEquals(82, IoBoard.fcode(3_700_000L))    // 80 m
        assertEquals(92, IoBoard.fcode(7_100_000L))    // 40 m
        assertEquals(103, IoBoard.fcode(14_200_000L))  // 20 m
        assertEquals(109, IoBoard.fcode(21_200_000L))  // 15 m
        assertEquals(113, IoBoard.fcode(28_500_000L))  // 10 m
        assertEquals(122, IoBoard.fcode(50_100_000L))  // 6 m
    }

    /** Codes are monotonic in frequency and roundtrip into the same band. */
    @Test
    fun fcodeIsMonotonicAndRoundtrips() {
        var last = 0
        for (hz in longArrayOf(
            20_000, 500_000, 1_900_000, 3_700_000, 7_100_000, 14_200_000,
            21_200_000, 28_500_000, 50_100_000, 144_200_000, 432_100_000,
        )) {
            val code = IoBoard.fcode(hz)
            assertTrue("fcode must grow with frequency", code > last)
            last = code
            val back = IoBoard.fcodeToHertz(code)
            // A code identifies the band: the decoded frequency stays within
            // the code's ±3.3% quantization step of the original.
            assertTrue("decode of $hz drifted to $back", back in (hz * 93 / 100)..(hz * 107 / 100))
        }
    }

    /**
     * The I2C passthrough word must match what the gateware's i2c_bus2.v
     * decodes: magic 0x03 in [31:25], read flag in [24], device in [22:16],
     * register in [15:8], value in [7:0].
     */
    @Test
    fun i2cPassthroughWordLayout() {
        val w = Hl2Protocol.i2cPassthroughWord(read = false, device = IoBoard.ADDRESS, reg = 4, value = 0xA8)
        assertEquals(0x03, (w ushr 25) and 0x7F)
        assertEquals(0, (w ushr 24) and 0x01)
        assertEquals(0x1D, (w ushr 16) and 0x7F)
        assertEquals(4, (w ushr 8) and 0xFF)
        assertEquals(0xA8, w and 0xFF)
        // A 40 m TX-frequency commit write (7 100 000 Hz, LSB 0x60), byte for byte.
        assertEquals(0x061D0460, Hl2Protocol.i2cPassthroughWord(false, 0x1D, 4, 7_100_000 and 0xFF))
    }

    /** Register map pins the wire contract with the board firmware. */
    @Test
    fun registerMap() {
        assertEquals(0x1D, IoBoard.ADDRESS)
        assertEquals(0, IoBoard.REG_TX_FREQ_BYTE4)
        assertEquals(4, IoBoard.REG_TX_FREQ_BYTE0)
        assertEquals(5, IoBoard.REG_CONTROL)
        assertEquals(11, IoBoard.REG_RF_INPUTS)
        assertEquals(13, IoBoard.REG_FCODE_RX1)
        assertEquals(32, IoBoard.REG_OP_MODE)
    }
}
