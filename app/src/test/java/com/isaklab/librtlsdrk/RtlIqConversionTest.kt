package com.isaklab.librtlsdrk

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The RTL receive path converts raw rtl_tcp bytes (unsigned 8-bit I/Q centred at
 * 128) into interleaved floats. Validates that conversion — the RTL counterpart
 * to the HL2 frame-codec tests — so the SdrDevice migration can't silently
 * corrupt RTL samples.
 */
class RtlIqConversionTest {

    @Test fun centreByteIsZero() {
        val iq = RTLTCPClient.u8ToIq(byteArrayOf(128.toByte(), 128.toByte()), 2)
        assertEquals(0f, iq[0], 0f)
        assertEquals(0f, iq[1], 0f)
    }

    @Test fun extremesMapToUnitRange() {
        val iq = RTLTCPClient.u8ToIq(byteArrayOf(255.toByte(), 0.toByte()), 2)
        assertEquals((255 - 128) / 128f, iq[0], 1e-7f)   // +0.9921875
        assertEquals((0 - 128) / 128f, iq[1], 1e-7f)     // -1.0
    }

    @Test fun outputLengthMatchesByteCount() {
        val buf = ByteArray(1024) { (it and 0xFF).toByte() }
        assertEquals(1024, RTLTCPClient.u8ToIq(buf, 1024).size)
    }

    @Test fun reconstructsAKnownTone() {
        // A +Fs/4 complex tone as u8: I = 128+127*cos, Q = 128+127*sin.
        val n = 256
        val buf = ByteArray(n * 2)
        for (k in 0 until n) {
            val ph = 2.0 * Math.PI * (k / 4.0)   // quarter-rate
            buf[2 * k] = (128 + (127 * Math.cos(ph)).toInt()).toByte()
            buf[2 * k + 1] = (128 + (127 * Math.sin(ph)).toInt()).toByte()
        }
        val iq = RTLTCPClient.u8ToIq(buf, n * 2)
        // sample 0 -> cos0=+1, sin0=0 ; sample 1 -> cos(pi/2)=0, sin=+1
        assertEquals(127 / 128f, iq[0], 1e-6f)
        assertEquals(0f, iq[1], 0.01f)
        assertEquals(0f, iq[2], 0.01f)
        assertEquals(127 / 128f, iq[3], 1e-6f)
    }
}
