/*
 * libcivk - Icom CI-V CAT driver for the iSDR driver host
 *
 * Copyright (C) 2026 Isak Ruas <isakruas@gmail.com>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 */
package com.isaklab.libcivk

import com.isaklab.libcivk.CivProtocol as P
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Byte-exact contract of the CI-V codec — framing, BCD, command bodies,
 * scope-waveform reassembly — checked against the published CI-V frame
 * format and the scope geometry of the current rig line.
 */
class CivProtocolTest {

    private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    // ---- BCD -----------------------------------------------------------------

    @Test
    fun `bcd le round trip and layout`() {
        // 14.074.000 Hz, the classic FT8 frequency, digit pairs low-first.
        val b = P.toBcdLe(14_074_000, 5)!!
        assertArrayEquals(bytes(0x00, 0x40, 0x07, 0x14, 0x00), b)
        assertEquals(14_074_000L, P.fromBcdLe(b))

        // Full 10 digits.
        val full = P.toBcdLe(9_999_999_999L, 5)!!
        assertArrayEquals(bytes(0x99, 0x99, 0x99, 0x99, 0x99), full)
        assertEquals(9_999_999_999L, P.fromBcdLe(full))

        // Zero is a legal frequency field.
        assertArrayEquals(bytes(0, 0, 0, 0, 0), P.toBcdLe(0, 5)!!)
    }

    @Test
    fun `bcd rejects overflow and bad nibbles`() {
        assertNull(P.toBcdLe(10_000_000_000L, 5))
        assertNull(P.fromBcdLe(bytes(0x0A)))
        assertNull(P.fromBcdLe(bytes(0xA0)))
        assertNull(P.fromBcdBe(bytes(0x1F)))
        assertEquals(1234L, P.fromBcdBe(bytes(0x12, 0x34)))
        assertArrayEquals(bytes(0x02, 0x55), P.toBcdBe2(255)!!)
        assertNull(P.toBcdBe2(10_000))
    }

    // ---- frame building --------------------------------------------------------

    @Test
    fun `write frequency exact bytes`() {
        val f = P.writeFrequency(0x94, 14_074_000)!!
        assertArrayEquals(
            bytes(0xFE, 0xFE, 0x94, 0xE0, 0x05, 0x00, 0x40, 0x07, 0x14, 0x00, 0xFD),
            f,
        )
    }

    @Test
    fun `write frequency rejects out of field`() {
        assertNull(P.writeFrequency(0x94, -1))
        assertNull(P.writeFrequency(0x94, 10_000_000_000L))
    }

    @Test
    fun `build frame rejects reserved bytes`() {
        // 0xFC..0xFE in the data area would desynchronise the bus.
        assertNull(P.buildFrame(0x94, 0xE0, bytes(0x05, 0xFE)))
        assertNull(P.buildFrame(0x94, 0xE0, bytes(0x05, 0xFD)))
        assertNull(P.buildFrame(0x94, 0xE0, bytes(0x05, 0xFC)))
        assertNull(P.buildFrame(0xFE, 0xE0, bytes(0x05)))
        assertNull(P.buildFrame(0x94, 0xFD, bytes(0x05)))
        assertNull(P.buildFrame(0x94, 0xE0, ByteArray(0)))
        assertNull(P.buildFrame(0x94, 0xE0, ByteArray(P.MAX_BODY + 1)))
        assertNotNull(P.buildFrame(0x94, 0xE0, bytes(0xFB)))
    }

    @Test
    fun `control command bodies`() {
        assertArrayEquals(
            bytes(0xFE, 0xFE, 0x94, 0xE0, 0x03, 0xFD),
            P.readFrequency(0x94),
        )
        assertArrayEquals(
            bytes(0xFE, 0xFE, 0x94, 0xE0, 0x06, 0x01, 0x02, 0xFD),
            P.writeMode(0x94, P.MODE_USB, 2)!!,
        )
        assertNull(P.writeMode(0x94, P.MODE_USB, 0))
        assertNull(P.writeMode(0x94, P.MODE_USB, 4))
        assertArrayEquals(
            bytes(0xFE, 0xFE, 0x94, 0xE0, 0x1C, 0x00, 0x01, 0xFD),
            P.setPtt(0x94, true),
        )
        assertArrayEquals(
            bytes(0xFE, 0xFE, 0x94, 0xE0, 0x1C, 0x00, 0x00, 0xFD),
            P.setPtt(0x94, false),
        )
        assertArrayEquals(
            bytes(0xFE, 0xFE, 0x94, 0xE0, 0x19, 0x00, 0xFD),
            P.readTransceiverId(0x94),
        )
        // Level 255 travels as 4-digit big-endian BCD.
        assertArrayEquals(
            bytes(0xFE, 0xFE, 0x94, 0xE0, 0x14, 0x01, 0x02, 0x55, 0xFD),
            P.setLevel(0x94, P.SUB_LEVEL_AF, 255),
        )
        assertArrayEquals(
            bytes(0xFE, 0xFE, 0x94, 0xE0, 0x15, 0x02, 0xFD),
            P.readMeter(0x94, P.SUB_METER_S),
        )
    }

