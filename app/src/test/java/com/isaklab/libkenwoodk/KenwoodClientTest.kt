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

import com.isaklab.libcivk.CivTransport
import com.isaklab.libkenwoodk.KenwoodClient.Link
import com.isaklab.libkenwoodk.KenwoodProtocol as P
import java.util.ArrayDeque
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Client behaviour against a scripted transport: the KNS handshake byte for
 * byte, both login encodings, the keepalive, AI-driven cache updates,
 * bandscope delivery on the spectrum plane, mode translation, and the
 * refusal paths for classic-tier rigs and missing credentials.
 */
class KenwoodClientTest {

    /**
     * Scripted replies keyed by the EXACT command string (terminator
     * included), consumed one per matching write.
     */
    private class FakeRig : CivTransport {
        private val lock = Object()
        private val replies = ArrayList<Pair<String, ArrayDeque<String>>>()
        private val inbox = ArrayDeque<Byte>()
        private val written = ArrayList<String>()

        /** Script [reply] for the next write of exactly [cmd]. */
        fun on(cmd: String, reply: String) {
            synchronized(lock) {
                val entry = replies.firstOrNull { it.first == cmd }
                if (entry != null) entry.second.add(reply)
                else replies.add(Pair(cmd, ArrayDeque(listOf(reply))))
            }
        }

        /** Push unsolicited bytes (AI reports, scope lines) into the stream. */
        fun pushUnsolicited(bytes: ByteArray) {
            synchronized(lock) { bytes.forEach { inbox.add(it) } }
        }

        fun pushUnsolicited(text: String) = pushUnsolicited(text.toByteArray())

        fun written(): List<String> = synchronized(lock) { ArrayList(written) }

        fun writesOf(cmd: String): Int = synchronized(lock) { written.count { it == cmd } }

        override fun writeAll(bytes: ByteArray) {
            val cmd = String(bytes, Charsets.US_ASCII)
            synchronized(lock) {
                written.add(cmd)
                val entry = replies.firstOrNull { it.first == cmd }
                if (entry != null && entry.second.isNotEmpty()) {
                    entry.second.removeFirst().toByteArray().forEach { inbox.add(it) }
                }
            }
        }

        override fun readSome(buf: ByteArray): Int {
            var n = 0
            synchronized(lock) {
                while (n < buf.size && inbox.isNotEmpty()) {
                    buf[n++] = inbox.removeFirst()
                }
            }
            if (n == 0) {
                // Behave like the transport timeout so the reader breathes.
                Thread.sleep(2)
            }
            return n
        }
    }

    private class Captured {
        /** (spectrum bins, IQ sample count) per delivery. */
        val spectra = ArrayList<Pair<FloatArray, Int>>()
        val status = ArrayList<Pair<Boolean, String>>()
    }

    private fun makeClient(
        rig: FakeRig,
        link: Link,
        credentials: Pair<String, String>?,
    ): Pair<KenwoodClient, Captured> {
        val cap = Captured()
        val client = KenwoodClient(
            rig, link, credentials,
            { spectrum, iq -> synchronized(cap) { cap.spectra.add(Pair(spectrum.copyOf(), iq.size)) } },
            { up, msg -> synchronized(cap) { cap.status.add(Pair(up, msg)) } },
        )
        return Pair(client, cap)
    }

    /**
     * Script a TS-890S LAN session: handshake, identification, initial state
     * and the bandscope placement polls (centre mode, 100 kHz span at
     * 14.1 MHz, EXPAND off).
     */
    private fun scriptLanTs890(h: FakeRig) {
        h.on("##CN;", "##CN1;")
        h.on("##ID00705kenwoodadmin;", "##ID1;")
        h.on("ID;", "ID024;")
        h.on("FA;", "FA00014100000;")
        h.on("OM0;", "OM02;")
        h.on("BS3;", "BS30;")
        h.on("BS4;", "BS44;")
        h.on("BSM0;", "BSM00700000007300000;")
        h.on("BSO;", "BSO0;")
    }

