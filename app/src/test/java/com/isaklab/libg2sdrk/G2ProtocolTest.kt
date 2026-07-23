package com.isaklab.libg2sdrk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Byte-exact contract of the Protocol 2 codec against the reference client
 * (Thetis ChannelMaster/network.c) — offsets, byte order and scalings.
 */
class G2ProtocolTest {

    // ---- discovery ----------------------------------------------------------

    @Test
    fun discovery_requestAndReplyRoundTrip() {
        val req = G2Protocol.discoveryRequest()
        assertEquals(60, req.size)
        assertEquals(0x02, req[4].toInt())
        for (i in req.indices) if (i != 4) assertEquals(0, req[i].toInt())

        // Saturn reply: cmd, MAC, board 10, protocol, gateware, DDC count.
        val reply = ByteArray(60)
        reply[4] = 0x02
        byteArrayOf(0x00, 0x1C, 0xC0.toByte(), 0x12, 0x34, 0x56).copyInto(reply, 5)
        reply[11] = 10; reply[12] = 39; reply[13] = 27; reply[20] = 7
        val info = G2Protocol.parseDiscoveryReply(reply, reply.size)!!
        assertEquals(10, info.boardId)
        assertEquals("00:1c:c0:12:34:56", info.mac)
        assertEquals(27, info.gatewareVersion)
        assertEquals(7, info.ddcCount)
        assertTrue(!info.busy)
        assertEquals("Saturn (ANAN-G2)", G2Protocol.Board.name(info.boardId))

        reply[4] = 0x03
        assertTrue(G2Protocol.parseDiscoveryReply(reply, reply.size)!!.busy)
        reply[4] = 0x77
        assertNull(G2Protocol.parseDiscoveryReply(reply, reply.size))
    }

    // ---- host -> radio commands --------------------------------------------

    @Test
    fun generalPacket_portPlanAndPaBit() {
        val st = G2Protocol.ControlState()
        var p = G2Protocol.generalPacket(st)
        assertEquals(60, p.size)
        assertEquals(0x00, p[4].toInt())
        assertEquals(1025, G2Protocol.be16(p, 5))    // RX specific
        assertEquals(1026, G2Protocol.be16(p, 7))    // TX specific
        assertEquals(1027, G2Protocol.be16(p, 9))    // high priority in
        assertEquals(1025, G2Protocol.be16(p, 11))   // high priority out
        assertEquals(1029, G2Protocol.be16(p, 15))   // TX IQ
        assertEquals(1035, G2Protocol.be16(p, 17))   // DDC0 base
        assertEquals(0, p[23].toInt())               // wideband off
        assertEquals(0, p[58].toInt())               // PA disabled -> bit0 clear

        st.paEnabled = true
        p = G2Protocol.generalPacket(st)
        // p2app: SetPAEnabled(byte & 1) — bit0 SET enables the PA.
        assertEquals(1, p[58].toInt() and 0x01)
        st.watchdogEnabled = true
        assertEquals(1, G2Protocol.generalPacket(st)[38].toInt())
    }

    @Test
    fun highPriorityPacket_coreFields() {
        val st = G2Protocol.ControlState().apply {
            run = true; mox = true
            rxFreqHz = 7_100_000; rx2FreqHz = 14_200_000; txFreqHz = 7_093_500
            txDrive = 200; ocOutputs = 0b0000100; stepAttenDb = 12
        }
        val p = G2Protocol.highPriorityPacket(st)
        assertEquals(G2Protocol.BUFLEN, p.size)
        assertEquals(0x03, p[4].toInt())                       // run | PTT
        assertEquals(7_100_000L, G2Protocol.be32(p, 9))        // DDC0
        assertEquals(14_200_000L, G2Protocol.be32(p, 13))      // DDC1
        assertEquals(7_093_500L, G2Protocol.be32(p, 329))      // DUC0
        assertEquals(200, p[345].toInt() and 0xFF)
        assertEquals(0b0000100, (p[1401].toInt() and 0xFF) shr 1)   // OC on C bits 1-7
        assertEquals(0, p[1403].toInt())                       // 1403 unread by p2app
        assertEquals(12, p[1442].toInt()); assertEquals(12, p[1443].toInt())
    }

