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
import com.isaklab.isdrproto.CatRepeater
import com.isaklab.isdrproto.CatRepeaterConfig
import com.isaklab.isdrproto.DriverProto
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

    private fun scriptLanTs990(h: FakeRig) {
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

    private fun repeaterConfig() = CatRepeaterConfig(
        duplex = CatRepeater.DUPLEX_PLUS,
        offsetHz = 600_000,
        txKind = CatRepeater.TONE_CTCSS,
        txValue = 885,
        txPolarity = CatRepeater.DCS_NORMAL,
        rxKind = CatRepeater.TONE_CTCSS,
        rxValue = 915,
        rxPolarity = CatRepeater.DCS_NORMAL,
    )

    /** Complete TS-890 transaction readbacks, including final physical proof. */
    private fun scriptTs890Repeater(h: FakeRig) {
        repeat(2) { h.on("OM0;", "OM04;") }
        h.on("FA;", "FA00014100000;")
        h.on("FB;", "FB00014200000;")
        h.on("FR;", "FR0;")
        h.on("FT;", "FT0;")
        h.on("TO;", "TO0;")
        h.on("TN;", "TN00;")
        h.on("CN;", "CN00;")
        h.on("TN;", "TN08;")
        h.on("CN;", "CN09;")
        h.on("TO;", "TO3;")
        h.on("FB;", "FB00014700000;")
        h.on("FR;", "FR0;")
        h.on("FT;", "FT1;")
        h.on("FA;", "FA00014100000;")
        h.on("FA;", "FA00014100000;")
        h.on("FB;", "FB00014700000;")
        h.on("FR;", "FR0;")
        h.on("FT;", "FT1;")
        h.on("TO;", "TO3;")
        h.on("TN;", "TN08;")
        h.on("CN;", "CN09;")
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
        assertEquals(20_000, c.sampleRateHz())
        assertEquals(Pair(7_064_000L, 7_084_000L), c.scopeEdges())

        h.on("BS4;", "BS42;")
        c.setSampleRate(21_000)
        assertEquals(20_000, c.sampleRateHz())
        assertEquals(1, h.writesOf("BS42;"))

        h.on("FA;", "FA00007074000;")
        h.on("FA;", "FA00007074000;")
        h.on("FB;", "FB00007100000;")
        h.on("TB;", "TB1;")
        assertTrue(c.setTxFrequency(7_100_000))
        assertEquals(7_074_000L, c.frequencyHz())
        assertEquals(0, h.writesOf("FA00007100000;"))
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
    fun `data modes map both ways and normal mode clears data`() {
        val h = FakeRig()
        scriptLanTs890(h)
        val (c, _) = makeClient(h, Link.LAN, Pair("kenwood", "admin"))
        assertTrue(connect(c))

        val data = DriverProto.CAT_MODE_DATA_FLAG
        for ((requested, om) in listOf(
            Pair(data or 0, P.MODE_LSB_D),
            Pair(data or 1, P.MODE_USB_D),
            Pair(data or 2, P.MODE_AM_D),
            Pair(data or 5, P.MODE_FM_D),
        )) {
            val digit = om.toString(16).uppercase()
            h.on("OM0;", "OM0$digit;")
            assertTrue(c.setCatMode(requested))
            assertEquals(1, h.writesOf("OM0$digit;"))
            assertEquals(om, c.mode())
            assertEquals(requested, c.currentCatMode())
        }

        h.on("OM0;", "OM02;")
        assertTrue(c.setCatMode(1))
        assertEquals(P.MODE_USB, c.mode())
        assertEquals(1, c.currentCatMode())
        c.disconnect()
    }

    @Test
    fun `mode requires physical match and rejects unknown data combinations`() {
        val h = FakeRig()
        scriptLanTs890(h)
        val (c, _) = makeClient(h, Link.LAN, Pair("kenwood", "admin"))
        assertTrue(connect(c))

        val data = DriverProto.CAT_MODE_DATA_FLAG
        h.on("OM0;", "OM02;")
        assertFalse(c.setCatMode(data or 1))
        assertEquals(1, h.writesOf("OM0D;"))
        assertEquals(P.MODE_USB, c.mode())
        assertEquals(1, c.currentCatMode())

        waitUntil { h.writesOf("DD01;") == 1 }
        val before = h.written().size
        for (invalid in listOf(6, data or 3, data or 4, data or 7, 0x200 or 1)) {
            assertFalse(c.setCatMode(invalid))
        }
        assertEquals(before, h.written().size)
        c.disconnect()
    }

    @Test
    fun `ts890 repeater is atomic and confirmed from physical readback`() {
        val h = FakeRig()
        scriptSerialTs890(h)
        val (c, _) = makeClient(h, Link.SERIAL, null)
        assertTrue(connect(c))
        scriptTs890Repeater(h)

        assertEquals(null, c.setCatRepeater(repeaterConfig()))
        assertEquals(
            CatRepeater.CAP_DUPLEX or CatRepeater.CAP_OFFSET or
                CatRepeater.CAP_CTCSS_TX or CatRepeater.CAP_CTCSS_RX,
            c.catRepeaterCapabilities(),
        )
        val written = h.written()
        for (command in listOf("TN08;", "CN09;", "TO3;", "FB00014700000;", "FR0;", "FT1;")) {
            assertTrue("missing $command", written.contains(command))
        }
        assertTrue(written.indexOf("FB00014700000;") < written.lastIndexOf("FT1;"))
        assertEquals(0, h.writesOf("FA00014700000;"))
        c.disconnect()
    }

    @Test
    fun `unknown tone and dcs are refused without touching the rig`() {
        val h = FakeRig()
        scriptSerialTs890(h)
        val (c, _) = makeClient(h, Link.SERIAL, null)
        assertTrue(connect(c))
        val before = h.written().size
        val unsupported = repeaterConfig().copy(
            txKind = CatRepeater.TONE_DCS,
            txValue = 23,
        )
        assertTrue(c.setCatRepeater(unsupported)!!.contains("DCS"))
        assertEquals(before, h.written().size)
        c.disconnect()
    }

    @Test
    fun `priority unkey cancels after current frame and runs next on wire`() {
        val h = FakeRig()
        scriptSerialTs890(h)
        val (c, _) = makeClient(h, Link.SERIAL, null)
        assertTrue(connect(c))

        h.on("OM0;", "OM04;")
        h.on("FA;", "FA00014100000;")
        h.on("FB;", "FB00014200000;")
        h.on("FR;", "FR0;")
        h.on("FT;", "FT0;")
        h.on("TO;", "TO0;")
        h.on("TN;", "TN00;")
        h.on("CN;", "CN00;")
        h.on("TN;", "TN08;")
        h.on("CN;", "CN09;")
        h.on("TO;", "TO3;")
        // No reply to the FB read after this mutating frame: the transaction
        // is deliberately stopped in the middle of its physical sequence.
        val result = arrayOfNulls<String>(1)
        val worker = Thread { result[0] = c.setCatRepeater(repeaterConfig()) }
        worker.start()
        waitUntil { h.writesOf("FB00014700000;") == 1 && h.writesOf("FB;") >= 2 }

        h.on("RX;", "RX;")
        val started = System.nanoTime()
        c.requestCatRepeaterCancelForUnkey()
        val unkey = Thread { c.setPtt(false) }
        unkey.start()
        worker.join(700)
        unkey.join(700)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertFalse("repeater worker did not stop", worker.isAlive)
        assertFalse("unkey did not complete", unkey.isAlive)
        assertTrue(result[0]!!, result[0]!!.contains("uncertain"))
        assertTrue("priority unkey took ${elapsedMs}ms", elapsedMs < 500)
        val written = h.written()
        assertTrue(written.indexOf("FB00014700000;") < written.indexOf("RX;"))
        assertEquals(0, h.writesOf("FB00014200000;")) // no slow rollback before unkey
        c.disconnect()
    }

    @Test
    fun `ptt sends tx rx and ai corrects the cache`() {
        val h = FakeRig()
        scriptLanTs890(h)
        val (c, _) = makeClient(h, Link.LAN, Pair("kenwood", "admin"))
        assertTrue(connect(c))

        h.on("TX0;", "TX0;")
        c.setPtt(true)
        waitUntil { h.writesOf("TX0;") == 1 }
        assertTrue(c.isTransmitting())
        h.on("RX;", "RX;")
        c.setPtt(false)
        waitUntil { h.writesOf("RX;") == 1 }
        assertFalse(c.isTransmitting())

        // The rig refuses to key (e.g. no ##TI grant): its RX report corrects
        // the optimistic cache.
        h.on("TX0;", "RX;")
        try {
            c.setPtt(true)
        } catch (_: IllegalStateException) {
        }
        assertFalse(c.isTransmitting())
        c.disconnect()
    }

    @Test
    fun `tx frequency uses vfo b split and leaves receive vfo a invariant`() {
        val h = FakeRig()
        scriptLanTs890(h)
        val (c, _) = makeClient(h, Link.LAN, Pair("kenwood", "admin"))
        assertTrue(connect(c))

        h.on("FA;", "FA00014100000;")
        h.on("FA;", "FA00014100000;")
        h.on("FB;", "FB00014250000;")
        h.on("FR;", "FR0;")
        h.on("FT;", "FT1;")

        assertTrue(c.setTxFrequency(14_250_000))
        assertEquals(14_100_000L, c.frequencyHz())
        val written = h.written()
        assertTrue(written.contains("FB00014250000;"))
        assertTrue(written.contains("FR0;"))
        assertTrue(written.contains("FT1;"))
        assertEquals(0, h.writesOf("FA00014250000;"))

        val order = listOf("FB00014250000;", "FB;", "FR0;", "FR;", "FT1;", "FT;")
            .map { written.indexOf(it) }
        assertTrue(order.all { it >= 0 })
        assertTrue(order.zipWithNext().all { (a, b) -> a < b })
        c.disconnect()
    }

    @Test
    fun `tx split rejects mismatched readback and never writes receive vfo`() {
        val h = FakeRig()
        scriptSerialTs890(h)
        val (c, _) = makeClient(h, Link.SERIAL, null)
        assertTrue(connect(c))

        h.on("FA;", "FA00014100000;")
        h.on("FB;", "FB00014249000;")
        assertFalse(c.setTxFrequency(14_250_000))
        assertEquals(14_100_000L, c.frequencyHz())
        assertEquals(0, h.writesOf("FR0;"))
        assertEquals(0, h.writesOf("FT1;"))
        assertEquals(0, h.writesOf("FA00014250000;"))
        c.disconnect()
    }

    @Test
    fun `tx split reports receive vfo race and reconciles final value`() {
        val h = FakeRig()
        scriptSerialTs890(h)
        val (c, _) = makeClient(h, Link.SERIAL, null)
        assertTrue(connect(c))

        h.on("FA;", "FA00014100000;")
        h.on("FA;", "FA00014101000;")
        h.on("FB;", "FB00014250000;")
        h.on("FR;", "FR0;")
        h.on("FT;", "FT1;")
        assertFalse(c.setTxFrequency(14_250_000))
        assertEquals(14_101_000L, c.frequencyHz())
        assertEquals(0, h.writesOf("FA00014250000;"))
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
        var refused = false
        try {
            c.setFrequency(7_074_000)
        } catch (_: IllegalStateException) {
            refused = true
        }
        assertTrue(refused)
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
        var refused = false
        try {
            c.setFrequency(7_000_000)
        } catch (_: IllegalStateException) {
            refused = true
        }
        assertTrue(refused)
        assertEquals(14_100_000L, c.frequencyHz())
    }

    // ---- receive controls ----------------------------------------------------

    @Test
    fun `set control levels confirm by read back`() {
        val h = FakeRig()
        scriptLanTs890(h)
        val (c, _) = makeClient(h, Link.LAN, Pair("kenwood", "admin"))
        assertTrue(connect(c))

        // CATCTL_RF_GAIN drives RG, CATCTL_SQUELCH SQ, CATCTL_AF_GAIN AG.
        h.on("RG;", "RG200;")
        assertTrue(c.setControl(2, 200))
        assertEquals(1, h.writesOf("RG200;"))
        h.on("SQ;", "SQ000;")
        assertTrue(c.setControl(3, 0))
        assertEquals(1, h.writesOf("SQ000;"))
        h.on("AG;", "AG128;")
        assertTrue(c.setControl(13, 128))
        assertEquals(1, h.writesOf("AG128;"))

        // A read-back that disagrees is a failed set.
        h.on("RG;", "RG180;")
        assertFalse(c.setControl(2, 190))
        // The rig's `?;` rejection is a failed set too.
        h.on("SQ;", "?;")
        assertFalse(c.setControl(3, 50))

        // Out-of-range values never touch the wire.
        assertFalse(c.setControl(2, 256))
        assertFalse(c.setControl(13, -1))
        assertEquals(0, h.writesOf("RG256;"))
        c.disconnect()
    }

    @Test
    fun `ts890 rf power maps drive to watts and requires read back`() {
        val h = FakeRig()
        scriptLanTs890(h)
        val (c, _) = makeClient(h, Link.LAN, Pair("kenwood", "admin"))
        assertTrue(connect(c))

        h.on("PC;", "PC050;")
        assertTrue(c.setControl(14, 128))
        assertEquals(1, h.writesOf("PC050;"))
        assertEquals(1, h.writesOf("PC;"))

        h.on("PC;", "PC049;")
        assertFalse(c.setControl(14, 128))
        h.on("PC;", "?;")
        assertFalse(c.setControl(14, 64))

        h.on("PC;", "PC005;")
        assertTrue(c.setControl(14, 0))
        val before = h.written().size
        assertFalse(c.setControl(14, -1))
        assertFalse(c.setControl(14, 256))
        assertEquals(before, h.written().size)
        c.disconnect()
    }

    @Test
    fun `ts990 rf power uses the two hundred watt model ceiling`() {
        val h = FakeRig()
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

        h.on("PC;", "PC200;")
        assertTrue(c.setControl(14, 255))
        assertEquals(1, h.writesOf("PC200;"))
        assertEquals(1, h.writesOf("PC;"))
        c.disconnect()
    }

    @Test
    fun `ts990 receive controls select and confirm main band`() {
        val h = FakeRig()
        scriptLanTs990(h)
        val (c, _) = makeClient(h, Link.LAN, Pair("kenwood", "admin"))
        assertTrue(connect(c))

        h.on("FL00;", "FL001;")
        assertTrue(c.setControl(1, 2))
        assertEquals(1, h.writesOf("FL001;"))

        h.on("RG0;", "RG0042;")
        assertTrue(c.setControl(2, 42))
        assertEquals(1, h.writesOf("RG0042;"))
        h.on("SQ0;", "SQ0043;")
        assertTrue(c.setControl(3, 43))
        assertEquals(1, h.writesOf("SQ0043;"))
        h.on("AG0;", "AG0044;")
        assertTrue(c.setControl(13, 44))
        assertEquals(1, h.writesOf("AG0044;"))

        h.on("NR0;", "NR01;")
        h.on("RL10;", "RL1001;")
        assertTrue(c.setControl(4, 1))
        assertEquals(1, h.writesOf("NR01;"))
        assertEquals(1, h.writesOf("RL1001;"))
        h.on("NB10;", "NB101;")
        assertTrue(c.setControl(5, 1))
        assertEquals(1, h.writesOf("NB101;"))
        h.on("BC0;", "BC01;")
        assertTrue(c.setControl(6, 1))
        assertEquals(1, h.writesOf("BC01;"))
        h.on("GC0;", "GC03;")
        assertTrue(c.setControl(7, 1))
        assertEquals(1, h.writesOf("GC03;"))

        // TS-990S PA query is bare; the set and answer still carry Main P1=0.
        h.on("PA;", "PA01;")
        assertTrue(c.setControl(8, 1))
        assertEquals(1, h.writesOf("PA01;"))
        h.on("RA0;", "RA02;")
        assertTrue(c.setControl(9, 10))
        assertEquals(1, h.writesOf("RA02;"))

        h.on("OM0;", "OM03;")
        assertTrue(c.setMode(3))
        h.on("SL0;", "SL000;")
        assertTrue(c.setControl(12, 50))
        assertEquals(1, h.writesOf("SL000;"))
        c.disconnect()
    }

    @Test
    fun `set control agc maps the reversed digit scale`() {
        val h = FakeRig()
        scriptLanTs890(h)
        val (c, _) = makeClient(h, Link.LAN, Pair("kenwood", "admin"))
        assertTrue(connect(c))

        // App 1 fast = GC3, 2 mid = GC2, 3 slow = GC1, 0 off = GC0.
        h.on("GC;", "GC3;")
        assertTrue(c.setControl(7, 1))
        assertEquals(1, h.writesOf("GC3;"))
        h.on("GC;", "GC2;")
        assertTrue(c.setControl(7, 2))
        assertEquals(1, h.writesOf("GC2;"))
        h.on("GC;", "GC1;")
        assertTrue(c.setControl(7, 3))
        assertEquals(1, h.writesOf("GC1;"))
        h.on("GC;", "GC0;")
        assertTrue(c.setControl(7, 0))
        assertEquals(1, h.writesOf("GC0;"))

        assertFalse(c.setControl(7, 4))
        assertEquals(0, h.writesOf("GC4;"))
        c.disconnect()
    }

    @Test
    fun `set control nr drives nr1 and its rl1 level`() {
        val h = FakeRig()
        scriptLanTs890(h)
        val (c, _) = makeClient(h, Link.LAN, Pair("kenwood", "admin"))
        assertTrue(connect(c))

        // 0 turns NR off; no RL1 traffic.
        h.on("NR;", "NR0;")
        assertTrue(c.setControl(4, 0))
        assertEquals(1, h.writesOf("NR0;"))
        assertTrue(h.written().all { !it.startsWith("RL1") || it == "RL1;" })

        // 15 = maximum: NR1 on, RL1 level 10.
        h.on("NR;", "NR1;")
        h.on("RL1;", "RL110;")
        assertTrue(c.setControl(4, 15))
        assertEquals(1, h.writesOf("NR1;"))
        assertEquals(1, h.writesOf("RL110;"))

        // 1 = minimum audible: RL1 level 01.
        h.on("NR;", "NR1;")
        h.on("RL1;", "RL101;")
        assertTrue(c.setControl(4, 1))
        assertEquals(1, h.writesOf("RL101;"))

        // The rig staying off on read-back fails the set before any RL1 write.
        h.on("NR;", "NR0;")
        assertFalse(c.setControl(4, 8))
        assertEquals(0, h.writesOf("RL106;"))

        assertFalse(c.setControl(4, 16))
        c.disconnect()
    }

    @Test
    fun `set control nb notch preamp att and filter`() {
        val h = FakeRig()
        scriptLanTs890(h)
        val (c, _) = makeClient(h, Link.LAN, Pair("kenwood", "admin"))
        assertTrue(connect(c))

        // CATCTL_NB drives NB1 on/off.
        h.on("NB1;", "NB11;")
        assertTrue(c.setControl(5, 1))
        assertEquals(1, h.writesOf("NB11;"))
        h.on("NB1;", "NB10;")
        assertTrue(c.setControl(5, 0))
        assertEquals(1, h.writesOf("NB10;"))

        // CATCTL_NOTCH_AUTO drives the beat canceller BC0/BC1.
        h.on("BC;", "BC1;")
        assertTrue(c.setControl(6, 1))
        assertEquals(1, h.writesOf("BC1;"))
        h.on("BC;", "BC0;")
        assertTrue(c.setControl(6, 0))
        assertEquals(1, h.writesOf("BC0;"))

        // CATCTL_PREAMP: digit-identical PA codes.
        h.on("PA;", "PA2;")
        assertTrue(c.setControl(8, 2))
        assertEquals(1, h.writesOf("PA2;"))

        // CATCTL_ATT: 10 dB snaps onto the 12 dB step (RA2).
        h.on("RA;", "RA2;")
        assertTrue(c.setControl(9, 10))
        assertEquals(1, h.writesOf("RA2;"))

        // CATCTL_FIL: app filter 2 = FL01 (B); the answer carries the
        // 270 Hz-option digit.
        h.on("FL0;", "FL011;")
        assertTrue(c.setControl(1, 2))
        assertEquals(1, h.writesOf("FL01;"))
        // Selection C refused by the rig (two-filter menu): mismatch fails.
        h.on("FL0;", "FL011;")
        assertFalse(c.setControl(1, 3))

        assertFalse(c.setControl(5, 2))
        assertFalse(c.setControl(6, -1))
        assertFalse(c.setControl(8, 3))
        assertFalse(c.setControl(9, 61))
        assertFalse(c.setControl(1, 0))
        c.disconnect()
    }

    @Test
    fun `set control filter width follows the mode ladders`() {
        val h = FakeRig()
        scriptLanTs890(h)
        val (c, _) = makeClient(h, Link.LAN, Pair("kenwood", "admin"))
        assertTrue(connect(c))

        // Session mode is USB (OM02): SL is a cut frequency there, not a
        // width — refused without traffic.
        assertFalse(c.setControl(12, 2400))
        assertEquals(0, h.writesOf("SL0;"))

        // In CW the SL parameter is the passband width: 500 Hz = ID 10.
        h.on("OM0;", "OM03;")
        assertTrue(c.setMode(3))
        h.on("SL0;", "SL010;")
        assertTrue(c.setControl(12, 500))
        assertEquals(1, h.writesOf("SL010;"))

        // 460 Hz snaps to the nearest rung, 450 Hz (ID 09).
        h.on("SL0;", "SL009;")
        assertTrue(c.setControl(12, 460))
        assertEquals(1, h.writesOf("SL009;"))

        // A mismatching read-back fails the set.
        h.on("SL0;", "SL005;")
        assertFalse(c.setControl(12, 100))
        c.disconnect()
    }

    @Test
    fun `unsupported control ids send nothing`() {
        val h = FakeRig()
        scriptLanTs890(h)
        val (c, _) = makeClient(h, Link.LAN, Pair("kenwood", "admin"))
        assertTrue(connect(c))
        // Wait for the fire-and-forget connect traffic (AI2/DD01) to flush
        // so the write log is stable before the snapshot.
        waitUntil { h.writesOf("DD01;") == 1 }
        val before = h.written().size

        // CATCTL_PBT_IN / CATCTL_PBT_OUT: IS is a single IF shift, not a
        // passband-tune pair — false by design.
        assertFalse(c.setControl(10, 128))
        assertFalse(c.setControl(11, 128))
        // Unknown ids likewise.
        assertFalse(c.setControl(0, 1))
        assertFalse(c.setControl(99, 1))
        Thread.sleep(30)
        assertEquals(before, h.written().size)
        c.disconnect()
    }
}