    /** Serial TS-890S session: no handshake, same identification and polls. */
    private fun scriptSerialTs890(h: FakeRig) {
        h.on("ID;", "ID024;")
        h.on("FA;", "FA00014100000;")
        h.on("OM0;", "OM02;")
        h.on("BS3;", "BS30;")
        h.on("BS4;", "BS44;")
        h.on("BSM0;", "BSM00700000007300000;")
        h.on("BSO;", "BSO0;")
    }

    private fun waitUntil(cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 2_000
        while (!cond() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
    }

    private fun connect(c: KenwoodClient): Boolean = runBlocking { c.connect() }

    // ---- connect flows -------------------------------------------------------

    @Test
    fun `lan handshake sequence byte exact`() {
        val h = FakeRig()
        scriptLanTs890(h)
        val (c, cap) = makeClient(h, Link.LAN, Pair("kenwood", "admin"))
        assertTrue(connect(c))

        var written = h.written()
        // The proven bring-up order: session, login, identify, AI, then state.
        assertEquals("##CN;", written[0])
        assertEquals("##ID00705kenwoodadmin;", written[1])
        assertEquals("ID;", written[2])
        // The AI2 and DD01 sends are fire-and-forget; wait for the reader to
        // flush them before inspecting the write log.
        waitUntil { h.writesOf("DD01;") == 1 }
        written = h.written()
        assertTrue(written.contains("AI2;"))
        assertTrue(written.contains("DD01;"))
        for (poll in listOf("FA;", "OM0;", "BS3;", "BS4;", "BSM0;", "BSO;")) {
            assertTrue("missing $poll", written.contains(poll))
        }
        // AI precedes the scope stream enable.
        assertTrue(written.indexOf("AI2;") < written.indexOf("DD01;"))

        assertEquals(14_100_000L, c.frequencyHz())
        assertEquals(P.MODE_USB, c.mode())
        assertEquals("TS-890S", c.modelName())
        assertTrue(c.scopeCapable())
        // Centre mode, 100 kHz (BS4 code 4) around the tuned frequency.
        assertEquals(Pair(14_050_000L, 14_150_000L), c.scopeEdges())
        assertEquals(100_000, c.sampleRateHz())
        assertEquals(listOf(Pair(true, "TS-890S")), synchronized(cap) { ArrayList(cap.status) })
        c.disconnect()
    }

    @Test
    fun `ts990 login shape is tried after a rejection`() {
        val h = FakeRig()
        // The TS-990S rejects the TS-890S-shaped login and accepts its own.
        h.on("##CN;", "##CN1;")
        h.on("##ID00705kenwoodadmin;", "##ID0;")
        h.on("##ID75kenwoodadmin;", "##ID1;")
        h.on("ID;", "ID023;")
        h.on("FA;", "FA00007074000;")
        h.on("OM0;", "OM01;")
        h.on("BS3;", "BS30;")
        h.on("BS4;", "BS42;")
        h.on("BSM0;", "BSM00700000007300000;")
        h.on("BSO;", "BSO0;")
        val (c, _) = makeClient(h, Link.LAN, Pair("kenwood", "admin"))
        assertTrue(connect(c))
        assertEquals("TS-990S", c.modelName())
        assertEquals(1, h.writesOf("##ID00705kenwoodadmin;"))
        assertEquals(1, h.writesOf("##ID75kenwoodadmin;"))
        c.disconnect()
    }

    @Test
    fun `missing credentials refuse with guidance`() {
        val h = FakeRig()
        h.on("##CN;", "##CN1;")
        val (c, cap) = makeClient(h, Link.LAN, null)
        assertFalse(connect(c))
        val status = synchronized(cap) { ArrayList(cap.status) }
        assertEquals(1, status.size)
        assertFalse(status[0].first)
        assertTrue(
            "the status must say how to pass credentials: ${status[0].second}",
            status[0].second.contains("user:password@host"),
        )
        // No login was ever attempted.
        assertTrue(h.written().none { it.startsWith("##ID") })
    }

    @Test
    fun `busy kns and silent ports refuse`() {
        val h = FakeRig()
        h.on("##CN;", "##CN0;")
        val (c, cap) = makeClient(h, Link.LAN, Pair("u", "p"))
        assertFalse(connect(c))
        assertFalse(synchronized(cap) { cap.status[0].first })

        val h2 = FakeRig()
        val (c2, cap2) = makeClient(h2, Link.SERIAL, null)
        assertFalse(connect(c2))
        assertFalse(synchronized(cap2) { cap2.status[0].first })
    }

    @Test
    fun `classic tier rig is refused by name`() {
        val h = FakeRig()
        h.on("ID;", "ID019;")
        val (c, cap) = makeClient(h, Link.SERIAL, null)
        assertFalse(connect(c))
        val status = synchronized(cap) { ArrayList(cap.status) }
        assertFalse(status[0].first)
        assertTrue(status[0].second, status[0].second.contains("TS-2000"))
        assertTrue(status[0].second, status[0].second.contains("not implemented"))
        // No scope or AI traffic went to a rig that speaks another dialect.
        assertTrue(h.written().none { it.startsWith("DD0") })
    }

    @Test
    fun `serial connect uses the unlinked scope mode`() {
        val h = FakeRig()
        scriptSerialTs890(h)
        val (c, _) = makeClient(h, Link.SERIAL, null)
        assertTrue(connect(c))
        waitUntil { h.writesOf("DD05;") == 1 }
        assertEquals(1, h.writesOf("DD05;"))
        assertEquals(0, h.writesOf("##CN;"))
        c.disconnect()
    }

    // ---- keepalive -----------------------------------------------------------

    @Test
    fun `lan keepalive sends ps during quiet`() {
        val h = FakeRig()
        scriptLanTs890(h)
        val (c, _) = makeClient(h, Link.LAN, Pair("kenwood", "admin"))
        c.setKeepaliveIntervalMs(50)
        assertTrue(connect(c))
        Thread.sleep(400)
        assertTrue(
            "expected keepalives during quiet, saw ${h.writesOf("PS;")}",
            h.writesOf("PS;") >= 2,
        )
        c.disconnect()
    }

    @Test
    fun `serial link sends no keepalive`() {
        val h = FakeRig()
        scriptSerialTs890(h)
        val (c, _) = makeClient(h, Link.SERIAL, null)
        c.setKeepaliveIntervalMs(50)
        assertTrue(connect(c))
        Thread.sleep(300)
        assertEquals(0, h.writesOf("PS;"))
        c.disconnect()
    }

    // ---- AI-driven cache updates ---------------------------------------------

    @Test
    fun `ai reports update frequency mode and keying`() {
        val h = FakeRig()
        scriptLanTs890(h)
        val (c, _) = makeClient(h, Link.LAN, Pair("kenwood", "admin"))
        assertTrue(connect(c))

        h.pushUnsolicited("FA00003573000;OM03;TX0;##TI1;")
        waitUntil { c.frequencyHz() == 3_573_000L }
        assertEquals(3_573_000L, c.frequencyHz())
        assertEquals(P.MODE_CW, c.mode())
        assertTrue(c.isTransmitting())
        assertTrue(c.txAuthorized())

        h.pushUnsolicited("RX;")
        waitUntil { !c.isTransmitting() }
        assertFalse(c.isTransmitting())
        c.disconnect()
    }

    @Test
    fun `rig side tunes fire the frequency callback and commanded ones do not`() {
        val h = FakeRig()
        scriptLanTs890(h)
        val (c, _) = makeClient(h, Link.LAN, Pair("kenwood", "admin"))
        val seen = ArrayList<Long>()
        c.onFrequencyChanged = { hz -> synchronized(seen) { seen.add(hz) } }
        assertTrue(connect(c))

        // A tune this client commanded confirms via read-back, no callback.
        h.on("FA;", "FA00007074000;")
        c.setFrequency(7_074_000)
        assertEquals(7_074_000L, c.frequencyHz())
        Thread.sleep(50)
        assertTrue(synchronized(seen) { seen.isEmpty() })
        assertEquals(1, h.writesOf("FA00007074000;"))

        // The operator turns the dial: the AI report fires the callback.
        h.pushUnsolicited("FA00003573000;")
        waitUntil { synchronized(seen) { seen.isNotEmpty() } }
        assertEquals(listOf(3_573_000L), synchronized(seen) { ArrayList(seen) })
        c.disconnect()
    }

    // ---- scope delivery ------------------------------------------------------

    private fun lanDd2Frame(f: (Int) -> Int): ByteArray {
        val sb = StringBuilder("##DD2")
        for (i in 0 until P.SCOPE_BINS) sb.append("%02X".format(f(i)))
        sb.append(';')
        return sb.toString().toByteArray()
    }

    @Test
    fun `scope line is delivered as spectrum with empty iq`() {
        val h = FakeRig()
        scriptLanTs890(h)
        val (c, cap) = makeClient(h, Link.LAN, Pair("kenwood", "admin"))
        val spans = ArrayList<Pair<Long, Long>>()
        c.onSpanDelivered = { lo, hi -> synchronized(spans) { spans.add(Pair(lo, hi)) } }
        assertTrue(connect(c))

        h.pushUnsolicited(
            lanDd2Frame {
                when (it) {
                    0 -> 0x00
                    1 -> 0x46
                    2 -> 0x8C
                    else -> 0x8C
                }
            },
        )
        waitUntil { synchronized(cap) { cap.spectra.isNotEmpty() } }
        val spectra = synchronized(cap) { ArrayList(cap.spectra) }
        assertEquals(1, spectra.size)
        val (spectrum, iqLen) = spectra[0]
        assertEquals(0, iqLen)
        assertEquals(P.SCOPE_BINS, spectrum.size)
        assertEquals(0.0f, spectrum[0], 0f)
        assertTrue(kotlin.math.abs(spectrum[1] + 50.0f) < 0.5f)
        assertEquals(-100.0f, spectrum[2], 0f)
        assertEquals(
            listOf(Pair(14_050_000L, 14_150_000L)),
            synchronized(spans) { ArrayList(spans) },
        )
        c.disconnect()
    }

    @Test
    fun `spectrum disable suppresses delivery and stops the stream`() {
        val h = FakeRig()
        scriptLanTs890(h)
        val (c, cap) = makeClient(h, Link.LAN, Pair("kenwood", "admin"))
        assertTrue(connect(c))
        c.spectrumEnabled = false
        waitUntil { h.writesOf("DD00;") == 1 }
        assertEquals(1, h.writesOf("DD00;"))

        h.pushUnsolicited(lanDd2Frame { 0x40 })
        Thread.sleep(100)
        assertTrue(synchronized(cap) { cap.spectra.isEmpty() })
        c.disconnect()
    }

    @Test
    fun `serial dd2 splits reassemble into one delivery`() {
        val h = FakeRig()
        scriptSerialTs890(h)
        val (c, cap) = makeClient(h, Link.SERIAL, null)
        assertTrue(connect(c))

        val stream = StringBuilder()
        for (split in 0 until 32) {
            stream.append("DD2%02d%s;".format(split, "10".repeat(20)))
        }
        h.pushUnsolicited(stream.toString())
        waitUntil { synchronized(cap) { cap.spectra.isNotEmpty() } }
        val spectra = synchronized(cap) { ArrayList(cap.spectra) }
        assertEquals(1, spectra.size)
        assertEquals(P.SCOPE_BINS, spectra[0].first.size)
        c.disconnect()
    }

    // ---- control -------------------------------------------------------------

    @Test
    fun `set mode translates the cat code space to om`() {
        val h = FakeRig()
        scriptLanTs890(h)
        val (c, _) = makeClient(h, Link.LAN, Pair("kenwood", "admin"))
        assertTrue(connect(c))

        // CAT code 3 = CW, OM digit 3.
        h.on("OM0;", "OM03;")
        assertTrue(c.setMode(3))
        assertEquals(1, h.writesOf("OM03;"))
        assertEquals(P.MODE_CW, c.mode())

        // CAT code 5 = FM, OM digit 4 (the tables differ here).
        h.on("OM0;", "OM04;")
        assertTrue(c.setMode(5))
        assertEquals(1, h.writesOf("OM04;"))
        assertEquals(P.MODE_FM, c.mode())

        // A CAT code with no OM equivalent is refused without traffic.
        assertFalse(c.setMode(6))
        assertEquals(0, h.writesOf("OM06;"))
        c.disconnect()
    }

    @Test
    fun `ptt sends tx rx and ai corrects the cache`() {
        val h = FakeRig()
        scriptLanTs890(h)
        val (c, _) = makeClient(h, Link.LAN, Pair("kenwood", "admin"))
        assertTrue(connect(c))

        c.setPtt(true)
        waitUntil { h.writesOf("TX0;") == 1 }
        assertTrue(c.isTransmitting())
        c.setPtt(false)
        waitUntil { h.writesOf("RX;") == 1 }
        assertFalse(c.isTransmitting())

        // The rig refuses to key (e.g. no ##TI grant): its RX report corrects
        // the optimistic cache.
        c.setPtt(true)
        h.pushUnsolicited("RX;")
        waitUntil { !c.isTransmitting() }
        assertFalse(c.isTransmitting())
        c.disconnect()
    }

    @Test
    fun `set sample rate snaps to the bs4 ladder`() {
        val h = FakeRig()
        scriptLanTs890(h)
        val (c, _) = makeClient(h, Link.LAN, Pair("kenwood", "admin"))
        assertTrue(connect(c))

        // 30 kHz asks for the nearest rung, 25 kHz (code 2).
        h.on("BS4;", "BS42;")
        c.setSampleRate(30_000)
        assertEquals(25_000, c.sampleRateHz())
        assertEquals(1, h.writesOf("BS42;"))
        // The edges follow the new span around the tuned frequency.
        assertEquals(Pair(14_087_500L, 14_112_500L), c.scopeEdges())

        // 1 MHz clamps onto the top rung, 500 kHz.
        h.on("BS4;", "BS46;")
        c.setSampleRate(1_000_000)
        assertEquals(500_000, c.sampleRateHz())
        assertEquals(1, h.writesOf("BS46;"))
        c.disconnect()
    }

    @Test
    fun `rejected command surfaces and does not change state`() {
        val h = FakeRig()
        scriptLanTs890(h)
        val (c, _) = makeClient(h, Link.LAN, Pair("kenwood", "admin"))
        assertTrue(connect(c))

        // The rig answers the read-back with the error token: the cache
        // keeps the last confirmed value.
        h.on("FA;", "?;")
        c.setFrequency(7_074_000)
        assertEquals(14_100_000L, c.frequencyHz())
        c.disconnect()
    }

    @Test
    fun `disconnect is idempotent and stops the reader`() {
        val h = FakeRig()
        scriptLanTs890(h)
        val (c, _) = makeClient(h, Link.LAN, Pair("kenwood", "admin"))
        assertTrue(connect(c))
        c.disconnect()
        c.disconnect()
        c.setFrequency(7_000_000) // must not hang or panic after teardown
        assertEquals(14_100_000L, c.frequencyHz())
    }
}
