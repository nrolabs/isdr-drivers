package com.isaklab.libg2sdrk

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Byte-accuracy vs the reference p2app (references/Saturn). */
class G2ProtocolP2Test {

    private fun be32(p: ByteArray, o: Int): Long =
        ((p[o].toLong() and 0xFF) shl 24) or ((p[o + 1].toLong() and 0xFF) shl 16) or
            ((p[o + 2].toLong() and 0xFF) shl 8) or (p[o + 3].toLong() and 0xFF)

    private fun be16(p: ByteArray, o: Int): Int =
        ((p[o].toInt() and 0xFF) shl 8) or (p[o + 1].toInt() and 0xFF)

    @Test
    fun frequenciesAreNcoPhaseWords() {
        // saturnregisters.c: word = f * 2^32 / 122.88 MHz, passed raw to the
        // FPGA NCO. Round-trip error must stay under the NCO resolution.
        for (f in longArrayOf(1_800_000, 7_100_000, 14_074_000, 28_500_000, 50_100_000)) {
            val w = G2Protocol.phaseWord(f)
            val back = G2Protocol.phaseWordToHz(w)
            assertTrue("$f Hz round-trip", abs(back - f) < 0.03)
        }
        val st = G2Protocol.ControlState().apply {
            rxFreqHz = 7_100_000; rx2FreqHz = 14_074_000; txFreqHz = 7_074_000
        }
        val p = G2Protocol.highPriorityPacket(st)
        assertEquals(G2Protocol.phaseWord(7_100_000), be32(p, 9))     // DDC0
        assertEquals(G2Protocol.phaseWord(14_074_000), be32(p, 13))   // DDC1
        assertEquals(G2Protocol.phaseWord(7_074_000), be32(p, 329))   // DUC
        // A raw-Hz packet would tune 7.1 MHz to ~203 kHz — never again:
        assertTrue(be32(p, 9) != 7_100_000L)
    }

    @Test
    fun cwxBitsAndRxAntennaWord() {
        val st = G2Protocol.ControlState().apply {
            cwxEnabled = true; cwxDash = true
            txAntenna = 2; rxAntenna = 3
        }
        val p = G2Protocol.highPriorityPacket(st)
        assertEquals(0x01 or 0x04, p[5].toInt() and 0xFF)             // CWX en+dash
        assertTrue("TX word carries ANT2", be16(p, 1428) and (1 shl 9) != 0)
        // 1432 is the RX ANTENNA word on the new-client path — ANT3 here,
        // and NOT a copy of the TX word.
        assertEquals(1 shl 10, be16(p, 1432))
    }

    @Test
    fun ddcSpecificCarriesDitherRandomAndSyncPair() {
        val st = G2Protocol.ControlState().apply {
            adcDither = 0b01; adcRandom = 0b11
            receiverCount = 2; ddcSync01 = true
        }
        val p = G2Protocol.rxSpecificPacket(st)
        assertEquals(0b01, p[5].toInt())
        assertEquals(0b11, p[6].toInt())
        assertEquals(0b11, p[7].toInt())                              // DDC0+1 enabled
        assertEquals(0b10, p[1363].toInt())                           // sync pair 0+1
    }

    @Test
    fun highPriorityCarriesAllSevenDdcNcos() {
        val st = G2Protocol.ControlState()
        for (n in 0 until G2Protocol.MAX_DDC) st.ddcFreqHz[n] = 7_000_000L + n * 100_000L
        val p = G2Protocol.highPriorityPacket(st)
        for (n in 0 until G2Protocol.MAX_DDC) {
            assertEquals("DDC$n phase word",
                G2Protocol.phaseWord(7_000_000L + n * 100_000L), be32(p, 9 + 4 * n))
        }
    }

    @Test
    fun ddcSpecificFramesByReceiverCountUpToSeven() {
        val st = G2Protocol.ControlState().apply { receiverCount = 7; sampleRate = 96_000 }
        val p = G2Protocol.rxSpecificPacket(st)
        assertEquals(0x7F, p[7].toInt() and 0xFF)                    // 7 DDCs enabled
        assertEquals(0, p[8].toInt())
        for (n in 0 until 7) {
            val b = 17 + 6 * n
            assertEquals("DDC$n input = ADC0", 0, p[b].toInt())
            assertEquals("DDC$n rate", 96, be16(p, b + 1))
            assertEquals("DDC$n size", 24, p[b + 5].toInt())
        }
    }

    @Test
    fun pureSignalRoutesTheFeedbackDdcToTheDucLoopback() {
        val st = G2Protocol.ControlState().apply {
            receiverCount = 2; pureSignal = true; psFeedbackDdc = 1
        }
        val p = G2Protocol.rxSpecificPacket(st)
        assertEquals(G2Protocol.DDC_INPUT_ADC0, p[17].toInt())        // DDC0 stays on the ADC
        assertEquals(G2Protocol.DDC_INPUT_TX_FEEDBACK, p[23].toInt()) // DDC1 = DUC loopback
        st.pureSignal = false; st.psFeedbackDdc = -1
        val off = G2Protocol.rxSpecificPacket(st)
        assertEquals(G2Protocol.DDC_INPUT_ADC0, off[23].toInt())
    }

    @Test
    fun ducSpecificCarriesTheFullHardwareKeyer() {
        val st = G2Protocol.ControlState().apply {
            cwEnabled = true; cwIambic = true; cwSidetone = true; cwBreakin = true
            cwSidetoneVol = 90; cwSidetoneFreq = 650
            cwSpeedWpm = 28; cwWeight = 55; cwHangMs = 300
            cwRfDelayMs = 20; cwRampMs = 5; txAttenDb = 12
        }
        val p = G2Protocol.txSpecificPacket(st)
        assertEquals(0x02 or 0x08 or 0x10 or 0x80, p[5].toInt() and 0xFF)
        assertEquals(90, p[6].toInt() and 0xFF)
        assertEquals(650, be16(p, 7))
        assertEquals(28, p[9].toInt() and 0xFF)
        assertEquals(55, p[10].toInt() and 0xFF)
        assertEquals(300, be16(p, 11))
        assertEquals(20, p[13].toInt() and 0xFF)
        assertEquals(5, p[17].toInt() and 0xFF)
        assertEquals(12, p[58].toInt() and 0xFF)                      // atten in TX
        assertEquals(12, p[59].toInt() and 0xFF)
    }
}