    @Test
    fun `receive control command bodies`() {
        // Squelch 128: 4-digit big-endian BCD level.
        assertArrayEquals(
            bytes(0xFE, 0xFE, 0x94, 0xE0, 0x14, 0x03, 0x01, 0x28, 0xFD),
            P.setLevel(0x94, P.SUB_LEVEL_SQL, 128),
        )
        assertArrayEquals(
            bytes(0xFE, 0xFE, 0x94, 0xE0, 0x14, 0x02, 0x02, 0x55, 0xFD),
            P.setLevel(0x94, P.SUB_LEVEL_RF, 255),
        )
        assertArrayEquals(
            bytes(0xFE, 0xFE, 0x94, 0xE0, 0x14, 0x07, 0x01, 0x28, 0xFD),
            P.setLevel(0x94, P.SUB_LEVEL_PBT_IN, 128),
        )
        assertArrayEquals(
            bytes(0xFE, 0xFE, 0x94, 0xE0, 0x14, 0x08, 0x00, 0x00, 0xFD),
            P.setLevel(0x94, P.SUB_LEVEL_PBT_OUT, 0),
        )
        assertArrayEquals(
            bytes(0xFE, 0xFE, 0x94, 0xE0, 0x14, 0x06, 0x02, 0x55, 0xFD),
            P.setLevel(0x94, P.SUB_LEVEL_NR, 255),
        )

        // Functions carry one plain data byte.
        assertArrayEquals(
            bytes(0xFE, 0xFE, 0x94, 0xE0, 0x16, 0x40, 0x01, 0xFD),
            P.setFunc(0x94, P.SUB_FUNC_NR, 1),
        )
        assertArrayEquals(
            bytes(0xFE, 0xFE, 0x94, 0xE0, 0x16, 0x22, 0x00, 0xFD),
            P.setFunc(0x94, P.SUB_FUNC_NB, 0),
        )
        assertArrayEquals(
            bytes(0xFE, 0xFE, 0x94, 0xE0, 0x16, 0x41, 0x01, 0xFD),
            P.setFunc(0x94, P.SUB_FUNC_NOTCH_AUTO, 1),
        )
        assertArrayEquals(
            bytes(0xFE, 0xFE, 0x94, 0xE0, 0x16, 0x12, 0x02, 0xFD),
            P.setFunc(0x94, P.SUB_FUNC_AGC, P.AGC_MID),
        )
        assertArrayEquals(
            bytes(0xFE, 0xFE, 0x94, 0xE0, 0x16, 0x02, 0x01, 0xFD),
            P.setFunc(0x94, P.SUB_FUNC_PREAMP, 1),
        )
    }

    @Test
    fun `attenuator travels as one bcd byte`() {
        assertArrayEquals(
            bytes(0xFE, 0xFE, 0x94, 0xE0, 0x11, 0x12, 0xFD),
            P.setAttenuator(0x94, 12)!!,
        )
        assertArrayEquals(
            bytes(0xFE, 0xFE, 0x94, 0xE0, 0x11, 0x00, 0xFD),
            P.setAttenuator(0x94, 0)!!,
        )
        assertArrayEquals(
            bytes(0xFE, 0xFE, 0x94, 0xE0, 0x11, 0x45, 0xFD),
            P.setAttenuator(0x94, 45)!!,
        )
        assertNull(P.setAttenuator(0x94, 100))
    }