    @Test
    fun alexWords_followFrequency() {
        // Reference example from p2app (saturnregisters.c bit maps): 40 m,
        // antenna 1 — RX idle 0x0120/0x0010, keyed 0x0920 with T/R set.
        val st = G2Protocol.ControlState().apply {
            rxFreqHz = 7_100_000; txFreqHz = 7_100_000; mox = false
            receiverCount = 1
        }
        assertEquals(0x0120, G2Protocol.txFilterWord(st))       // 60/40 LPF + Ant1
        assertEquals(0x0010, G2Protocol.rxFilterWord(st.rxFreqHz))  // 6-10 MHz BPF

        st.mox = true
        assertEquals(0x0920, G2Protocol.txFilterWord(st))       // + T/R relay

        // Band edges across the tables.
        assertEquals(G2Protocol.Alex.TX_LPF_160, G2Protocol.txLpfBit(1_850_000))
        assertEquals(G2Protocol.Alex.TX_LPF_30_20, G2Protocol.txLpfBit(14_200_000))
        assertEquals(G2Protocol.Alex.TX_LPF_6, G2Protocol.txLpfBit(50_100_000))
        assertEquals(G2Protocol.Alex.RX_BPF_1_2_5, G2Protocol.rxFilterWord(1_850_000))
        assertEquals(G2Protocol.Alex.RX_BPF_10_22, G2Protocol.rxFilterWord(14_200_000))
        assertEquals(G2Protocol.Alex.RX_PREAMP_6M, G2Protocol.rxFilterWord(50_100_000))
        assertEquals(G2Protocol.Alex.RX_BYPASS, G2Protocol.rxFilterWord(475_000))

        // Serialized as four BE u16 registers: 1428/1432 TX, 1430 RX2, 1434 RX1.
        val p = G2Protocol.highPriorityPacket(st)
        assertEquals(0x0920, G2Protocol.be16(p, 1428))
        assertEquals(0x0920, G2Protocol.be16(p, 1432))
        assertEquals(0x0000, G2Protocol.be16(p, 1430))          // RX2 off
        assertEquals(0x0010, G2Protocol.be16(p, 1434))

        st.receiverCount = 2; st.rx2FreqHz = 14_200_000
        assertEquals(G2Protocol.Alex.RX_BPF_10_22,
            G2Protocol.be16(G2Protocol.highPriorityPacket(st), 1430))
    }

    @Test
    fun rxSpecificPacket_ddcConfig() {
        val st = G2Protocol.ControlState().apply { sampleRate = 384_000; receiverCount = 2 }
        val p = G2Protocol.rxSpecificPacket(st)
        assertEquals(G2Protocol.BUFLEN, p.size)
        assertEquals(2, p[4].toInt())                 // ADCs
        assertEquals(0b11, p[7].toInt())              // DDC0+DDC1 enabled
        assertEquals(384, G2Protocol.be16(p, 18))     // DDC0 rate (kHz)
        assertEquals(24, p[22].toInt())               // 24-bit samples
        assertEquals(384, G2Protocol.be16(p, 24))     // DDC1 rate
        assertEquals(24, p[28].toInt())
    }

    @Test
    fun txSpecificPacket_ducConfig() {
        val p = G2Protocol.txSpecificPacket(G2Protocol.ControlState())
        assertEquals(60, p.size)
        assertEquals(1, p[4].toInt())                 // one DAC
        assertEquals(0, p[5].toInt())                 // EER/CW off for SSB-via-IQ
        assertEquals(192, G2Protocol.be16(p, 14))     // DUC rate kHz
    }

