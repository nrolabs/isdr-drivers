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
import java.util.ArrayDeque
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Client behaviour against a scripted transport: connect handshake,
 * request/retry/NAK handling, transceive tracking, and scope delivery on
 * the spectrum plane with an empty IQ block.
 */
class CivClientTest {

    private companion object {
        const val RIG = 0x94 // IC-7300 address
    }

    /**
     * Answers each written frame from a script keyed by command byte,
     * echoing the controller's own frame first the way the shared bus does.
     */
    private class FakeRig(private val echo: Boolean) : CivTransport {
        private val lock = Object()
        private val replies = ArrayList<Pair<Int, ArrayDeque<ByteArray>>>()
        private val inbox = ArrayDeque<Byte>()
        val written = ArrayList<ByteArray>()

        fun on(cmd: Int, reply: ByteArray) {
            synchronized(lock) {
                val entry = replies.firstOrNull { it.first == cmd }
                if (entry != null) entry.second.add(reply)
                else replies.add(Pair(cmd, ArrayDeque(listOf(reply))))
            }
        }

        fun ack(cmd: Int) = on(
            cmd,
            byteArrayOf(0xFE.toByte(), 0xFE.toByte(), 0xE0.toByte(), RIG.toByte(),
                0xFB.toByte(), 0xFD.toByte()),
        )

        fun nak(cmd: Int) = on(
            cmd,
            byteArrayOf(0xFE.toByte(), 0xFE.toByte(), 0xE0.toByte(), RIG.toByte(),
                0xFA.toByte(), 0xFD.toByte()),
        )

        fun reply(cmd: Int, data: ByteArray) {
            val f = byteArrayOf(0xFE.toByte(), 0xFE.toByte(), 0xE0.toByte(), RIG.toByte(),
                cmd.toByte()) + data + byteArrayOf(0xFD.toByte())
            on(cmd, f)
        }

        /** Push unsolicited bytes (transceive, scope) into the read stream. */
        fun pushUnsolicited(frame: ByteArray) {
            synchronized(lock) { frame.forEach { inbox.add(it) } }
        }

        fun writesOf(cmd: Int): Int = synchronized(lock) {
            written.count { it.size > 4 && (it[4].toInt() and 0xFF) == cmd }
        }