    @Test
    fun `width code tables per mode`() {
        // SSB/CW table: codes 0..9 are 50..500 in 50 Hz steps.
        assertEquals(0, P.widthCodeForMode(P.MODE_USB, 50))
        assertEquals(9, P.widthCodeForMode(P.MODE_CW, 500))
        // Codes 10..40 are 600..3600 in 100 Hz steps.
        assertEquals(10, P.widthCodeForMode(P.MODE_LSB, 600))
        assertEquals(28, P.widthCodeForMode(P.MODE_USB, 2400))
        assertEquals(40, P.widthCodeForMode(P.MODE_RTTY_R, 3600))
        // Snapping to the nearest code, clamped at the table ends.
        assertEquals(0, P.widthCodeForMode(P.MODE_USB, 1))
        assertEquals(28, P.widthCodeForMode(P.MODE_USB, 2380))
        assertEquals(40, P.widthCodeForMode(P.MODE_USB, 90_000))
        // AM table: codes 0..49 are 200..10000 in 200 Hz steps.
        assertEquals(0, P.widthCodeForMode(P.MODE_AM, 200))
        assertEquals(29, P.widthCodeForMode(P.MODE_AM, 6000))
        assertEquals(49, P.widthCodeForMode(P.MODE_AM, 10_000))
        assertEquals(49, P.widthCodeForMode(P.MODE_AM, 50_000))
        // FM has no width command.
        assertNull(P.widthCodeForMode(P.MODE_FM, 12_000))
    }

    @Test
    fun `filter width command carries the bcd code`() {
        // USB 2400 Hz = code 28 = BCD 0x28.
        assertArrayEquals(
            bytes(0xFE, 0xFE, 0x94, 0xE0, 0x1A, 0x03, 0x28, 0xFD),
            P.setFilterWidth(0x94, P.MODE_USB, 2400)!!,
        )
        // SSB/CW code 40 (3600 Hz) = BCD 0x40.
        assertArrayEquals(
            bytes(0xFE, 0xFE, 0x94, 0xE0, 0x1A, 0x03, 0x40, 0xFD),
            P.setFilterWidth(0x94, P.MODE_CW, 3600)!!,
        )
        // AM 6000 Hz = code 29 = BCD 0x29.
        assertArrayEquals(
            bytes(0xFE, 0xFE, 0x94, 0xE0, 0x1A, 0x03, 0x29, 0xFD),
            P.setFilterWidth(0x94, P.MODE_AM, 6000)!!,
        )
        assertNull(P.setFilterWidth(0x94, P.MODE_FM, 12_000))
    }

    @Test
    fun `scope command bodies`() {
        assertArrayEquals(
            bytes(0xFE, 0xFE, 0x94, 0xE0, 0x27, 0x10, 0x01, 0xFD),
            P.scopeOn(0x94, true),
        )
        assertArrayEquals(
            bytes(0xFE, 0xFE, 0x94, 0xE0, 0x27, 0x11, 0x01, 0xFD),
            P.scopeWaveOutput(0x94, true),
        )
        assertArrayEquals(
            bytes(0xFE, 0xFE, 0x94, 0xE0, 0x27, 0x14, 0x00, 0x00, 0xFD),
            P.scopeSetMode(0x94, 0, P.SCOPE_MODE_CENTER)!!,
        )
        assertNull(P.scopeSetMode(0x94, 2, 0))

        // 500 kHz span travels as its +/- half-span, 250 kHz, LE BCD.
        assertArrayEquals(
            bytes(0xFE, 0xFE, 0x94, 0xE0, 0x27, 0x15, 0x00, 0x00, 0x00, 0x25, 0x00, 0x00, 0xFD),
            P.scopeSetSpan(0x94, 0, 500_000)!!,
        )
        assertNull(P.scopeSetSpan(0x94, 2, 500_000))

        // -10.5 dB reference: 1050 centi-dB magnitude, sign byte 1.
        assertArrayEquals(
            bytes(0xFE, 0xFE, 0x94, 0xE0, 0x27, 0x19, 0x00, 0x10, 0x50, 0x01, 0xFD),
            P.scopeSetRef(0x94, 0, -21)!!,
        )
        assertArrayEquals(
            bytes(0xFE, 0xFE, 0x94, 0xE0, 0x27, 0x19, 0x00, 0x05, 0x00, 0x00, 0xFD),
            P.scopeSetRef(0x94, 0, 10)!!,
        )
        assertNull(P.scopeSetRef(0x94, 0, 41))
        assertNull(P.scopeSetRef(0x94, 0, -41))
    }

    // ---- deframing ----------------------------------------------------------

    @Test
    fun `deframer reassembles across arbitrary splits`() {
        val frame = P.writeFrequency(0x94, 7_074_000)!!
        val d = P.Deframer()
        val got = ArrayList<ByteArray>()
        for (b in frame) got.addAll(d.push(byteArrayOf(b)))
        assertEquals(1, got.size)
        assertArrayEquals(frame.copyOfRange(2, frame.size - 1), got[0])
    }

