/*
 * libkenwoodk - Kenwood CAT driver for the iSDR driver host
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
package com.isaklab.libkenwoodk

import com.isaklab.libkenwoodk.KenwoodProtocol as P
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Byte-exact contract of the Kenwood codec — command strings, answer
 * parsing, segment splitting, KNS login encodings and bandscope frames —
 * checked against the published PC control command references.
 */
class KenwoodProtocolTest {

    // ---- core command builders ----------------------------------------------

    @Test
    fun `frequency commands exact ascii`() {
        assertEquals("FA;", P.getFa())
        assertEquals("FB;", P.getFb())
        assertEquals("FA00007074000;", P.setFa(7_074_000))
        assertEquals("FB00014074000;", P.setFb(14_074_000))
        assertEquals("FA00000000000;", P.setFa(0))
        assertEquals("FA99999999999;", P.setFa(99_999_999_999))
    }

    @Test
    fun `frequency field rejects out of range`() {
        assertNull(P.setFa(-1))
        assertNull(P.setFa(100_000_000_000))
        assertNull(P.setFb(-1))
    }

    @Test
    fun `frequency answers parse with strict width`() {
        assertEquals(7_074_000L, P.parseFa("FA00007074000"))
        assertEquals(14_074_000L, P.parseFb("FB00014074000"))
        // Wrong width, wrong prefix, non-digit content.
        assertNull(P.parseFa("FA0007074000"))
        assertNull(P.parseFa("FA000070740000"))
        assertNull(P.parseFa("FB00007074000"))
        assertNull(P.parseFa("FA0000707400O"))
    }

    @Test
    fun `om mode table and rejects`() {
        assertEquals("OM0;", P.getOm(0))
        assertEquals("OM1;", P.getOm(1))
        assertNull(P.getOm(2))
        assertEquals("OM02;", P.setOm(0, P.MODE_USB))
        assertEquals("OM11;", P.setOm(1, P.MODE_LSB))
        assertEquals("OM0F;", P.setOm(0, P.MODE_AM_D))
        assertEquals("OM0A;", P.setOm(0, P.MODE_PSK))
        // 0 and 8 are unassigned digits; nothing above F exists.
        assertNull(P.setOm(0, 0x0))
        assertNull(P.setOm(0, 0x8))
        assertNull(P.setOm(0, 0x10))

        assertEquals(Pair(0, P.MODE_CW), P.parseOm("OM03"))
        assertEquals(Pair(1, P.MODE_FM_D), P.parseOm("OM1E"))
        assertNull(P.parseOm("OM00"))
        assertNull(P.parseOm("OM08"))
        assertNull(P.parseOm("OM2"))
        assertNull(P.parseOm("OM031"))
        assertEquals("USB", P.modeName(P.MODE_USB))
        assertEquals("AM-D", P.modeName(P.MODE_AM_D))
        assertNull(P.modeName(0x8))
    }

    @Test
    fun `keying commands and state reports`() {
        assertEquals("TX0;", P.setTx(0))
        assertEquals("TX1;", P.setTx(1))
        assertEquals("TX2;", P.setTx(2))
        assertNull(P.setTx(3))
        assertEquals("RX;", P.setRx())
        assertEquals(true, P.parseTxState("TX0"))
        assertEquals(true, P.parseTxState("TX"))
        assertEquals(false, P.parseTxState("RX"))
        assertNull(P.parseTxState("TX7"))
        assertNull(P.parseTxState("RX0X"))
    }

    @Test
    fun `vfo select commands`() {
        assertEquals("FR0;", P.setFr(0))
        assertEquals("FT1;", P.setFt(1))
        assertEquals("FR3;", P.setFr(3))
        assertNull(P.setFr(2))
        assertNull(P.setFt(4))
        assertEquals("FR;", P.getFr())
        assertEquals("FT;", P.getFt())
        assertEquals(1, P.parseFr("FR1"))
        assertEquals(0, P.parseFt("FT0"))
        assertNull(P.parseFr("FR2"))
    }