        override fun writeAll(bytes: ByteArray) {
            synchronized(lock) {
                written.add(bytes.copyOf())
                if (echo) bytes.forEach { inbox.add(it) }
                val cmd = bytes[4].toInt() and 0xFF
                val entry = replies.firstOrNull { it.first == cmd }
                if (entry != null && entry.second.isNotEmpty()) {
                    entry.second.removeFirst().forEach { inbox.add(it) }
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
                // Behave like the serial timeout so the reader loop breathes.
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

    private fun makeClient(rig: FakeRig, addr: Int): Pair<CivClient, Captured> {
        val cap = Captured()
        val client = CivClient(
            rig, addr,
            { spectrum, iq -> synchronized(cap) { cap.spectra.add(Pair(spectrum.copyOf(), iq.size)) } },
            { up, msg -> synchronized(cap) { cap.status.add(Pair(up, msg)) } },
        )
        return Pair(client, cap)
    }

    /** Handshake replies a live IC-7300 gives. */
    private fun scriptConnect(rig: FakeRig) {
        rig.reply(P.CMD_READ_ID, byteArrayOf(P.SUB_ID.toByte(), RIG.toByte()))
        rig.reply(P.CMD_READ_FREQ, P.toBcdLe(14_074_000, 5)!!)
        rig.reply(P.CMD_READ_MODE, byteArrayOf(P.MODE_USB.toByte(), 0x01))
        rig.ack(P.CMD_SCOPE) // scope on
        rig.ack(P.CMD_SCOPE) // waveform output on
    }

    private fun connect(c: CivClient): Boolean = runBlocking { c.connect() }

    @Test
    fun `connect reads state and enables scope`() {
        val rig = FakeRig(echo = true)
        scriptConnect(rig)
        val (c, cap) = makeClient(rig, RIG)
        assertTrue(connect(c))
        assertEquals(14_074_000L, c.frequencyHz())
        assertEquals(P.MODE_USB, c.mode())
        assertTrue(c.scopeCapable())
        assertEquals("IC-7300", c.modelName())
        assertEquals(2, rig.writesOf(P.CMD_SCOPE))
        assertEquals(listOf(Pair(true, "IC-7300")), cap.status)
        c.disconnect()
    }

    @Test
    fun `connect fails when the port is silent`() {
        val rig = FakeRig(echo = false)
        val (c, cap) = makeClient(rig, RIG)
        assertFalse(connect(c))
        assertEquals(1, cap.status.size)
        assertFalse(cap.status[0].first)
    }

    @Test
    fun `probe finds the rig address`() {
        val rig = FakeRig(echo = true)
        scriptConnect(rig)
        val (c, _) = makeClient(rig, 0)
        assertTrue(connect(c))
        assertEquals("IC-7300", c.modelName())
        c.disconnect()
    }

    @Test
    fun `set frequency acked updates cache and nak does not`() {
        val rig = FakeRig(echo = true)
        scriptConnect(rig)
        val (c, _) = makeClient(rig, RIG)
        assertTrue(connect(c))

        rig.ack(P.CMD_WRITE_FREQ)
        c.setFrequency(7_074_000)
        assertEquals(7_074_000L, c.frequencyHz())

        rig.nak(P.CMD_WRITE_FREQ)
        c.setFrequency(50_313_000)
        assertEquals(7_074_000L, c.frequencyHz())
        c.disconnect()
    }

    @Test
    fun `request is retried until a reply lands`() {
        val rig = FakeRig(echo = true)
        scriptConnect(rig)
        val (c, _) = makeClient(rig, RIG)
        assertTrue(connect(c))

        // No reply scripted for the first attempt; the second gets the ACK.
        rig.on(P.CMD_WRITE_FREQ, ByteArray(0))
        rig.ack(P.CMD_WRITE_FREQ)
        c.setFrequency(7_074_000)
        assertEquals(7_074_000L, c.frequencyHz())
        assertEquals(2, rig.writesOf(P.CMD_WRITE_FREQ))
        c.disconnect()
    }

    @Test
    fun `transceive frames update frequency and mode`() {
        val rig = FakeRig(echo = true)
        scriptConnect(rig)
        val (c, _) = makeClient(rig, RIG)
        assertTrue(connect(c))

        // The operator turns the dial: the rig broadcasts the new frequency.
        rig.pushUnsolicited(
            byteArrayOf(0xFE.toByte(), 0xFE.toByte(), 0x00, RIG.toByte(),
                P.CMD_TRANSCEIVE_FREQ.toByte()) +
                P.toBcdLe(3_573_000, 5)!! + byteArrayOf(0xFD.toByte()),
        )
        rig.pushUnsolicited(
            byteArrayOf(0xFE.toByte(), 0xFE.toByte(), 0x00, RIG.toByte(),
                P.CMD_TRANSCEIVE_MODE.toByte(), P.MODE_CW.toByte(), 0x01, 0xFD.toByte()),
        )

        val deadline = System.currentTimeMillis() + 1000
        while (c.frequencyHz() != 3_573_000L && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
        assertEquals(3_573_000L, c.frequencyHz())
        assertEquals(P.MODE_CW, c.mode())
        c.disconnect()
    }

    @Test
    fun `scope sweep is delivered as spectrum with empty iq`() {
        val rig = FakeRig(echo = true)
        scriptConnect(rig)
        val (c, cap) = makeClient(rig, RIG)
        assertTrue(connect(c))

        // One complete 3-division sweep pushed by the rig.
        val head = byteArrayOf(0xFE.toByte(), 0xFE.toByte(), 0x00, RIG.toByte(),
            P.CMD_SCOPE.toByte(), P.SUB_SCOPE_WAVE.toByte())
        rig.pushUnsolicited(
            head + byteArrayOf(0x00, 0x01, 0x03, 0x00) +
                P.toBcdLe(14_100_000, 5)!! + P.toBcdLe(250_000, 5)!! +
                byteArrayOf(0x00, 0xFD.toByte()),
        )
        rig.pushUnsolicited(
            head + byteArrayOf(0x00, 0x02, 0x03, 0, 80, 160.toByte(), 0xFD.toByte()),
        )
        rig.pushUnsolicited(
            head + byteArrayOf(0x00, 0x03, 0x03, 160.toByte(), 0, 0xFD.toByte()),
        )

        val deadline = System.currentTimeMillis() + 1000
        while (synchronized(cap) { cap.spectra.isEmpty() } &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(5)
        }
        synchronized(cap) {
            assertEquals(1, cap.spectra.size)
            val (spectrum, iqLen) = cap.spectra[0]
            assertEquals(0, iqLen)
            assertArrayEquals(floatArrayOf(-80f, -40f, 0f, 0f, -80f), spectrum, 0f)
        }
        assertEquals(Pair(13_850_000L, 14_350_000L), c.scopeEdges())
        assertEquals(500_000, c.sampleRateHz())
        c.disconnect()
    }

    @Test
    fun `spectrum disable suppresses delivery`() {
        val rig = FakeRig(echo = true)
        scriptConnect(rig)
        val (c, cap) = makeClient(rig, RIG)
        assertTrue(connect(c))
        c.spectrumEnabled = false

        val head = byteArrayOf(0xFE.toByte(), 0xFE.toByte(), 0x00, RIG.toByte(),
            P.CMD_SCOPE.toByte(), P.SUB_SCOPE_WAVE.toByte())
        rig.pushUnsolicited(
            head + byteArrayOf(0x00, 0x01, 0x02, 0x00) +
                P.toBcdLe(14_100_000, 5)!! + P.toBcdLe(250_000, 5)!! +
                byteArrayOf(0x00, 0xFD.toByte()),
        )
        rig.pushUnsolicited(head + byteArrayOf(0x00, 0x02, 0x02, 1, 2, 0xFD.toByte()))

        Thread.sleep(100)
        synchronized(cap) { assertTrue(cap.spectra.isEmpty()) }
        c.disconnect()
    }

    @Test
    fun `ptt tracks only acknowledged keying`() {
        val rig = FakeRig(echo = true)
        scriptConnect(rig)
        val (c, _) = makeClient(rig, RIG)
        assertTrue(connect(c))
        assertFalse(c.isTransmitting())

        rig.ack(P.CMD_PTT)
        c.setPtt(true)
        assertTrue(c.isTransmitting())

        rig.nak(P.CMD_PTT)
        c.setPtt(false)
        assertTrue(c.isTransmitting())

        rig.ack(P.CMD_PTT)
        c.setPtt(false)
        assertFalse(c.isTransmitting())
        c.disconnect()
    }

    @Test
    fun `set sample rate snaps to a scope span`() {
        val rig = FakeRig(echo = true)
        scriptConnect(rig)
        val (c, _) = makeClient(rig, RIG)
        assertTrue(connect(c))

        rig.ack(P.CMD_SCOPE)
        c.setSampleRate(300_000)
        assertEquals(250_000, c.sampleRateHz())
        c.disconnect()
    }

    @Test
    fun `disconnect is idempotent and stops the reader`() {
        val rig = FakeRig(echo = true)
        scriptConnect(rig)
        val (c, _) = makeClient(rig, RIG)
        assertTrue(connect(c))
        c.disconnect()
        c.disconnect()
        c.setFrequency(7_000_000) // must not hang or throw after teardown
        assertEquals(14_074_000L, c.frequencyHz())
    }
}