    @Test
    fun `deframer resyncs after noise and lost terminator`() {
        val d = P.Deframer()
        // Garbage before any preamble is ignored.
        assertTrue(d.push(bytes(0x12, 0x34, 0xFD)).isEmpty())
        // A frame whose terminator was lost is abandoned when the next
        // preamble arrives; the following frame survives.
        val stream = bytes(0xFE, 0xFE, 0x94, 0xE0, 0x03) + P.setPtt(0x94, true)
        val got = d.push(stream)
        assertEquals(1, got.size)
        assertArrayEquals(bytes(0x94, 0xE0, 0x1C, 0x00, 0x01), got[0])
    }

    @Test
    fun `deframer discards on collision`() {
        val d = P.Deframer()
        val stream = bytes(0xFE, 0xFE, 0x94, 0xE0, 0xFC) + P.readFrequency(0x94)
        val got = d.push(stream)
        assertEquals(1, got.size)
        assertArrayEquals(bytes(0x94, 0xE0, 0x03), got[0])
        assertEquals(1L, d.collisions)
    }

    @Test
    fun `deframer bounds body length`() {
        val d = P.Deframer()
        val stream = bytes(0xFE, 0xFE) + ByteArray(P.MAX_BODY + 10) { 0x11 } + bytes(0xFD)
        assertTrue(d.push(stream).isEmpty())
        assertEquals(1L, d.oversizeDrops)
    }

    @Test
    fun `parse ack nak and echo`() {
        assertEquals(P.Frame.Ack(0x94), P.parseFrame(bytes(0xE0, 0x94, 0xFB)))
        assertEquals(P.Frame.Nak(0x94), P.parseFrame(bytes(0xE0, 0x94, 0xFA)))
        assertNull(P.parseFrame(bytes(0xE0, 0x94)))

        val echo = P.parseFrame(bytes(0x94, 0xE0, 0x05, 0x00))!!
        assertTrue(P.isEcho(echo, P.CONTROLLER_ADDR))
        val reply = P.parseFrame(bytes(0xE0, 0x94, 0x03, 0x00))!!
        assertFalse(P.isEcho(reply, P.CONTROLLER_ADDR))
    }

    // ---- scope assembly ---------------------------------------------------------

    /** Header frame data (after `27 00`) for a centre-mode sweep. */
    private fun scopeHeader(id: Int, maxDiv: Int, center: Long, halfSpan: Long, oor: Int): ByteArray =
        bytes(id, 0x01, bcd1(maxDiv), 0x00) +
            P.toBcdLe(center, 5)!! + P.toBcdLe(halfSpan, 5)!! + bytes(oor)

    private fun scopeChunk(id: Int, div: Int, maxDiv: Int, data: ByteArray): ByteArray =
        bytes(id, bcd1(div), bcd1(maxDiv)) + data

    private fun bcd1(v: Int): Int {
        check(v <= 99)
        return ((v / 10) shl 4) or (v % 10)
    }

    @Test
    fun `scope full sweep center mode`() {
        // IC-7300 geometry: header + 9 full chunks of 50 + a final 25.
        val asm = P.ScopeAssembler(475)
        assertNull(asm.push(scopeHeader(0, 11, 14_100_000, 250_000, 0)))
        for (div in 2..10) {
            val chunk = ByteArray(50) { ((div + it) % 161).toByte() }
            assertNull(asm.push(scopeChunk(0, div, 11, chunk)))
        }
        val line = asm.push(scopeChunk(0, 11, 11, ByteArray(25) { 7 }))!!
        assertEquals(0, line.id)
        assertEquals(P.ScopeMode.CENTER, line.mode)
        assertEquals(13_850_000L, line.lowEdgeHz)
        assertEquals(14_350_000L, line.highEdgeHz)
        assertEquals(14_100_000L, line.centerHz())
        assertEquals(500_000L, line.spanHz())
        assertFalse(line.outOfRange)
        assertEquals(475, line.bins.size)
        assertEquals(7, line.bins[474].toInt())
        assertEquals(0L, asm.dropped)
    }