    @Test
    fun `id ai sm and levels`() {
        assertEquals("ID;", P.getId())
        assertEquals(24, P.parseId("ID024"))
        assertEquals(23, P.parseId("ID023"))
        assertNull(P.parseId("ID24"))
        assertNull(P.parseId("ID0240"))

        assertEquals("AI2;", P.setAi2())

        assertEquals("SM;", P.getSm())
        assertEquals(0, P.parseSm("SM0000"))
        assertEquals(70, P.parseSm("SM0070"))
        assertNull(P.parseSm("SM0071"))
        assertNull(P.parseSm("SM007"))

        assertEquals("AG255;", P.setAg(255))
        assertEquals("AG000;", P.setAg(0))
        assertEquals("RG128;", P.setRg(128))
        assertEquals("SQ042;", P.setSq(42))
        assertEquals("AG;", P.getAg())
        assertEquals("RG;", P.getRg())
        assertEquals("SQ;", P.getSq())
        assertEquals(255, P.parseLevel("AG255", "AG"))
        assertEquals(0, P.parseLevel("SQ000", "SQ"))
        assertNull(P.parseLevel("AG256", "AG"))
        assertNull(P.parseLevel("AG25", "AG"))

        assertEquals("PS;", P.getPs())
        assertEquals(true, P.parsePs("PS1"))
        assertEquals(false, P.parsePs("PS0"))
    }

    @Test
    fun `meter enable report and swr calibration`() {
        assertEquals("RM11;", P.setRm(1, true))
        assertEquals("RM20;", P.setRm(2, false))
        assertNull(P.setRm(10, true))
        assertEquals(Pair(1, 23), P.parseRm("RM10023"))
        assertNull(P.parseRm("RM1002"))
        assertNull(P.parseRm("RM1002X"))

        // The documented breakpoints, and linear interpolation between them.
        assertEquals(1.0f, P.swrFromMeter(0), 0f)
        assertEquals(1.5f, P.swrFromMeter(11), 0f)
        assertEquals(2.0f, P.swrFromMeter(23), 0f)
        assertEquals(3.0f, P.swrFromMeter(35), 0f)
        assertTrue(P.swrFromMeter(70).isInfinite())
        assertTrue(P.swrFromMeter(99).isInfinite())
        val mid = P.swrFromMeter(17)
        assertTrue("17 dots interpolates to ~1.75, got $mid", kotlin.math.abs(mid - 1.75f) < 0.01f)
    }

    // ---- segment splitter ----------------------------------------------------

    @Test
    fun `splitter cuts concatenated segments`() {
        val s = P.Splitter()
        val got = s.push("FA00007074000;OM02;TX0;".toByteArray())
        assertEquals(
            listOf<P.Segment>(
                P.Segment.Cmd("FA00007074000"),
                P.Segment.Cmd("OM02"),
                P.Segment.Cmd("TX0"),
            ),
            got,
        )
    }

    @Test
    fun `splitter tolerates arbitrary chunk boundaries`() {
        val stream = "FA00007074000;OM02;".toByteArray()
        for (chunk in 1 until stream.size) {
            val s = P.Splitter()
            val got = ArrayList<P.Segment>()
            var off = 0
            while (off < stream.size) {
                val end = minOf(off + chunk, stream.size)
                got.addAll(s.push(stream.copyOfRange(off, end)))
                off = end
            }
            assertEquals(
                "chunk size $chunk",
                listOf<P.Segment>(
                    P.Segment.Cmd("FA00007074000"),
                    P.Segment.Cmd("OM02"),
                ),
                got,
            )
        }
    }

    @Test
    fun `splitter surfaces the error token and skips empty`() {
        val s = P.Splitter()
        val got = s.push("?;;FA00007074000;".toByteArray())
        assertEquals(
            listOf(P.Segment.Error, P.Segment.Cmd("FA00007074000")),
            got,
        )
    }