    @Test
    fun txIqPacket_packsSequenceAnd24BitSamples() {
        val iq = FloatArray(G2Protocol.TX_SAMPLES_PER_PACKET * 2)
        iq[0] = 0.5f; iq[1] = -0.25f                  // first I/Q pair
        val p = G2Protocol.txIqPacket(41L, iq, 0)
        assertEquals(G2Protocol.BUFLEN, p.size)
        assertEquals(41L, G2Protocol.be32(p, 0))
        // Reference encoding: round(sample * 8388607), 3-byte BE two's
        // complement; the wire carries the imaginary word first.
        val q = (p[4].toInt() shl 16) or ((p[5].toInt() and 0xFF) shl 8) or (p[6].toInt() and 0xFF)
        val i = (p[7].toInt() shl 16) or ((p[8].toInt() and 0xFF) shl 8) or (p[9].toInt() and 0xFF)
        assertEquals((0.5f * 8_388_607f).toInt(), i)
        assertEquals((-0.25f * 8_388_607f).toInt(), q)
    }

    // ---- radio -> host ------------------------------------------------------

    @Test
    fun status_parsesTelemetryAndVolts() {
        val s = ByteArray(G2Protocol.STATUS_LEN)
        s[4] = 0b0001_0001                            // PTT + PLL locked
        G2Protocol.putBE16(s, 4 + 10, 1234)           // fwd power
        G2Protocol.putBE16(s, 4 + 18, 56)             // rev power
        G2Protocol.putBE16(s, 4 + 45, 2544)           // supply volts raw (~13.8 V)
        val st = G2Protocol.parseStatus(s, s.size)!!
        assertTrue(st.pttIn); assertTrue(st.pllLocked); assertTrue(!st.dot)
        assertEquals(1234, st.forwardPower)
        assertEquals(56, st.reversePower)
        assertEquals(13.8, st.supplyVolts, 0.05)
        assertNull(G2Protocol.parseStatus(s, 59))     // strict length check
    }

    @Test
    fun ddcIq_decodesToneAtKnownOffset() {
        // Synthesize DDC packets carrying a -12 kHz tone at 48 kSps and prove
        // the decoded IQ phase advance matches — same method as the HL2 tests.
        val rate = 48_000
        val tone = -12_000
        val w = 2 * PI * tone / rate
        var phase = 0.0
        val out = ArrayList<Float>(8192)
        for (seq in 0 until 12L) {
            val p = ByteArray(G2Protocol.BUFLEN)
            G2Protocol.putBE32(p, 0, seq)
            G2Protocol.putBE16(p, 12, 24)
            G2Protocol.putBE16(p, 14, G2Protocol.IQ_SAMPLES_PER_PACKET)
            var o = G2Protocol.DDC_HEADER
            repeat(G2Protocol.IQ_SAMPLES_PER_PACKET) {
                // hardware wire order: imaginary word first
                G2Protocol.putS24(p, o, (0.5 * sin(phase)).toFloat())
                G2Protocol.putS24(p, o + 3, (0.5 * cos(phase)).toFloat())
                phase += w
                o += 6
            }
            val got = G2Protocol.parseDdcIq(p, p.size) { i, q -> out.add(i); out.add(q) }
            assertEquals(seq, got)
        }
        var acc = 0.0; var n = 0
        var pi = out[0].toDouble(); var pq = out[1].toDouble()
        var k = 2
        while (k + 1 < out.size) {
            val ci = out[k].toDouble(); val cq = out[k + 1].toDouble()
            acc += atan2(cq * pi - ci * pq, ci * pi + cq * pq); n++
            pi = ci; pq = cq; k += 2
        }
        val est = (acc / n) * rate / (2 * PI)
        assertEquals(tone.toDouble(), est, rate * 0.01)
        assertEquals(-1L, G2Protocol.parseDdcIq(ByteArray(100), 100) { _, _ -> })
    }

    @Test
    fun ddcPortMapping() {
        assertEquals(0, G2Protocol.ddcIndexOf(1035))
        assertEquals(6, G2Protocol.ddcIndexOf(1041))
        assertEquals(-1, G2Protocol.ddcIndexOf(1042))
        assertEquals(-1, G2Protocol.ddcIndexOf(1025))
    }

    @Test
    fun txInterpolator_unityDcGain() {
        // A keyed CW carrier (constant IQ) must reproduce exactly at 192 k.
        val interp = TxInterpolator()
        val out = FloatArray(8)
        var last = 0f
        repeat(50) {
            interp.process(0.7f, -0.3f, out, 0)
            last = out[6]
        }
        assertEquals(0.7f, last, 1e-4f)
    }
}