    @Test
    fun `scope fixed mode edges`() {
        val asm = P.ScopeAssembler(475)
        val hdr = bytes(0x00, 0x01, 0x02, 0x01) + // fixed mode, 2 divisions
            P.toBcdLe(7_000_000, 5)!! + P.toBcdLe(7_300_000, 5)!! + bytes(0x01)
        assertNull(asm.push(hdr))
        val line = asm.push(scopeChunk(0, 2, 2, bytes(1, 2, 3)))!!
        assertEquals(P.ScopeMode.FIXED, line.mode)
        assertEquals(7_000_000L, line.lowEdgeHz)
        assertEquals(7_300_000L, line.highEdgeHz)
        assertTrue(line.outOfRange)
        assertArrayEquals(bytes(1, 2, 3), line.bins)
    }

    @Test
    fun `scope drops bad bcd and inverted edges`() {
        val asm = P.ScopeAssembler(475)
        val corrupt = scopeHeader(0, 11, 14_100_000, 250_000, 0)
        corrupt[5] = 0xAB.toByte() // corrupt frequency digit
        assertNull(asm.push(corrupt))
        assertEquals(1L, asm.dropped)

        // Fixed mode with high edge below low edge.
        val inverted = bytes(0x00, 0x01, 0x02, 0x01) +
            P.toBcdLe(7_300_000, 5)!! + P.toBcdLe(7_000_000, 5)!! + bytes(0x00)
        assertNull(asm.push(inverted))
        assertEquals(2L, asm.dropped)

        // Unknown scope mode.
        val badMode = scopeHeader(0, 11, 14_100_000, 250_000, 0)
        badMode[3] = 0x07
        assertNull(asm.push(badMode))
        assertEquals(3L, asm.dropped)
    }

    @Test
    fun `scope drops out of sequence and overflow`() {
        val asm = P.ScopeAssembler(100)
        assertNull(asm.push(scopeHeader(0, 3, 14_100_000, 250_000, 0)))
        // Division 3 when 2 was expected: the sweep is abandoned...
        assertNull(asm.push(scopeChunk(0, 3, 3, ByteArray(10) { 1 })))
        assertEquals(1L, asm.dropped)
        // ...and a data frame with no header waits silently for the next sweep.
        assertNull(asm.push(scopeChunk(0, 2, 3, ByteArray(10) { 1 })))
        assertEquals(1L, asm.dropped)

        // More bins than the line holds.
        assertNull(asm.push(scopeHeader(0, 3, 14_100_000, 250_000, 0)))
        assertNull(asm.push(scopeChunk(0, 2, 3, ByteArray(60) { 1 })))
        assertNull(asm.push(scopeChunk(0, 3, 3, ByteArray(60) { 1 })))
        assertEquals(2L, asm.dropped)

        // The assembler recovers on the next clean sweep.
        assertNull(asm.push(scopeHeader(0, 2, 14_100_000, 250_000, 0)))
        assertNotNull(asm.push(scopeChunk(0, 2, 2, ByteArray(40) { 9 })))
    }

    @Test
    fun `scope rejects bad ids and short frames`() {
        val asm = P.ScopeAssembler(475)
        assertNull(asm.push(ByteArray(0)))
        assertNull(asm.push(bytes(0x00, 0x01)))
        assertNull(asm.push(scopeHeader(2, 11, 14_100_000, 250_000, 0)))
        // Division counter that is not BCD.
        assertNull(asm.push(bytes(0x00, 0x0A, 0x11, 0x00)))
        // maxDivision zero.
        assertNull(asm.push(bytes(0x00, 0x01, 0x00, 0x00)))
        assertEquals(5L, asm.dropped)
    }

    @Test
    fun `scope main and sub assemble independently`() {
        val asm = P.ScopeAssembler(100)
        assertNull(asm.push(scopeHeader(0, 2, 14_100_000, 250_000, 0)))
        assertNull(asm.push(scopeHeader(1, 2, 7_100_000, 25_000, 0)))
        val sub = asm.push(scopeChunk(1, 2, 2, ByteArray(10) { 5 }))!!
        assertEquals(1, sub.id)
        assertEquals(7_100_000L, sub.centerHz())
        val main = asm.push(scopeChunk(0, 2, 2, ByteArray(10) { 6 }))!!
        assertEquals(0, main.id)
        assertEquals(14_100_000L, main.centerHz())
    }

    @Test
    fun `bins to db maps the scale endpoints`() {
        val db = P.binsToDb(bytes(0, 80, 160, 200), 160, -80f, 0f)
        assertEquals(-80f, db[0], 0f)
        assertEquals(-40f, db[1], 0f)
        assertEquals(0f, db[2], 0f)
        // Above-scale values clamp instead of extrapolating.
        assertEquals(0f, db[3], 0f)
    }
}