    @Test
    fun `splitter drops oversized and non text segments`() {
        val s = P.Splitter()
        assertTrue(s.push(ByteArray(P.MAX_SEGMENT + 10) { 'A'.code.toByte() }).isEmpty())
        assertEquals(1L, s.dropped)
        // The stream recovers after the terminator.
        val got = s.push(";ID;".toByteArray())
        assertEquals(listOf<P.Segment>(P.Segment.Cmd("ID")), got)

        val s2 = P.Splitter()
        assertTrue(s2.push(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), ';'.code.toByte())).isEmpty())
        assertEquals(1L, s2.dropped)
    }

    // ---- KNS handshake -------------------------------------------------------

    @Test
    fun `kns connect and replies`() {
        assertEquals("##CN;", P.knsConnect())
        assertEquals(true, P.parseKnsConnectReply("##CN1"))
        assertEquals(false, P.parseKnsConnectReply("##CN0"))
        assertNull(P.parseKnsConnectReply("##CN2"))
        assertEquals(true, P.parseKnsLoginReply("##ID1"))
        assertEquals(false, P.parseKnsLoginReply("##ID0"))
        assertNull(P.parseKnsLoginReply("##ID10"))
        assertEquals(3, P.parseKnsUe("##UE3"))
        assertNull(P.parseKnsUe("##UE"))
        assertEquals(true, P.parseKnsTi("##TI1"))
        assertEquals(false, P.parseKnsTi("##TI0"))
    }

    @Test
    fun `kns login ts890 worked example`() {
        // The command reference's worked example: admin, account "kenwood"
        // (7 chars), password "admin" (5 chars), 2-digit lengths.
        assertEquals("##ID00705kenwoodadmin;", P.knsLoginTs890(true, "kenwood", "admin"))
        // User type digit 1.
        assertEquals("##ID10402userpw;", P.knsLoginTs890(false, "user", "pw"))
        // Empty fields, embedded terminator, non-printable bytes.
        assertNull(P.knsLoginTs890(true, "", "x"))
        assertNull(P.knsLoginTs890(true, "x", ""))
        assertNull(P.knsLoginTs890(true, "a;b", "x"))
        assertNull(P.knsLoginTs890(true, "a b", "x"))
    }

    @Test
    fun `kns login ts990 worked example`() {
        // TS-990S shape: 1-digit lengths, no type digit, fields 1-8 chars.
        assertEquals("##ID75kenwoodadmin;", P.knsLoginTs990("kenwood", "admin"))
        assertNull(P.knsLoginTs990("ninechars", "pw"))
        assertNull(P.knsLoginTs990("acct", "ninechars"))
        assertNull(P.knsLoginTs990("", "pw"))
        assertEquals("##ID11ab;", P.knsLoginTs990("a", "b"))
    }

    // ---- bandscope -----------------------------------------------------------

    @Test
    fun `dd0 builder codes`() {
        assertEquals("DD00;", P.setDd0(0))
        assertEquals("DD01;", P.setDd0(1))
        assertEquals("DD05;", P.setDd0(5))
        assertNull(P.setDd0(6))
    }

    /** A full `##DD2` segment (terminator stripped) whose 640 bins are f(i). */
    private fun lanDd2(f: (Int) -> Int): String {
        val sb = StringBuilder("##DD2")
        for (i in 0 until P.SCOPE_BINS) sb.append("%02X".format(f(i)))
        return sb.toString()
    }

    @Test
    fun `lan dd2 parses a synthetic 1286 byte frame`() {
        // Wire frame = 5-char prefix + 1280 hex digits + ';' = 1286 bytes;
        // route it through the splitter to prove the sizes line up.
        val frame = (lanDd2 { it % 0x8D } + ";").toByteArray()
        assertEquals(1286, frame.size)
        val s = P.Splitter()
        val segs = s.push(frame)
        assertEquals(1, segs.size)
        val seg = (segs[0] as P.Segment.Cmd).body

        val bins = P.parseLanDd2(seg)!!
        assertEquals(P.SCOPE_BINS, bins.size)
        assertEquals(0, bins[0].toInt() and 0xFF)
        assertEquals(0x8C, bins[140].toInt() and 0xFF)
        // High nibble first: "1A" is 26, not 161.
        val seg2 = "##DD2" + "1A".repeat(P.SCOPE_BINS)
        assertTrue(P.parseLanDd2(seg2)!!.all { (it.toInt() and 0xFF) == 0x1A })
    }

    @Test
    fun `lan dd2 clamps at the floor and maps db endpoints`() {
        val seg = lanDd2 {
            when (it) {
                0 -> 0x00
                1 -> 0x8C
                2 -> 0xFF // above the floor: clamps, never extrapolates
                3 -> 0x46 // half scale
                else -> 0x00
            }
        }
        val bins = P.parseLanDd2(seg)!!
        assertEquals(0x8C, bins[2].toInt() and 0xFF)
        val db = P.binsToDb(bins)
        assertEquals(0.0f, db[0], 0f)
        assertEquals(-100.0f, db[1], 0f)
        assertEquals(-100.0f, db[2], 0f)
        assertTrue(kotlin.math.abs(db[3] + 50.0f) < 0.5f)
    }

    @Test
    fun `lan dd2 rejects wrong length and non hex`() {
        val good = lanDd2 { 1 }
        assertNull(P.parseLanDd2(good.substring(0, good.length - 1)))
        assertNull(P.parseLanDd2(good + "00"))
        val bad = good.substring(0, 10) + "G" + good.substring(11)
        assertNull(P.parseLanDd2(bad))
        assertNull(P.parseLanDd2("DD2000102"))
    }

    private fun serialSplit(split: Int, fill: Int): String =
        "DD2%02d%s".format(split, "%02X".format(fill).repeat(20))

    @Test
    fun `serial dd2 split parses and validates`() {
        val (split, bins) = P.parseSerialDd2(serialSplit(7, 0x22))!!
        assertEquals(7, split)
        assertTrue(bins.size == 20 && bins.all { (it.toInt() and 0xFF) == 0x22 })
        // Split index out of range, wrong payload width, non-hex digit.
        assertNull(P.parseSerialDd2(serialSplit(32, 0x22)))
        assertNull(P.parseSerialDd2("DD207001122"))
        val good = serialSplit(7, 0x22)
        val bad = good.substring(0, 6) + "Z" + good.substring(7)
        assertNull(P.parseSerialDd2(bad))
    }

    @Test
    fun `serial assembler reassembles all 32 splits`() {
        val asm = P.SerialScopeAssembler()
        for (split in 0 until 31) {
            assertNull(asm.push(split, ByteArray(20) { split.toByte() }))
        }
        val line = asm.push(31, ByteArray(20) { 31 })
        assertNotNull("split 31 completes the line", line)
        assertEquals(P.SCOPE_BINS, line!!.size)
        assertEquals(0, line[0].toInt())
        assertEquals(31, line[639].toInt())
        assertEquals(5, line[5 * 20].toInt())
        assertEquals(0L, asm.dropped)
    }

    @Test
    fun `serial assembler drops out of sequence and recovers`() {
        val asm = P.SerialScopeAssembler()
        assertNull(asm.push(0, ByteArray(20) { 1 }))
        assertNull(asm.push(1, ByteArray(20) { 1 }))
        // Split 3 when 2 was expected: the line is abandoned...
        assertNull(asm.push(3, ByteArray(20) { 1 }))
        assertEquals(1L, asm.dropped)
        // ...and later splits with no line pending stay silent.
        assertNull(asm.push(4, ByteArray(20) { 1 }))
        // Split 0 always restarts cleanly.
        for (split in 0 until 32) {
            val done = asm.push(split, ByteArray(20) { 9 })
            assertEquals(split == 31, done != null)
        }
        // A restart mid-line counts the abandoned line.
        assertNull(asm.push(0, ByteArray(20) { 1 }))
        assertNull(asm.push(0, ByteArray(20) { 2 }))
        assertEquals(2L, asm.dropped)
    }

    // ---- DD4 header and edge derivation --------------------------------------

    @Test
    fun `dd4 header parses center and fixed shapes`() {
        // Centre mode: span 100 kHz around 14.1 MHz, in range.
        var seg = "DD40%011d%011d0".format(100_000, 14_100_000)
        assertEquals(27, seg.length)
        var hdr = P.parseDd4Header(seg)!!
        assertEquals(P.BandscopeMode.CENTER, hdr.mode)
        assertEquals(100_000L, hdr.field1Hz)
        assertEquals(14_100_000L, hdr.field2Hz)
        assertTrue(!hdr.outOfRange)
        assertEquals(Pair(14_050_000L, 14_150_000L), hdr.edges(false))
        assertEquals(Pair(14_000_000L, 14_200_000L), hdr.edges(true))

        // Fixed mode: low/high edges, out of range.
        seg = "DD41%011d%011d1".format(7_000_000, 7_300_000)
        hdr = P.parseDd4Header(seg)!!
        assertEquals(P.BandscopeMode.FIXED, hdr.mode)
        assertTrue(hdr.outOfRange)
        assertEquals(Pair(7_000_000L, 7_300_000L), hdr.edges(false))

        // Unknown scope-mode digit, and a short frame.
        assertNull(P.parseDd4Header("DD43%011d%011d0".format(7_000_000, 7_300_000)))
        assertNull(P.parseDd4Header("DD40%011d%010d0".format(100_000, 14_100_000)))
    }

    @Test
    fun `bs answers parse`() {
        assertEquals("BS3;", P.getBs3())
        assertEquals(P.BandscopeMode.CENTER, P.parseBs3("BS30"))
        assertEquals(P.BandscopeMode.FIXED, P.parseBs3("BS31"))
        assertEquals(P.BandscopeMode.AUTO_SCROLL, P.parseBs3("BS32"))
        assertNull(P.parseBs3("BS33"))

        assertEquals("BS4;", P.getBs4())
        assertEquals("BS42;", P.setBs4(2))
        assertNull(P.setBs4(7))
        assertEquals(0, P.parseBs4("BS40"))
        assertEquals(6, P.parseBs4("BS46"))
        assertNull(P.parseBs4("BS47"))
        assertEquals(5_000L, P.bs4SpanHz(0))
        assertEquals(500_000L, P.bs4SpanHz(6))
        assertNull(P.bs4SpanHz(7))

        assertEquals("BSM0;", P.getBsm0())
        assertEquals(Pair(7_000_000L, 7_300_000L), P.parseBsm0("BSM00700000007300000"))
        assertNull(P.parseBsm0("BSM00730000007000000")) // inverted
        assertNull(P.parseBsm0("BSM0070000000730000"))

        assertEquals("BSO;", P.getBso())
        assertEquals(true, P.parseBso("BSO1"))
        assertEquals(false, P.parseBso("BSO0"))

        assertEquals("BSC;", P.getBsc())
        assertEquals(140, P.parseBsc("BSC140"))
        assertNull(P.parseBsc("BSC141"))
    }

    @Test
    fun `edge derivation center fixed and expand`() {
        // Centre mode from the BS4 span code around the tuned frequency.
        assertEquals(
            Pair(14_050_000L, 14_150_000L),
            P.deriveEdges(P.BandscopeMode.CENTER, 14_100_000, 100_000L, null, false),
        )
        // EXPAND on: the streamed data covers more than the displayed span;
        // the derived span widens by the documented lower-bound factor.
        assertEquals(
            Pair(14_000_000L, 14_200_000L),
            P.deriveEdges(P.BandscopeMode.CENTER, 14_100_000, 100_000L, null, true),
        )
        // Fixed and auto-scroll use the BSM0 limits.
        assertEquals(
            Pair(7_000_000L, 7_300_000L),
            P.deriveEdges(P.BandscopeMode.FIXED, 0, null, Pair(7_000_000L, 7_300_000L), false),
        )
        assertEquals(
            Pair(6_850_000L, 7_450_000L),
            P.deriveEdges(
                P.BandscopeMode.AUTO_SCROLL, 0, null, Pair(7_000_000L, 7_300_000L), true,
            ),
        )
        // Missing or degenerate inputs derive nothing.
        assertNull(P.deriveEdges(P.BandscopeMode.CENTER, 14_100_000, null, null, false))
        assertNull(P.deriveEdges(P.BandscopeMode.CENTER, 0, 100_000L, null, false))
        assertNull(P.deriveEdges(P.BandscopeMode.FIXED, 0, null, null, false))
        assertNull(
            P.deriveEdges(P.BandscopeMode.FIXED, 0, null, Pair(7_300_000L, 7_000_000L), false),
        )
    }

    // ---- receive controls ----------------------------------------------------

    @Test
    fun `agc gc command exact ascii`() {
        assertEquals("GC;", P.getGc())
        assertEquals("GC0;", P.setGc(0))
        assertEquals("GC1;", P.setGc(1))
        assertEquals("GC2;", P.setGc(2))
        assertEquals("GC3;", P.setGc(3))
        // 4 (OFF-to-ON) is set-only sugar the driver never sends.
        assertNull(P.setGc(4))
        assertEquals(0, P.parseGc("GC0"))
        assertEquals(3, P.parseGc("GC3"))
        assertNull(P.parseGc("GC4"))
        assertNull(P.parseGc("GC"))
        assertNull(P.parseGc("GC10"))
    }

    @Test
    fun `noise reduction nr and rl1 exact ascii`() {
        assertEquals("NR;", P.getNr())
        assertEquals("NR0;", P.setNr(0))
        assertEquals("NR1;", P.setNr(1))
        assertEquals("NR2;", P.setNr(2))
        assertNull(P.setNr(3))
        assertEquals(2, P.parseNr("NR2"))
        assertNull(P.parseNr("NR3"))

        assertEquals("RL1;", P.getRl1())
        assertEquals("RL101;", P.setRl1(1))
        assertEquals("RL110;", P.setRl1(10))
        assertNull(P.setRl1(0))
        assertNull(P.setRl1(11))
        assertEquals(7, P.parseRl1("RL107"))
        assertNull(P.parseRl1("RL100"))
        assertNull(P.parseRl1("RL111"))
        assertNull(P.parseRl1("RL17"))
    }

    @Test
    fun `noise blanker nb1 exact ascii`() {
        assertEquals("NB1;", P.getNb1())
        assertEquals("NB10;", P.setNb1(false))
        assertEquals("NB11;", P.setNb1(true))
        assertEquals(false, P.parseNb1("NB10"))
        assertEquals(true, P.parseNb1("NB11"))
        assertNull(P.parseNb1("NB1"))
        assertNull(P.parseNb1("NB111"))
    }

    @Test
    fun `beat cancel bc exact ascii`() {
        assertEquals("BC;", P.getBc())
        assertEquals("BC0;", P.setBc(0))
        assertEquals("BC1;", P.setBc(1))
        assertEquals("BC2;", P.setBc(2))
        assertNull(P.setBc(3))
        assertEquals(1, P.parseBc("BC1"))
        assertNull(P.parseBc("BC3"))
    }

    @Test
    fun `preamp pa exact ascii`() {
        assertEquals("PA;", P.getPa())
        assertEquals("PA0;", P.setPa(0))
        assertEquals("PA2;", P.setPa(2))
        assertNull(P.setPa(3))
        assertEquals(2, P.parsePa("PA2"))
        assertNull(P.parsePa("PA3"))
    }

    @Test
    fun `attenuator ra codes and db snapping`() {
        assertEquals("RA;", P.getRa())
        assertEquals("RA0;", P.setRa(0))
        assertEquals("RA3;", P.setRa(3))
        assertNull(P.setRa(4))
        assertEquals(2, P.parseRa("RA2"))
        assertNull(P.parseRa("RA4"))
        // Steps 0/6/12/18 dB; snapping picks the nearest, ties round down.
        assertEquals(0, P.raCodeForDb(0))
        assertEquals(0, P.raCodeForDb(2))
        assertEquals(1, P.raCodeForDb(6))
        assertEquals(1, P.raCodeForDb(9))
        assertEquals(2, P.raCodeForDb(10))
        assertEquals(3, P.raCodeForDb(18))
        assertEquals(3, P.raCodeForDb(40))
    }

    @Test
    fun `receive filter fl0 exact ascii`() {
        assertEquals("FL0;", P.getFl0())
        assertEquals("FL00;", P.setFl0(0))
        assertEquals("FL01;", P.setFl0(1))
        assertEquals("FL02;", P.setFl0(2))
        assertNull(P.setFl0(3))
        // The answer carries the trailing 270 Hz-option digit; a bare
        // selection digit is also accepted.
        assertEquals(1, P.parseFl0("FL011"))
        assertEquals(2, P.parseFl0("FL020"))
        assertEquals(0, P.parseFl0("FL00"))
        assertNull(P.parseFl0("FL03"))
        assertNull(P.parseFl0("FL012x"))
        assertNull(P.parseFl0("FL0"))
        assertNull(P.parseFl0("FL015"))
    }

    @Test
    fun `sl width ladders snap per mode`() {
        assertEquals("SL0;", P.getSl0())
        assertEquals("SL000;", P.setSl0(0))
        assertEquals("SL018;", P.setSl0(18))
        assertEquals("SL035;", P.setSl0(35))
        assertNull(P.setSl0(36))
        assertEquals(7, P.parseSl0("SL007"))
        assertNull(P.parseSl0("SL036"))
        assertNull(P.parseSl0("SL07"))

        // CW ladder: code = index into the documented width table.
        assertEquals(Pair(0, 50), P.slWidthCode(P.MODE_CW, 50))
        assertEquals(Pair(10, 500), P.slWidthCode(P.MODE_CW, 510))
        assertEquals(Pair(18, 2500), P.slWidthCode(P.MODE_CW_R, 9_000))
        // FSK ladder.
        assertEquals(Pair(0, 250), P.slWidthCode(P.MODE_FSK, 100))
        assertEquals(Pair(6, 1000), P.slWidthCode(P.MODE_FSK_R, 900))
        assertEquals(Pair(7, 1500), P.slWidthCode(P.MODE_FSK, 1500))
        // PSK ladder.
        assertEquals(Pair(26, 3000), P.slWidthCode(P.MODE_PSK, 3000))
        assertEquals(Pair(16, 1200), P.slWidthCode(P.MODE_PSK_R, 1250))
        // Modes where SL is a cut frequency (or menu-dependent) have no ladder.
        assertNull(P.slWidthCode(P.MODE_USB, 2400))
        assertNull(P.slWidthCode(P.MODE_AM, 6000))
        assertNull(P.slWidthCode(P.MODE_FM, 10_000))
        assertNull(P.slWidthCode(P.MODE_CW, 0))
    }
}
