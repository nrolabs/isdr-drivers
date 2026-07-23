package com.isaklab.libhl2sdrk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Test

/**
 * JVM unit tests for the Hermes-Lite 2 wire codec. They reproduce exactly what
 * the reference emulator (tools/hl2-emulator) puts on the wire, so the driver
 * is validated without any hardware.
 */
class Hl2ProtocolTest {

    private fun s24be(v: Int) = byteArrayOf(
        ((v shr 16) and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte()
    )

    /** Build a board->host RX frame carrying a complex tone, like the emulator. */
    private fun buildRxFrame(seq: Long, toneHz: Int, rate: Int, startPhase: Double): ByteArray {
        val buf = ByteArray(Hl2Protocol.FRAME)
        buf[0] = 0xEF.toByte(); buf[1] = 0xFE.toByte(); buf[2] = 0x01; buf[3] = 0x06
        Hl2Protocol.putBE32(buf, 4, seq)
        val amp = (0.5 * (1 shl 23)).toInt()
        val w = 2 * PI * toneHz / rate
        var phase = startPhase
        for ((si, base) in intArrayOf(8, 520).withIndex()) {
            buf[base] = 0x7F; buf[base + 1] = 0x7F; buf[base + 2] = 0x7F
            buf[base + 3] = (if (si == 0) (1 shl 3) else (2 shl 3)).toByte()  // telemetry bank
            var p = base + 8
            repeat(Hl2Protocol.SAMPLES_PER_SUBFRAME) {
                val i = s24be((amp * cos(phase)).toInt())
                val q = s24be((amp * sin(phase)).toInt())
                phase += w
                // hardware wire order: imaginary word first (hl2.cxx)
                System.arraycopy(q, 0, buf, p, 3)
                System.arraycopy(i, 0, buf, p + 3, 3)
                buf[p + 6] = 0; buf[p + 7] = 0
                p += 8
            }
        }
        return buf
    }

    @Test
    fun rxFrame_decodesToneAtKnownOffset() {
        val rate = 48000
        val tone = -12000
        val samples = ArrayList<Float>(4096)
        var phase = 0.0
        val w = 2 * PI * tone / rate
        for (seq in 0 until 20L) {
            val frame = buildRxFrame(seq, tone, rate, phase)
            assertTrue(Hl2Protocol.isRxFrame(frame, frame.size))
            Hl2Protocol.parseRxFrame(frame, 1) { _, i, q -> samples.add(i); samples.add(q) }
            phase += w * 2 * Hl2Protocol.SAMPLES_PER_SUBFRAME
        }
        // Estimate the tone from the average phase advance between IQ samples.
        var acc = 0.0; var n = 0
        var pi = samples[0].toDouble(); var pq = samples[1].toDouble()
        var k = 2
        while (k + 1 < samples.size) {
            val ci = samples[k].toDouble(); val cq = samples[k + 1].toDouble()
            // d = cur * conj(prev)
            val dr = ci * pi + cq * pq
            val di = cq * pi - ci * pq
            acc += atan2(di, dr); n++
            pi = ci; pq = cq; k += 2
        }
        val est = (acc / n) * rate / (2 * PI)
        assertEquals(tone.toDouble(), est, rate * 0.01)  // within 1% of Fs
    }

    /** Build a 2-receiver RX frame: 14-byte slots (I1 Q1 I2 Q2 mic), 36/subframe. */
    private fun buildRx2Frame(seq: Long, tone1: Int, tone2: Int, rate: Int,
                              p1: Double, p2: Double): Pair<ByteArray, DoubleArray> {
        val buf = ByteArray(Hl2Protocol.FRAME)
        buf[0] = 0xEF.toByte(); buf[1] = 0xFE.toByte(); buf[2] = 0x01; buf[3] = 0x06
        Hl2Protocol.putBE32(buf, 4, seq)
        val amp = (0.5 * (1 shl 23)).toInt()
        val w1 = 2 * PI * tone1 / rate
        val w2 = 2 * PI * tone2 / rate
        var ph1 = p1; var ph2 = p2
        for (base in intArrayOf(8, 520)) {
            buf[base] = 0x7F; buf[base + 1] = 0x7F; buf[base + 2] = 0x7F
            var p = base + 8
            repeat(36) {
                System.arraycopy(s24be((amp * sin(ph1)).toInt()), 0, buf, p, 3)
                System.arraycopy(s24be((amp * cos(ph1)).toInt()), 0, buf, p + 3, 3)
                System.arraycopy(s24be((amp * sin(ph2)).toInt()), 0, buf, p + 6, 3)
                System.arraycopy(s24be((amp * cos(ph2)).toInt()), 0, buf, p + 9, 3)
                ph1 += w1; ph2 += w2
                p += 14
            }
        }
        return buf to doubleArrayOf(ph1, ph2)
    }

    @Test
    fun rxFrame_twoReceiversAreSeparated() {
        val rate = 48000
        val t1 = 8000; val t2 = -5000
        val s1 = ArrayList<Float>(); val s2 = ArrayList<Float>()
        var p1 = 0.0; var p2 = 0.0
        for (seq in 0 until 30L) {
            val (frame, ph) = buildRx2Frame(seq, t1, t2, rate, p1, p2)
            Hl2Protocol.parseRxFrame(frame, 2) { rx, i, q ->
                if (rx == 0) { s1.add(i); s1.add(q) } else { s2.add(i); s2.add(q) }
            }
            p1 = ph[0]; p2 = ph[1]
        }
        assertEquals(s1.size, s2.size)
        assertEquals(t1.toDouble(), freqOf(s1), rate * 0.01)
        assertEquals(t2.toDouble(), freqOf(s2), rate * 0.01)
    }

    private fun freqOf(iq: List<Float>): Double {
        var acc = 0.0; var n = 0
        var k = 2
        var pi = iq[0].toDouble(); var pq = iq[1].toDouble()
        while (k + 1 < iq.size) {
            val ci = iq[k].toDouble(); val cq = iq[k + 1].toDouble()
            acc += atan2(cq * pi - ci * pq, ci * pi + cq * pq); n++
            pi = ci; pq = cq; k += 2
        }
        return (acc / n) * 48000 / (2 * PI)
    }

    @Test
    fun controlFrame_encodesBanksThatDecodeBack() {
        val st = Hl2Protocol.ControlState().apply {
            rxFreqHz[0] = 14_074_000L
            txFreqHz = 7_074_000L
            sampleRate = 192000
            lnaGainDb = 30
            txDrive = 200
            paEnabled = true
            mox = true
            receiverCount = 4
            rxFreqHz[1] = 10_136_000L
            rxFreqHz[2] = 18_100_000L
            rxFreqHz[3] = 21_074_000L
        }
        // Walk all bank pairs (0,2,4,6,8,10) as the driver rotates them.
        val decoded = HashMap<Int, ByteArray>()
        var seq = 0L
        for (c0Index in intArrayOf(0, 2, 4, 6, 8, 10)) {
            val f = Hl2Protocol.buildControlFrame(seq++, c0Index, st, null)
            assertEquals(0xEF.toByte(), f[0]); assertEquals(0x02.toByte(), f[3])
            for (base in intArrayOf(11, 523)) {
                assertEquals(0x7F.toByte(), f[base - 1])
                val c0 = f[base].toInt() and 0xFF
                assertEquals(1, c0 and 0x01)                  // MOX bit set
                decoded[c0 shr 1] = f.copyOfRange(base + 1, base + 5)
            }
        }
        // addr1 = TX freq, addr2 = RX freq, addr0 config speed, addr9 drive/PA, addr0A LNA
        assertEquals(7_074_000L, be32(decoded[1]!!))
        assertEquals(14_074_000L, be32(decoded[2]!!))
        assertEquals(2, decoded[0]!![0].toInt() and 0x03)     // speed for 192k
        assertEquals(0x04, decoded[0]!![3].toInt() and 0x04)  // duplex on
        assertEquals(10_136_000L, be32(decoded[3]!!))         // addr3 = RX2 freq
        assertEquals(18_100_000L, be32(decoded[4]!!))         // addr4 = RX3 freq
        assertEquals(21_074_000L, be32(decoded[5]!!))         // addr5 = RX4 freq
        assertEquals(3, (decoded[0]!![3].toInt() shr 3) and 0x07)  // 4 receivers (n-1)
        assertEquals(200, decoded[9]!![0].toInt() and 0xFF)   // drive
        assertTrue(decoded[9]!![1].toInt() and 0x08 != 0)     // PA enabled
        val lna = decoded[10]!![3].toInt() and 0xFF
        assertTrue(lna and 0x40 != 0)
        assertEquals(30, (lna and 0x3F) - 12)                 // LNA gain
    }

    @Test
    fun controlFrame_addr9CarriesVnaAndTrDisableBits() {
        val st = Hl2Protocol.ControlState().apply {
            txDrive = 100; paEnabled = true; vnaMode = true; trDisable = true
        }
        val f = Hl2Protocol.buildControlFrame(0, 8, st, null)
        // addr9 is the second sub-frame of bank pair 8; its C2 is byte 525.
        val c2 = f[525].toInt() and 0xFF
        assertTrue("PA bit19", c2 and 0x08 != 0)
        assertTrue("VNA bit23", c2 and 0x80 != 0)
        assertTrue("TR-disable bit18", c2 and 0x04 != 0)
        assertEquals(100, f[524].toInt() and 0xFF)            // drive unchanged

        // All flags off -> only-drive frame has a clean C2.
        val off = Hl2Protocol.buildControlFrame(0, 8, Hl2Protocol.ControlState().apply { txDrive = 100 }, null)
        assertEquals(0, off[525].toInt() and 0xFF)
    }

    @Test
    fun controlFrame_addr0CarriesOpenCollectorOutputs() {
        // OC0 + OC6 set (one-hot filter select forwarded to the filter board).
        val st = Hl2Protocol.ControlState().apply { ocOutputs = 0b100_0001 }
        val f = Hl2Protocol.buildControlFrame(0, 0, st, null)
        val c2 = f[13].toInt() and 0xFF                 // addr0 C2 = first sub-frame byte 13
        assertEquals(0b100_0001, (c2 shr 1) and 0x7F)   // data[23:17] = C2[7:1]
        assertEquals(0, c2 and 0x01)                    // C2 bit0 stays clear

        // Only 7 bits reach the wire; higher bits are masked off.
        val wide = Hl2Protocol.buildControlFrame(0, 0, Hl2Protocol.ControlState().apply { ocOutputs = 0xFF }, null)
        assertEquals(0x7F, (wide[13].toInt() and 0xFF) shr 1)
    }

    @Test
    fun filterBandBitsMatchReference() {
        // One-hot masks from hl2setup/hl2.h (FILTER_*).
        assertEquals(0x01, Hl2Protocol.Filter.LPF_160M)
        assertEquals(0x02, Hl2Protocol.Filter.LPF_80M)
        assertEquals(0x04, Hl2Protocol.Filter.LPF_60_40M)
        assertEquals(0x08, Hl2Protocol.Filter.LPF_30_20M)
        assertEquals(0x10, Hl2Protocol.Filter.LPF_17_15M)
        assertEquals(0x20, Hl2Protocol.Filter.LPF_12_10M)
        assertEquals(0x40, Hl2Protocol.Filter.HPF)
        // A selected band lands on C2[7:1] as sent on the wire.
        val f = Hl2Protocol.buildControlFrame(0, 0,
            Hl2Protocol.ControlState().apply { ocOutputs = Hl2Protocol.Filter.LPF_60_40M }, null)
        assertEquals(Hl2Protocol.Filter.LPF_60_40M, (f[13].toInt() and 0xFF) shr 1)
    }

    @Test
    fun controlFrame_classicBoardUsesStepAttenuatorAndCleanC2() {
        // Classic P1 (Hermes/Angelia/Orion): addr 0x0A C4 = bit5 enable +
        // 0..31 dB attenuation; the shared -12..+48 gain scale maps onto it.
        val st = Hl2Protocol.ControlState().apply {
            classicBoard = true; lnaGainDb = 48        // max gain -> 0 dB att
            txDrive = 200; paEnabled = true; vnaMode = true; trDisable = true
        }
        val g = Hl2Protocol.buildControlFrame(0, 10, st, null)
        assertEquals(0x20, g[15].toInt() and 0xFF)
        st.lnaGainDb = -12                              // min gain -> full att
        assertEquals(0x20 or 31, Hl2Protocol.buildControlFrame(0, 10, st, null)[15].toInt() and 0xFF)
        // HL2-only C2 bits (VNA/PA/T-R) never reach a classic board.
        val f = Hl2Protocol.buildControlFrame(0, 8, st, null)
        assertEquals(200, f[524].toInt() and 0xFF)      // drive intact
        assertEquals(0, f[525].toInt() and 0xFF)
    }

    @Test
    fun controlFrame_addr17CarriesTxLatencyAndPttHang() {
        val st = Hl2Protocol.ControlState().apply { pttHang = 7; txLatencyMs = 25 }
        val f = Hl2Protocol.buildControlFrame(0, 0x16, st, null)
        // addr 0x17 is the second sub-frame of bank pair 0x16.
        assertEquals(0x17, (f[523].toInt() and 0xFF) shr 1)
        assertEquals(7, f[526].toInt() and 0x1F)    // C3 = PTT hang
        assertEquals(25, f[527].toInt() and 0x7F)   // C4 = TX latency ms
    }

    @Test
    fun controlFrame_addr3ACarriesResetOnDisconnect() {
        val on = Hl2Protocol.buildControlFrame(0, 0x3A, Hl2Protocol.ControlState(), null)
        assertEquals(0x3A, (on[11].toInt() and 0xFF) shr 1)
        assertEquals(1, on[15].toInt() and 0x01)    // default: reset enabled
        val off = Hl2Protocol.buildControlFrame(
            0, 0x3A, Hl2Protocol.ControlState().apply { resetOnDisconnect = false }, null)
        assertEquals(0, off[15].toInt() and 0x01)
    }

    @Test
    fun rxFrame_decodesSupplyVoltage() {
        // Bank 3 (C0 = 0x18): C3-C4 = AIN6 supply-voltage ADC.
        val buf = ByteArray(Hl2Protocol.FRAME)
        buf[0] = 0xEF.toByte(); buf[1] = 0xFE.toByte(); buf[2] = 0x01; buf[3] = 0x06
        buf[8] = 0x7F; buf[9] = 0x7F; buf[10] = 0x7F
        buf[11] = (3 shl 3).toByte()
        buf[14] = 0x0F; buf[15] = 0xFF.toByte()     // raw 4095 = full scale
        val t = Hl2Protocol.parseRxFrame(buf, 1) { _, _, _ -> }
        assertTrue(t.hasSupplyVolts)
        // Full scale through the (4.7k+820)/820 divider at 3.3 V ≈ 22.21 V.
        assertEquals(22.21, t.supplyVolts, 0.01)

        val none = Hl2Protocol.parseRxFrame(ByteArray(Hl2Protocol.FRAME), 1) { _, _, _ -> }
        assertTrue(!none.hasSupplyVolts)
    }

    @Test
    fun txSamples_streamedWhenTransmitting() {
        val st = Hl2Protocol.ControlState().apply { mox = true }
        val iVal = 12345; val qVal = -9876
        val packed = (iVal shl 16) or (qVal and 0xFFFF)
        val f = Hl2Protocol.buildControlFrame(0, 0, st, { packed })
        // first transmit slot lives at offset 16 (+4 for L/R); the DUC wants
        // the imaginary word first, so the wire order is Q then I, 16-bit BE
        val q = ((f[20].toInt() shl 8) or (f[21].toInt() and 0xFF)).toShort().toInt()
        val i = ((f[22].toInt() shl 8) or (f[23].toInt() and 0xFF)).toShort().toInt()
        assertEquals(iVal, i)
        assertEquals(qVal, q)
    }

    @Test
    fun rxFrame_reportsKeyInputsAndAdcOverflow() {
        // C0 bit0=PTT, bit1=DASH, bit2=DOT (Thetis decode); bank-0 C1 bit0 =
        // ADC clip. These bits ride every frame and feed hardware keying.
        val buf = ByteArray(Hl2Protocol.FRAME)
        buf[0] = 0xEF.toByte(); buf[1] = 0xFE.toByte(); buf[2] = 0x01; buf[3] = 0x06
        for (base in intArrayOf(8, 520)) {
            buf[base] = 0x7F; buf[base + 1] = 0x7F; buf[base + 2] = 0x7F
        }
        buf[11] = ((0 shl 3) or 0x05).toByte()   // bank 0 + PTT + DOT
        buf[12] = 0x01                            // C1 bit0 = overflow
        buf[523] = ((1 shl 3) or 0x02).toByte()  // bank 1 + DASH
        val t = Hl2Protocol.parseRxFrame(buf, 1) { _, _, _ -> }
        assertTrue(t.keyPtt)
        assertTrue(t.keyDot)
        assertTrue(t.keyDash)
        assertTrue(t.adcOverflow)
        assertTrue(t.hasAdcOverflow)

        val quiet = Hl2Protocol.parseRxFrame(ByteArray(Hl2Protocol.FRAME).also {
            it[0] = 0xEF.toByte(); it[1] = 0xFE.toByte(); it[2] = 0x01; it[3] = 0x06
            for (base in intArrayOf(8, 520)) {
                it[base] = 0x7F; it[base + 1] = 0x7F; it[base + 2] = 0x7F
            }
        }, 1) { _, _, _ -> }
        assertTrue(!quiet.keyPtt && !quiet.keyDash && !quiet.keyDot)
        assertTrue(!quiet.adcOverflow)
    }

    @Test
    fun ocWord_switchesWithMoxAtomically() {
        // The TX band word must ride the SAME frame that carries the keying
        // bit — a linear's band relays may never see a word that lags MOX.
        val st = Hl2Protocol.ControlState().apply {
            ocOutputs = 0x04          // 40 m one-hot (RX)
            ocOutputsTx = 0x22        // arbitrary TX decoder word
        }
        val rx = Hl2Protocol.buildControlFrame(0, 0, st, null)
        assertEquals(0x04 shl 1, rx[13].toInt() and 0xFF)
        assertEquals(0, rx[11].toInt() and 1)             // mox clear

        st.mox = true
        val tx = Hl2Protocol.buildControlFrame(1, 0, st, { null })
        assertEquals(0x22 shl 1, tx[13].toInt() and 0xFF) // TX word
        assertEquals(1, tx[11].toInt() and 1)             // same frame keys

        st.ocOutputsTx = -1                                // mirror mode
        val mirror = Hl2Protocol.buildControlFrame(2, 0, st, { null })
        assertEquals(0x04 shl 1, mirror[13].toInt() and 0xFF)
    }

    @Test
    fun roundTripSampleHelpers() {
        assertEquals(0, Hl2Protocol.s24(s24be(0), 0))
        assertEquals(-1, Hl2Protocol.s24(s24be(-1), 0))
        assertEquals(8_388_607, Hl2Protocol.s24(s24be(8_388_607), 0))
        assertEquals(-8_388_608, Hl2Protocol.s24(s24be(-8_388_608), 0))
    }

    private fun be32(b: ByteArray): Long =
        ((b[0].toLong() and 0xFF) shl 24) or ((b[1].toLong() and 0xFF) shl 16) or
                ((b[2].toLong() and 0xFF) shl 8) or (b[3].toLong() and 0xFF)
}
