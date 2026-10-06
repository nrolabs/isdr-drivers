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
import com.isaklab.isdrproto.CatRepeater
import com.isaklab.isdrproto.CatRepeaterConfig
import com.isaklab.isdrproto.DriverProto
import com.isaklab.isdrproto.Frames
import com.isaklab.isdrproto.getTelemetry
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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

        // Shared receive-control id space (CMD_CAT_SET_CONTROL).
        const val CATCTL_FIL = 1
        const val CATCTL_RF_GAIN = 2
        const val CATCTL_SQUELCH = 3
        const val CATCTL_NR = 4
        const val CATCTL_NB = 5
        const val CATCTL_NOTCH_AUTO = 6
        const val CATCTL_AGC = 7
        const val CATCTL_PREAMP = 8
        const val CATCTL_ATT = 9
        const val CATCTL_PBT_IN = 10
        const val CATCTL_PBT_OUT = 11
        const val CATCTL_FILTER_WIDTH = 12
        const val CATCTL_AF_GAIN = 13
        const val CATCTL_RF_POWER = 14
    }

    /**
     * Answers each written frame from a script keyed by command byte,
     * echoing the controller's own frame first the way the shared bus does.
     */
    private class FakeRig(private val echo: Boolean, private val address: Int = RIG) : CivTransport {
        private val lock = Object()
        private val replies = ArrayList<Pair<Int, ArrayDeque<ByteArray>>>()
        private val inbox = ArrayDeque<Byte>()
        val written = ArrayList<ByteArray>()
        @Volatile var beforeWrite: ((ByteArray) -> Unit)? = null

        fun on(cmd: Int, reply: ByteArray) {
            synchronized(lock) {
                val entry = replies.firstOrNull { it.first == cmd }
                if (entry != null) entry.second.add(reply)
                else replies.add(Pair(cmd, ArrayDeque(listOf(reply))))
            }
        }

        fun ack(cmd: Int) = on(
            cmd,
            byteArrayOf(0xFE.toByte(), 0xFE.toByte(), 0xE0.toByte(), address.toByte(),
                0xFB.toByte(), 0xFD.toByte()),
        )

        fun nak(cmd: Int) = on(
            cmd,
            byteArrayOf(0xFE.toByte(), 0xFE.toByte(), 0xE0.toByte(), address.toByte(),
                0xFA.toByte(), 0xFD.toByte()),
        )

        fun reply(cmd: Int, data: ByteArray) {
            val f = byteArrayOf(0xFE.toByte(), 0xFE.toByte(), 0xE0.toByte(), address.toByte(),
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

        fun lastWritten(): ByteArray = synchronized(lock) { written.last().copyOf() }

        fun totalWrites(): Int = synchronized(lock) { written.size }

        fun writtenFrames(): List<ByteArray> = synchronized(lock) {
            written.map { it.copyOf() }
        }

        override fun writeAll(bytes: ByteArray) {
            beforeWrite?.invoke(bytes)
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

    private fun makeClient(
        rig: FakeRig,
        addr: Int,
        requiredReportedCivAddress: Int? = null,
    ): Pair<CivClient, Captured> {
        val cap = Captured()
        val client = CivClient(
            rig, addr,
            { spectrum, iq -> synchronized(cap) { cap.spectra.add(Pair(spectrum.copyOf(), iq.size)) } },
            { up, msg -> synchronized(cap) { cap.status.add(Pair(up, msg)) } },
            requiredReportedCivAddress,
        )
        return Pair(client, cap)
    }

    /** Handshake replies a live IC-7300 gives. */
    private fun scriptConnect(rig: FakeRig, address: Int = RIG) {
        rig.reply(P.CMD_READ_ID, byteArrayOf(P.SUB_ID.toByte(), address.toByte()))
        rig.reply(P.CMD_READ_FREQ, P.toBcdLe(14_074_000, 5)!!)
        rig.reply(
            P.CMD_MODE_DATA,
            bytes(P.SUB_MODE_DATA_SELECTED, P.MODE_USB, 0, 1),
        )
        rig.ack(P.CMD_SCOPE) // scope on
        rig.reply(P.CMD_SCOPE, bytes(P.SUB_SCOPE_ON, 1))
        rig.ack(P.CMD_SCOPE) // waveform output on
        rig.reply(P.CMD_SCOPE, bytes(P.SUB_SCOPE_WAVE_OUTPUT, 1))
    }

    private fun connect(c: CivClient): Boolean = runBlocking { c.connect() }

    private fun scriptLegacyRepeaterState(
        rig: FakeRig,
        repeaterTone: Int,
        toneSquelch: Int,
        txTone: Int,
        rxTone: Int,
    ) {
        rig.reply(P.CMD_FUNC, bytes(P.SUB_FUNC_REPEATER_TONE, repeaterTone))
        rig.reply(P.CMD_FUNC, bytes(P.SUB_FUNC_TONE_SQUELCH, toneSquelch))
        rig.reply(P.CMD_TONE, byteArrayOf(P.SUB_TONE_TX.toByte()) + P.toBcdBe(txTone.toLong(), 3)!!)
        rig.reply(P.CMD_TONE, byteArrayOf(P.SUB_TONE_RX.toByte()) + P.toBcdBe(rxTone.toLong(), 3)!!)
    }

    private fun waitForWrittenBody(rig: FakeRig, timeoutMs: Long, vararg body: Int): Boolean {
        val expected = bytes(*body)
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        do {
            if (rig.writtenFrames().any { frame ->
                    frame.size == expected.size + 5 &&
                        frame.copyOfRange(4, frame.size - 1).contentEquals(expected)
                }
            ) {
                return true
            }
            Thread.sleep(2)
        } while (System.nanoTime() < deadline)
        return false
    }

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
        assertEquals(4, rig.writesOf(P.CMD_SCOPE))
        assertEquals(listOf(Pair(true, "IC-7300")), cap.status)
        c.disconnect()
    }

    @Test
    fun `required reported address match connects before reading state`() {
        val rig = FakeRig(echo = true)
        scriptConnect(rig)
        val (c, cap) = makeClient(rig, RIG, requiredReportedCivAddress = RIG)

        assertTrue(connect(c))
        assertEquals(listOf(Pair(true, "IC-7300")), cap.status)
        assertEquals(1, rig.writesOf(P.CMD_READ_ID))
        assertEquals(1, rig.writesOf(P.CMD_READ_FREQ))
        assertEquals(4, rig.writesOf(P.CMD_SCOPE))
        c.disconnect()
    }

    @Test
    fun `required reported address mismatch refuses before state or scope`() {
        val rig = FakeRig(echo = true)
        rig.reply(P.CMD_READ_ID, bytes(P.SUB_ID, 0xA4))
        val (c, cap) = makeClient(rig, RIG, requiredReportedCivAddress = RIG)

        assertFalse(connect(c))
        assertEquals(1, rig.totalWrites())
        assertEquals(1, rig.writesOf(P.CMD_READ_ID))
        assertEquals(0, rig.writesOf(P.CMD_READ_FREQ))
        assertEquals(0, rig.writesOf(P.CMD_SCOPE))
        val status = synchronized(cap) { ArrayList(cap.status) }
        assertEquals(1, status.size)
        assertFalse(status[0].first)
        assertTrue(status[0].second, status[0].second.contains("address mismatch"))
        assertTrue(status[0].second, status[0].second.contains("0x94"))
        assertTrue(status[0].second, status[0].second.contains("0xA4"))
    }

    @Test
    fun `required reported address rejects malformed identity before state`() {
        val rig = FakeRig(echo = true)
        rig.reply(P.CMD_READ_ID, bytes(P.SUB_ID))
        val (c, cap) = makeClient(rig, RIG, requiredReportedCivAddress = RIG)

        assertFalse(connect(c))
        assertEquals(1, rig.totalWrites())
        assertEquals(0, rig.writesOf(P.CMD_READ_FREQ))
        val status = synchronized(cap) { ArrayList(cap.status) }
        assertEquals(1, status.size)
        assertTrue(status[0].second, status[0].second.contains("malformed CI-V"))
    }

    @Test
    fun `required reported address refuses a silent port without state traffic`() {
        val rig = FakeRig(echo = false)
        val (c, cap) = makeClient(rig, RIG, requiredReportedCivAddress = RIG)

        assertFalse(connect(c))
        assertEquals(3, rig.writesOf(P.CMD_READ_ID))
        assertEquals(3, rig.totalWrites())
        assertEquals(0, rig.writesOf(P.CMD_READ_FREQ))
        assertEquals(0, rig.writesOf(P.CMD_SCOPE))
        val status = synchronized(cap) { ArrayList(cap.status) }
        assertEquals(1, status.size)
        assertTrue(status[0].second, status[0].second.contains("did not answer"))
        assertTrue(status[0].second, status[0].second.contains("0x94"))
    }

    @Test
    fun `generic identity mode preserves legacy nonempty reply compatibility`() {
        val rig = FakeRig(echo = true)
        // Generic discovery historically required only a correlated reply.
        rig.reply(P.CMD_READ_ID, bytes(P.SUB_ID))
        rig.reply(P.CMD_READ_FREQ, P.toBcdLe(14_074_000, 5)!!)
        rig.reply(P.CMD_MODE_DATA, bytes(P.SUB_MODE_DATA_SELECTED, P.MODE_USB, 0, 1))
        rig.ack(P.CMD_SCOPE)
        rig.reply(P.CMD_SCOPE, bytes(P.SUB_SCOPE_ON, 1))
        rig.ack(P.CMD_SCOPE)
        rig.reply(P.CMD_SCOPE, bytes(P.SUB_SCOPE_WAVE_OUTPUT, 1))
        val (c, cap) = makeClient(rig, RIG)

        assertTrue(connect(c))
        assertEquals(listOf(Pair(true, "IC-7300")), cap.status)
        assertEquals(14_074_000L, c.frequencyHz())
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
        rig.reply(P.CMD_READ_FREQ, P.toBcdLe(7_074_000, 5)!!)
        c.setFrequency(7_074_000)
        assertEquals(7_074_000L, c.frequencyHz())

        rig.nak(P.CMD_WRITE_FREQ)
        try {
            c.setFrequency(50_313_000)
        } catch (_: IllegalStateException) {
        }
        assertEquals(7_074_000L, c.frequencyHz())
        c.disconnect()
    }

    @Test
    fun `ic905 tunes and tracks the ten gigahertz band`() {
        val address = CivModels.ADDR_IC905
        val initial = 10_368_000_000L
        val target = 10_368_100_000L
        val rig = FakeRig(echo = true, address = address)
        rig.reply(P.CMD_READ_ID, bytes(P.SUB_ID, address))
        rig.reply(P.CMD_READ_FREQ, P.toBcdLe(initial, 6)!!)
        rig.reply(
            P.CMD_MODE_DATA,
            bytes(P.SUB_MODE_DATA_SELECTED, P.MODE_USB, 0, 1),
        )
        rig.ack(P.CMD_SCOPE)
        rig.reply(P.CMD_SCOPE, bytes(P.SUB_SCOPE_ON, 1))
        rig.ack(P.CMD_SCOPE)
        rig.reply(P.CMD_SCOPE, bytes(P.SUB_SCOPE_WAVE_OUTPUT, 1))
        val (c, _) = makeClient(rig, address)
        assertTrue(connect(c))
        assertEquals(initial, c.frequencyHz())

        rig.ack(P.CMD_WRITE_FREQ)
        rig.reply(P.CMD_READ_FREQ, P.toBcdLe(target, 6)!!)
        c.setFrequency(target)
        assertEquals(target, c.frequencyHz())
        val write = rig.writtenFrames().last { frame ->
            frame.size > 4 && (frame[4].toInt() and 0xFF) == P.CMD_WRITE_FREQ
        }
        assertArrayEquals(P.writeFrequency(address, target)!!, write)

        rig.pushUnsolicited(
            bytes(0xFE, 0xFE, 0x00, address, P.CMD_TRANSCEIVE_FREQ) +
                P.toBcdLe(10_450_000_000L, 6)!! + bytes(0xFD),
        )
        val deadline = System.currentTimeMillis() + 1000
        while (c.frequencyHz() != 10_450_000_000L && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
        assertEquals(10_450_000_000L, c.frequencyHz())
        c.disconnect()
    }

    @Test
    fun `tx frequency refuses without retuning receive vfo`() {
        val rig = FakeRig(echo = true)
        scriptConnect(rig)
        val (c, _) = makeClient(rig, RIG)
        assertTrue(connect(c))
        val writesBefore = rig.totalWrites()

        assertFalse(c.setTxFrequency(14_250_000))
        assertEquals(14_074_000L, c.frequencyHz())
        assertEquals(writesBefore, rig.totalWrites())
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
        rig.reply(P.CMD_READ_FREQ, P.toBcdLe(7_074_000, 5)!!)
        c.setFrequency(7_074_000)
        assertEquals(7_074_000L, c.frequencyHz())
        assertEquals(2, rig.writesOf(P.CMD_WRITE_FREQ))
        c.disconnect()
    }

    @Test
    fun `transceive frames update frequency and mode`() {
        val address = 0x5E // A control-only legacy model without command 0x26.
        val rig = FakeRig(echo = true, address = address)
        rig.reply(P.CMD_READ_ID, bytes(P.SUB_ID, address))
        rig.reply(P.CMD_READ_FREQ, P.toBcdLe(14_074_000, 5)!!)
        rig.reply(P.CMD_READ_MODE, bytes(P.MODE_USB, 1))
        val (c, _) = makeClient(rig, address)
        assertTrue(connect(c))
        val announced = java.util.concurrent.CopyOnWriteArrayList<Int>()
        c.setStateListener { announced += c.currentCatMode() }

        // The operator turns the dial: the rig broadcasts the new frequency.
        rig.pushUnsolicited(
            byteArrayOf(0xFE.toByte(), 0xFE.toByte(), 0x00, address.toByte(),
                P.CMD_TRANSCEIVE_FREQ.toByte()) +
                P.toBcdLe(3_573_000, 5)!! + byteArrayOf(0xFD.toByte()),
        )
        rig.pushUnsolicited(
            byteArrayOf(0xFE.toByte(), 0xFE.toByte(), 0x00, address.toByte(),
                P.CMD_TRANSCEIVE_MODE.toByte(), P.MODE_CW.toByte(), 0x01, 0xFD.toByte()),
        )

        val deadline = System.currentTimeMillis() + 1000
        while (!announced.contains(P.MODE_CW) && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
        assertEquals(3_573_000L, c.frequencyHz())
        assertEquals(P.MODE_CW, c.mode())
        assertTrue(announced.contains(P.MODE_CW))
        c.disconnect()
    }

    @Test
    fun `transceive mode waits for DATA readback and still announces unchanged base code`() {
        for (dataMode in listOf(0, 3)) {
            val rig = FakeRig(echo = true)
            scriptConnect(rig)
            rig.reply(P.CMD_READ_METER, bytes(P.SUB_METER_S, 0, 0))
            rig.reply(P.CMD_MODE_DATA, bytes(0, P.MODE_AM, dataMode, 2))
            val expected = P.MODE_AM or (if (dataMode > 0) DriverProto.CAT_MODE_DATA_FLAG else 0)
            val announced = java.util.concurrent.CopyOnWriteArrayList<Int>()
            val seen = java.util.concurrent.CountDownLatch(1)
            val c = CivClient(rig, RIG, { _, _ -> }, { _, _ -> }, onControl = { _, _ -> })
            c.setStateListener {
                announced += c.currentCatMode()
                if (c.currentCatMode() == expected) seen.countDown()
            }
            assertTrue(connect(c))
            try {
                // The following frequency announcement must not expose a
                // made-up DATA-off mode from the incomplete 0x01 broadcast.
                rig.pushUnsolicited(bytes(0xFE, 0xFE, 0, RIG, P.CMD_TRANSCEIVE_MODE, P.MODE_AM, 2, 0xFD) +
                    if (dataMode > 0) bytes(0xFE, 0xFE, 0, RIG, P.CMD_TRANSCEIVE_FREQ) +
                        P.toBcdLe(7_100_000, 5)!! + bytes(0xFD) else byteArrayOf())
                assertTrue(seen.await(2, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals(expected, c.currentCatMode())
                if (dataMode > 0) assertFalse(announced.contains(P.MODE_AM))
            } finally { c.disconnect() }
        }
    }

    @Test
    fun `scope sweep is delivered as spectrum with empty iq`() {
        val rig = FakeRig(echo = true)
        scriptConnect(rig)
        val (c, cap) = makeClient(rig, RIG)
        assertTrue(connect(c))

        // A complete LAN sweep; fragmented USB geometry is covered in the codec suite.
        val head = bytes(0xFE, 0xFE, 0, RIG, P.CMD_SCOPE, P.SUB_SCOPE_WAVE)
        rig.pushUnsolicited(head + bytes(0, 1, 1, 0) +
            P.toBcdLe(14_100_000, 5)!! + P.toBcdLe(250_000, 5)!! + bytes(0) +
            bytes(0, 80, 160, 160, 0) + ByteArray(470) + bytes(0xFD))

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
            assertEquals(475, spectrum.size)
            assertArrayEquals(floatArrayOf(-80f, -40f, 0f, 0f, -80f), spectrum.copyOf(5), 0f)
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
        rig.ack(P.CMD_SCOPE)
        rig.reply(P.CMD_SCOPE, bytes(P.SUB_SCOPE_WAVE_OUTPUT, 0))
        c.spectrumEnabled = false

        val head = bytes(0xFE, 0xFE, 0, RIG, P.CMD_SCOPE, P.SUB_SCOPE_WAVE)
        rig.pushUnsolicited(head + bytes(0, 1, 1, 0) +
            P.toBcdLe(14_100_000, 5)!! + P.toBcdLe(250_000, 5)!! + bytes(0) +
            ByteArray(475) + bytes(0xFD))

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
        rig.reply(P.CMD_PTT, byteArrayOf(P.SUB_PTT.toByte(), 1))
        c.setPtt(true)
        assertTrue(c.isTransmitting())

        rig.nak(P.CMD_PTT)
        try {
            c.setPtt(false)
        } catch (_: IllegalStateException) {
        }
        assertTrue(c.isTransmitting())

        rig.ack(P.CMD_PTT)
        rig.reply(P.CMD_PTT, byteArrayOf(P.SUB_PTT.toByte(), 0))
        c.setPtt(false)
        assertFalse(c.isTransmitting())
        c.disconnect()
    }

    @Test
    fun `IC7300 repeater accepts only the final physical readback`() {
        val rig = FakeRig(echo = true)
        scriptConnect(rig)
        val (c, _) = makeClient(rig, RIG)
        assertTrue(connect(c))
        assertEquals(
            CatRepeater.CAP_CTCSS_TX or CatRepeater.CAP_CTCSS_RX,
            c.catRepeaterCapabilities(),
        )

        val requested = CatRepeaterConfig(
            CatRepeater.DUPLEX_SIMPLEX,
            0,
            CatRepeater.TONE_CTCSS,
            885,
            CatRepeater.DCS_NORMAL,
            CatRepeater.TONE_CTCSS,
            915,
            CatRepeater.DCS_NORMAL,
        )
        rig.reply(P.CMD_PTT, bytes(P.SUB_PTT, 0))
        rig.reply(P.CMD_READ_MODE, bytes(P.MODE_FM, 1))
        scriptLegacyRepeaterState(rig, 0, 0, 885, 885)
        rig.ack(P.CMD_FUNC) // make TX/RX selectors inert
        rig.ack(P.CMD_FUNC)
        rig.ack(P.CMD_TONE) // write exact latent TX/RX tones
        rig.ack(P.CMD_TONE)
        rig.ack(P.CMD_FUNC) // activate tone squelch last
        rig.reply(P.CMD_READ_MODE, bytes(P.MODE_FM, 1))
        scriptLegacyRepeaterState(rig, 0, 1, 885, 915)

        assertEquals(null, c.setCatRepeater(requested))
        assertTrue(
            rig.writtenFrames().any {
                it.contentEquals(P.setCtcssTone(RIG, P.SUB_TONE_TX, 885))
            },
        )
        assertTrue(
            rig.writtenFrames().any {
                it.contentEquals(P.setCtcssTone(RIG, P.SUB_TONE_RX, 915))
            },
        )
        c.disconnect()
    }

    @Test
    fun `undocumented CI-V profile refuses repeater without io`() {
        val address = 0x5E
        val rig = FakeRig(echo = true, address = address)
        rig.reply(P.CMD_READ_ID, bytes(P.SUB_ID, address))
        rig.reply(P.CMD_READ_FREQ, P.toBcdLe(14_074_000, 5)!!)
        rig.reply(P.CMD_READ_MODE, bytes(P.MODE_FM, 1))
        val (c, _) = makeClient(rig, address)
        assertTrue(connect(c))
        assertEquals(0, c.catRepeaterCapabilities())
        val before = rig.totalWrites()

        val error = c.setCatRepeater(
            CatRepeaterConfig(
                CatRepeater.DUPLEX_SIMPLEX,
                0,
                CatRepeater.TONE_OFF,
                0,
                CatRepeater.DCS_NORMAL,
                CatRepeater.TONE_OFF,
                0,
                CatRepeater.DCS_NORMAL,
            ),
        )

        assertTrue(error?.contains("no documented repeater controls") == true)
        assertEquals(before, rig.totalWrites())
        c.disconnect()
    }

    @Test
    fun `priority unkey cancels CI-V repeater at a complete frame boundary`() {
        val rig = FakeRig(echo = true)
        scriptConnect(rig)
        val (c, _) = makeClient(rig, RIG)
        assertTrue(connect(c))
        rig.reply(P.CMD_PTT, bytes(P.SUB_PTT, 0))
        rig.reply(P.CMD_READ_MODE, bytes(P.MODE_FM, 1))
        scriptLegacyRepeaterState(rig, 0, 0, 885, 885)

        val requested = CatRepeaterConfig(
            CatRepeater.DUPLEX_SIMPLEX,
            0,
            CatRepeater.TONE_CTCSS,
            885,
            CatRepeater.DCS_NORMAL,
            CatRepeater.TONE_OFF,
            0,
            CatRepeater.DCS_NORMAL,
        )
        val result = AtomicReference<String?>()
        val transaction = thread(name = "civ-repeater-test") {
            result.set(c.setCatRepeater(requested))
        }
        assertTrue(
            waitForWrittenBody(
                rig,
                1_000,
                P.CMD_FUNC,
                P.SUB_FUNC_REPEATER_TONE,
                0,
            ),
        )

        // The mutating FUNC write deliberately has no reply. Cancellation
        // must drain that untagged reply slot before PTT OFF uses the bus.
        rig.ack(P.CMD_PTT)
        rig.reply(P.CMD_PTT, bytes(P.SUB_PTT, 0))
        val cancelAt = System.nanoTime()
        c.requestCatRepeaterCancelForUnkey()
        c.setPtt(false)
        val unkeyLatencyMs = (System.nanoTime() - cancelAt) / 1_000_000
        transaction.join(1_000)

        assertFalse(transaction.isAlive)
        assertTrue(result.get()?.contains("cancelled for priority unkey") == true)
        assertTrue("unkey latency was ${unkeyLatencyMs}ms", unkeyLatencyMs < 750)
        val frames = rig.writtenFrames()
        val mutation = P.setFunc(RIG, P.SUB_FUNC_REPEATER_TONE, 0)
        val unkey = P.setPtt(RIG, false)
        val mutationIndex = frames.indexOfFirst { it.contentEquals(mutation) }
        val unkeyIndex = frames.indexOfFirst { it.contentEquals(unkey) }
        assertTrue(mutationIndex >= 0)
        assertTrue(unkeyIndex > mutationIndex)
        assertEquals(
            1,
            frames.subList(mutationIndex, unkeyIndex).count {
                it.size > 4 && (it[4].toInt() and 0xFF) == P.CMD_FUNC
            },
        )
        c.disconnect()
    }

    @Test
    fun `scope span uses the exact common model ladder`() {
        val rig = FakeRig(echo = true)
        scriptConnect(rig)
        val (c, _) = makeClient(rig, RIG)
        assertTrue(connect(c))

        rig.reply(P.CMD_SCOPE, bytes(P.SUB_SCOPE_MODE, 0, 0))
        rig.ack(P.CMD_SCOPE)
        rig.reply(P.CMD_SCOPE, bytes(P.SUB_SCOPE_SPAN, 0) + P.toBcdLe(2_500, 5)!!)
        c.setSampleRate(5_000)
        assertEquals(5_000, c.sampleRateHz())
        val before = rig.writesOf(P.CMD_SCOPE)
        assertThrows(IllegalArgumentException::class.java) { c.setSampleRate(300_000) }
        assertThrows(IllegalArgumentException::class.java) { c.setSampleRate(2_000_000) }
        assertEquals(before, rig.writesOf(P.CMD_SCOPE))
        c.disconnect()
    }

    @Test
    fun `extended scope spans are model specific`() {
        fun connectedAt(address: Int): Pair<CivClient, FakeRig> {
            val rig = FakeRig(echo = true, address = address)
            rig.reply(P.CMD_READ_ID, bytes(P.SUB_ID, address))
            rig.reply(P.CMD_READ_FREQ, P.toBcdLe(145_500_000, 5)!!)
            if (CivModels.supportsModeData(address)) {
                rig.reply(
                    P.CMD_MODE_DATA,
                    bytes(P.SUB_MODE_DATA_SELECTED, P.MODE_FM, 0, 1),
                )
            } else {
                rig.reply(P.CMD_READ_MODE, bytes(P.MODE_FM, 1))
            }
            rig.ack(P.CMD_SCOPE)
            rig.reply(P.CMD_SCOPE, bytes(P.SUB_SCOPE_ON, 1))
            rig.ack(P.CMD_SCOPE)
            rig.reply(P.CMD_SCOPE, bytes(P.SUB_SCOPE_WAVE_OUTPUT, 1))
            val (client, _) = makeClient(rig, address)
            assertTrue(connect(client))
            return Pair(client, rig)
        }

        val (r8600, r8600Rig) = connectedAt(CivModels.ADDR_ICR8600)
        r8600Rig.reply(P.CMD_SCOPE, bytes(P.SUB_SCOPE_MODE, 0, 2))
        r8600Rig.ack(P.CMD_SCOPE)
        r8600Rig.reply(P.CMD_SCOPE, bytes(P.SUB_SCOPE_SPAN, 0) + P.toBcdLe(2_500_000, 5)!!)
        r8600.setSampleRate(5_000_000)
        assertEquals(5_000_000, r8600.sampleRateHz())
        assertThrows(IllegalArgumentException::class.java) { r8600.setSampleRate(10_000_000) }
        r8600.disconnect()

        val (ic905, ic905Rig) = connectedAt(CivModels.ADDR_IC905)
        ic905Rig.reply(P.CMD_SCOPE, bytes(P.SUB_SCOPE_MODE, 0, 0))
        ic905Rig.ack(P.CMD_SCOPE)
        ic905Rig.reply(P.CMD_SCOPE, bytes(P.SUB_SCOPE_SPAN, 0) + P.toBcdLe(25_000_000, 5)!!)
        ic905.setSampleRate(50_000_000)
        assertEquals(50_000_000, ic905.sampleRateHz())
        ic905.disconnect()

        assertEquals(null, CivModels.scopeCaps(CivModels.ADDR_IC7851))
    }

    // ---- receive controls (CATCTL_*) ----------------------------------------

    private fun connectedClient(): Pair<CivClient, FakeRig> {
        val rig = FakeRig(echo = true)
        scriptConnect(rig)
        val (c, _) = makeClient(rig, RIG)
        assertTrue(connect(c))
        return Pair(c, rig)
    }

    private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun `levels are acked bcd writes`() {
        val (c, rig) = connectedClient()
        for ((id, sub) in listOf(
            Pair(CATCTL_RF_GAIN, 0x02),
            Pair(CATCTL_SQUELCH, 0x03),
            Pair(CATCTL_PBT_IN, 0x07),
            Pair(CATCTL_PBT_OUT, 0x08),
            Pair(CATCTL_AF_GAIN, 0x01),
        )) {
            rig.ack(P.CMD_LEVEL)
            rig.reply(P.CMD_LEVEL, bytes(sub, 0x02, 0x00))
            assertTrue("id $id", c.setControl(id, 200))
            assertArrayEquals(
                bytes(0xFE, 0xFE, RIG, 0xE0, 0x14, sub, 0x02, 0x00, 0xFD),
                rig.writtenFrames().let { it[it.lastIndex - 1] },
            )
        }
        // NAK is false; out-of-range writes nothing.
        rig.nak(P.CMD_LEVEL)
        assertFalse(c.setControl(CATCTL_RF_GAIN, 10))
        val before = rig.writesOf(P.CMD_LEVEL)
        assertFalse(c.setControl(CATCTL_RF_GAIN, 256))
        assertFalse(c.setControl(CATCTL_RF_GAIN, -1))
        assertEquals(before, rig.writesOf(P.CMD_LEVEL))
        c.disconnect()
    }

    @Test
    fun `rf power is accepted only after exact physical read back`() {
        val (c, rig) = connectedClient()

        rig.ack(P.CMD_LEVEL)
        rig.reply(
            P.CMD_LEVEL,
            bytes(P.SUB_LEVEL_RFPOWER, 0x02, 0x55),
        )
        assertTrue(c.setControl(CATCTL_RF_POWER, 255))
        val frames = rig.writtenFrames()
        val set = bytes(0xFE, 0xFE, RIG, 0xE0, 0x14, 0x0A, 0x02, 0x55, 0xFD)
        val read = bytes(0xFE, 0xFE, RIG, 0xE0, 0x14, 0x0A, 0xFD)
        assertTrue(frames.indexOfFirst { it.contentEquals(set) } >= 0)
        assertTrue(
            frames.indexOfFirst { it.contentEquals(read) } >
                frames.indexOfFirst { it.contentEquals(set) },
        )

        rig.ack(P.CMD_LEVEL)
        rig.reply(P.CMD_LEVEL, bytes(P.SUB_LEVEL_RFPOWER, 0x01, 0x27))
        assertFalse(c.setControl(CATCTL_RF_POWER, 128))

        rig.nak(P.CMD_LEVEL)
        assertFalse(c.setControl(CATCTL_RF_POWER, 64))
        val before = rig.writesOf(P.CMD_LEVEL)
        assertFalse(c.setControl(CATCTL_RF_POWER, -1))
        assertFalse(c.setControl(CATCTL_RF_POWER, 256))
        assertEquals(before, rig.writesOf(P.CMD_LEVEL))
        c.disconnect()
    }

    @Test
    fun `receive only icom refuses rf power without wire traffic`() {
        val rig = FakeRig(echo = true, address = CivModels.ADDR_ICR8600)
        rig.reply(
            P.CMD_READ_ID,
            bytes(P.SUB_ID, CivModels.ADDR_ICR8600),
        )
        rig.reply(P.CMD_READ_FREQ, P.toBcdLe(14_074_000, 5)!!)
        rig.reply(P.CMD_READ_MODE, bytes(P.MODE_USB, 0x01))
        rig.ack(P.CMD_SCOPE)
        rig.reply(P.CMD_SCOPE, bytes(P.SUB_SCOPE_ON, 1))
        rig.ack(P.CMD_SCOPE)
        rig.reply(P.CMD_SCOPE, bytes(P.SUB_SCOPE_WAVE_OUTPUT, 1))
        val (c, _) = makeClient(rig, CivModels.ADDR_ICR8600)
        assertTrue(connect(c))
        val before = rig.writesOf(P.CMD_LEVEL)
        assertFalse(c.setControl(CATCTL_RF_POWER, 128))
        assertEquals(before, rig.writesOf(P.CMD_LEVEL))
        c.disconnect()
    }

    private fun nrReply(rig: FakeRig, on: Int, level: Int) {
        rig.reply(P.CMD_FUNC, bytes(P.SUB_FUNC_NR, on))
        rig.reply(P.CMD_LEVEL, bytes(P.SUB_LEVEL_NR) + P.toBcdBe2(level)!!)
    }

    @Test
    fun `NR confirms both fields and disabling preserves stored strength`() {
        val (c, rig) = connectedClient()
        nrReply(rig, 1, 136)
        rig.ack(P.CMD_FUNC)
        nrReply(rig, 0, 136)
        assertTrue(c.setControl(CATCTL_NR, 0))
        assertFalse(rig.writtenFrames().any { it.contentEquals(P.setLevel(RIG, P.SUB_LEVEL_NR, 136)) })
        nrReply(rig, 0, 136)
        rig.ack(P.CMD_FUNC)
        rig.ack(P.CMD_LEVEL)
        nrReply(rig, 1, 255)
        assertTrue(c.setControl(CATCTL_NR, 15))
        assertTrue(rig.writtenFrames().any { it.contentEquals(P.setLevel(RIG, P.SUB_LEVEL_NR, 255)) })
        assertFalse(c.setControl(CATCTL_NR, 16))
        c.disconnect()
    }

    @Test
    fun `NR failed strength write restores and verifies both previous fields`() {
        val (c, rig) = connectedClient()
        nrReply(rig, 0, 68)
        rig.ack(P.CMD_FUNC) // new NR on
        rig.nak(P.CMD_LEVEL) // strength rejected after function applied
        rig.ack(P.CMD_LEVEL) // restore stored strength
        rig.ack(P.CMD_FUNC) // restore NR off
        nrReply(rig, 0, 68)
        assertFalse(c.setControl(CATCTL_NR, 15))
        assertTrue(c.catControlError()!!.contains("previous NR state was restored"))
        val frames = rig.writtenFrames()
        val set = frames.indexOfFirst { it.contentEquals(P.setLevel(RIG, P.SUB_LEVEL_NR, 255)) }
        val restore = frames.indexOfFirst { it.contentEquals(P.setLevel(RIG, P.SUB_LEVEL_NR, 68)) }
        assertTrue(restore > set)
        assertTrue(frames.drop(restore).any { it.contentEquals(P.setFunc(RIG, P.SUB_FUNC_NR, 0)) })
        c.disconnect()
    }

    @Test
    fun `NR rollback failure reports uncertainty instead of optimistic success`() {
        val (c, rig) = connectedClient()
        nrReply(rig, 0, 68)
        rig.ack(P.CMD_FUNC)
        rig.nak(P.CMD_LEVEL)
        rig.nak(P.CMD_LEVEL) // rollback strength also fails
        rig.ack(P.CMD_FUNC)
        nrReply(rig, 0, 85) // physical pair differs from snapshot
        assertFalse(c.setControl(CATCTL_NR, 15))
        assertTrue(c.catControlError()!!.contains("rollback failed"))
        assertTrue(c.catControlError()!!.contains("uncertain"))
        c.disconnect()
    }

    @Test
    fun `on off functions and agc and preamp`() {
        val (c, rig) = connectedClient()
        rig.ack(P.CMD_FUNC)
        rig.reply(P.CMD_FUNC, bytes(0x22, 1))
        assertTrue(c.setControl(CATCTL_NB, 1))
        assertArrayEquals(
            bytes(0xFE, 0xFE, RIG, 0xE0, 0x16, 0x22, 0x01, 0xFD),
            rig.writtenFrames().let { it[it.lastIndex - 1] },
        )
        rig.ack(P.CMD_FUNC)
        rig.reply(P.CMD_FUNC, bytes(0x41, 1))
        assertTrue(c.setControl(CATCTL_NOTCH_AUTO, 1))
        assertArrayEquals(
            bytes(0xFE, 0xFE, RIG, 0xE0, 0x16, 0x41, 0x01, 0xFD),
            rig.writtenFrames().let { it[it.lastIndex - 1] },
        )
        rig.ack(P.CMD_FUNC)
        rig.reply(P.CMD_FUNC, bytes(0x12, 3))
        assertTrue(c.setControl(CATCTL_AGC, 3))
        assertArrayEquals(
            bytes(0xFE, 0xFE, RIG, 0xE0, 0x16, 0x12, 0x03, 0xFD),
            rig.writtenFrames().let { it[it.lastIndex - 1] },
        )
        rig.ack(P.CMD_FUNC)
        rig.reply(P.CMD_FUNC, bytes(0x02, 2))
        assertTrue(c.setControl(CATCTL_PREAMP, 2))
        assertArrayEquals(
            bytes(0xFE, 0xFE, RIG, 0xE0, 0x16, 0x02, 0x02, 0xFD),
            rig.writtenFrames().let { it[it.lastIndex - 1] },
        )

        // AGC off does not exist on this command; bad values write nothing.
        val before = rig.writesOf(P.CMD_FUNC)
        assertFalse(c.setControl(CATCTL_AGC, 0))
        assertFalse(c.setControl(CATCTL_AGC, 4))
        assertFalse(c.setControl(CATCTL_PREAMP, 3))
        assertFalse(c.setControl(CATCTL_NB, 2))
        assertEquals(before, rig.writesOf(P.CMD_FUNC))
        c.disconnect()
    }

    @Test
    fun `attenuator and bounds`() {
        val (c, rig) = connectedClient()
        rig.ack(P.CMD_ATTENUATOR)
        rig.reply(P.CMD_ATTENUATOR, bytes(0x20))
        assertTrue(c.setControl(CATCTL_ATT, 20))
        assertArrayEquals(
            bytes(0xFE, 0xFE, RIG, 0xE0, 0x11, 0x20, 0xFD),
            rig.writtenFrames().let { it[it.lastIndex - 1] },
        )
        rig.ack(P.CMD_ATTENUATOR)
        rig.reply(P.CMD_ATTENUATOR, bytes(0x00))
        assertTrue(c.setControl(CATCTL_ATT, 0))
        assertArrayEquals(
            bytes(0xFE, 0xFE, RIG, 0xE0, 0x11, 0x00, 0xFD),
            rig.writtenFrames().let { it[it.lastIndex - 1] },
        )
        rig.nak(P.CMD_ATTENUATOR)
        assertFalse(c.setControl(CATCTL_ATT, 20))
        val before = rig.writesOf(P.CMD_ATTENUATOR)
        assertFalse(c.setControl(CATCTL_ATT, 46))
        assertFalse(c.setControl(CATCTL_ATT, -1))
        assertEquals(before, rig.writesOf(P.CMD_ATTENUATOR))
        c.disconnect()
    }

    @Test
    fun `filter width refreshes physical mode before choosing its exact table`() {
        val (c, rig) = connectedClient() // connect read back USB
        rig.reply(P.CMD_MODE_DATA, bytes(0, P.MODE_USB, 0, 1))
        rig.ack(P.CMD_MEM)
        rig.reply(P.CMD_MEM, bytes(0x03, 0x28))
        rig.reply(P.CMD_MODE_DATA, bytes(0, P.MODE_USB, 0, 1))
        assertTrue(c.setControl(CATCTL_FILTER_WIDTH, 2400))
        assertArrayEquals(
            bytes(0xFE, 0xFE, RIG, 0xE0, 0x1A, 0x03, 0x28, 0xFD),
            rig.writtenFrames().last { it[4].toInt() == P.CMD_MEM && it.size == 8 },
        )

        // No transceive event/poll occurred: the cache still says USB.
        rig.reply(P.CMD_MODE_DATA, bytes(0, P.MODE_AM, 0, 1))
        rig.ack(P.CMD_MEM)
        rig.reply(P.CMD_MEM, bytes(0x03, 0x29))
        rig.reply(P.CMD_MODE_DATA, bytes(0, P.MODE_AM, 0, 1))
        assertTrue(c.setControl(CATCTL_FILTER_WIDTH, 6000))
        assertArrayEquals(
            bytes(0xFE, 0xFE, RIG, 0xE0, 0x1A, 0x03, 0x29, 0xFD),
            rig.writtenFrames().last { it[4].toInt() == P.CMD_MEM && it.size == 8 },
        )

        // FM has no width command: nothing is written.
        rig.reply(P.CMD_MODE_DATA, bytes(0, P.MODE_FM, 0, 1))
        val before = rig.writesOf(P.CMD_MEM)
        assertFalse(c.setControl(CATCTL_FILTER_WIDTH, 12_000))
        assertEquals(before, rig.writesOf(P.CMD_MEM))
        c.disconnect()
    }

    @Test
    fun `width readback under changed mode DATA or FIL never confirms the write`() {
        for (after in listOf(bytes(0, P.MODE_AM, 0, 1), bytes(0, P.MODE_USB, 3, 1), bytes(0, P.MODE_USB, 0, 2))) {
            val (c, rig) = connectedClient()
            try {
                rig.reply(P.CMD_MODE_DATA, bytes(0, P.MODE_USB, 0, 1))
                rig.ack(P.CMD_MEM)
                rig.reply(P.CMD_MEM, bytes(0x03, 0x28)) // 2400 USB, 5800 AM.
                rig.reply(P.CMD_MODE_DATA, after)
                assertFalse(c.setControl(CATCTL_FILTER_WIDTH, 2400))
            } finally { c.disconnect() }
        }
    }

    @Test
    fun `width write refuses a missing physical mode read without mutation`() {
        val (c, rig) = connectedClient()
        try {
            rig.nak(P.CMD_MODE_DATA)
            assertFalse(c.setControl(CATCTL_FILTER_WIDTH, 2400))
            assertEquals(0, rig.writesOf(P.CMD_MEM))
        } finally { c.disconnect() }
    }

    @Test
    fun `filter width refused off the known family`() {
        // An unlisted address gets full control but no 0x1A gamble.
        val rig = FakeRig(echo = true, address = 0x5E)
        rig.reply(P.CMD_READ_ID, byteArrayOf(P.SUB_ID.toByte(), 0x5E))
        rig.reply(P.CMD_READ_FREQ, P.toBcdLe(14_074_000, 5)!!)
        rig.reply(P.CMD_READ_MODE, byteArrayOf(P.MODE_USB.toByte(), 0x01))
        val (c, _) = makeClient(rig, 0x5E)
        assertTrue(connect(c))
        assertFalse(c.setControl(CATCTL_FILTER_WIDTH, 2400))
        assertEquals(0, rig.writesOf(P.CMD_MEM))
        c.disconnect()
    }

    @Test
    fun `fil still rides the mode write and unknown ids are refused`() {
        val (c, rig) = connectedClient()
        rig.reply(P.CMD_MODE_DATA, bytes(0, P.MODE_USB, 0, 1))
        rig.ack(P.CMD_MODE_DATA)
        rig.reply(
            P.CMD_MODE_DATA,
            bytes(P.SUB_MODE_DATA_SELECTED, P.MODE_USB, 0, 2),
        )
        assertTrue(c.setControl(CATCTL_FIL, 2))
        val modeWrite = P.writeModeData(RIG, P.MODE_USB, false, 2)!!
        assertTrue(rig.writtenFrames().any { it.contentEquals(modeWrite) })

        val total = rig.totalWrites()
        assertFalse(c.setControl(99, 1))
        assertFalse(c.setControl(0, 1))
        assertEquals(total, rig.totalWrites())
        c.disconnect()
    }

    @Test
    fun `fil requires the physical filter read back to match`() {
        val (c, rig) = connectedClient()
        rig.reply(P.CMD_MODE_DATA, bytes(0, P.MODE_USB, 0, 1))
        rig.ack(P.CMD_MODE_DATA)
        rig.reply(
            P.CMD_MODE_DATA,
            bytes(P.SUB_MODE_DATA_SELECTED, P.MODE_USB, 0, 3),
        )

        assertFalse(c.setControl(CATCTL_FIL, 2))
        assertEquals(P.MODE_USB, c.currentCatMode())
        assertTrue(
            rig.writtenFrames().any {
                it.contentEquals(P.writeModeData(RIG, P.MODE_USB, false, 2)!!)
            },
        )
        c.disconnect()
    }

    @Test
    fun `legacy mode reply without filter never confirms FIL`() {
        val address = 0x5E
        val rig = FakeRig(echo = true, address = address)
        rig.reply(P.CMD_READ_ID, bytes(P.SUB_ID, address))
        rig.reply(P.CMD_READ_FREQ, P.toBcdLe(14_074_000, 5)!!)
        rig.reply(P.CMD_READ_MODE, bytes(P.MODE_USB))
        val (c, _) = makeClient(rig, address)
        assertTrue(connect(c))

        rig.reply(P.CMD_READ_MODE, bytes(P.MODE_USB))
        rig.ack(P.CMD_WRITE_MODE)
        rig.reply(P.CMD_READ_MODE, bytes(P.MODE_USB))
        assertFalse(c.setControl(CATCTL_FIL, 2))
        assertEquals(P.MODE_USB, c.currentCatMode())
        assertTrue(
            rig.writtenFrames().any {
                it.contentEquals(P.writeMode(address, P.MODE_USB, 2)!!)
            },
        )
        c.disconnect()
    }

    @Test
    fun `ic7851 reads and confirms DATA state through command 26`() {
        val address = CivModels.ADDR_IC7851
        val rig = FakeRig(echo = true, address = address)
        rig.reply(P.CMD_READ_ID, bytes(P.SUB_ID, address))
        rig.reply(P.CMD_READ_FREQ, P.toBcdLe(14_074_000, 5)!!)
        rig.reply(
            P.CMD_MODE_DATA,
            bytes(P.SUB_MODE_DATA_SELECTED, P.MODE_USB, 1, 2),
        )
        val (c, _) = makeClient(rig, address)
        assertTrue(connect(c))
        assertEquals(P.MODE_USB or DriverProto.CAT_MODE_DATA_FLAG, c.currentCatMode())

        rig.ack(P.CMD_MODE_DATA)
        rig.reply(
            P.CMD_MODE_DATA,
            bytes(P.SUB_MODE_DATA_SELECTED, P.MODE_USB, 0, 2),
        )
        assertTrue(c.setCatMode(P.MODE_USB))
        assertTrue(
            rig.writtenFrames().any {
                it.contentEquals(P.writeModeData(address, P.MODE_USB, false, 2)!!)
            },
        )

        rig.ack(P.CMD_MODE_DATA)
        rig.reply(
            P.CMD_MODE_DATA,
            bytes(P.SUB_MODE_DATA_SELECTED, P.MODE_USB, 1, 2),
        )
        assertTrue(c.setCatMode(P.MODE_USB or DriverProto.CAT_MODE_DATA_FLAG))
        assertEquals(P.MODE_USB or DriverProto.CAT_MODE_DATA_FLAG, c.currentCatMode())
        c.disconnect()
    }

    @Test
    fun `mode commands publish equal FIL again after switching away and back`() {
        val rig = FakeRig(echo = true)
        scriptConnect(rig)
        val modes = listOf(P.MODE_USB, P.MODE_AM, P.MODE_USB)
        for (mode in modes) {
            rig.ack(P.CMD_MODE_DATA)
            rig.reply(P.CMD_MODE_DATA, bytes(0, mode, 0, 1))
        }
        val ordered = java.util.concurrent.CopyOnWriteArrayList<String>()
        lateinit var c: CivClient
        c = CivClient(rig, RIG, { _, _ -> }, { up, _ ->
            if (up) {
                // Run at the connected boundary, before background polling
                // starts, so only explicit mode commands can satisfy this.
                modes.forEach { assertTrue(c.setCatMode(it)) }
            }
        }, onControl = { id, value -> if (id == CATCTL_FIL) ordered += "FIL$value" })
        c.setStateListener { ordered += "MODE${c.currentCatMode()}" }
        try {
            assertTrue(connect(c))
            assertEquals(listOf("FIL1", "MODE${P.MODE_AM}", "FIL1", "MODE${P.MODE_USB}", "FIL1"), ordered)
        } finally { c.disconnect() }
    }

    @Test
    fun `data modes require exact selected vfo read back`() {
        val (c, rig) = connectedClient()
        for (base in intArrayOf(P.MODE_LSB, P.MODE_USB, P.MODE_AM, P.MODE_FM)) {
            val requested = base or DriverProto.CAT_MODE_DATA_FLAG
            rig.ack(P.CMD_MODE_DATA)
            rig.reply(
                P.CMD_MODE_DATA,
                bytes(P.SUB_MODE_DATA_SELECTED, base, 1, 1),
            )
            assertTrue(c.setCatMode(requested))
            assertEquals(requested, c.currentCatMode())
            val expected = P.writeModeData(RIG, base, true, 1)!!
            assertTrue(rig.writtenFrames().any { it.contentEquals(expected) })
        }

        rig.ack(P.CMD_MODE_DATA)
        rig.reply(
            P.CMD_MODE_DATA,
            bytes(P.SUB_MODE_DATA_SELECTED, P.MODE_USB, 0, 2),
        )
        assertFalse(c.setCatMode(P.MODE_USB or DriverProto.CAT_MODE_DATA_FLAG))
        assertEquals(P.MODE_USB, c.currentCatMode())

        val before = rig.totalWrites()
        assertFalse(c.setCatMode(0x200 or P.MODE_USB))
        assertEquals(before, rig.totalWrites())
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
        try {
            c.setFrequency(7_000_000)
        } catch (_: IllegalStateException) {
        }
        assertEquals(14_074_000L, c.frequencyHz())
    }
    @Test
    fun `control ACK alone does not prove the physical level`() {
        val (c, rig) = connectedClient()
        rig.ack(P.CMD_LEVEL)
        rig.reply(P.CMD_LEVEL, bytes(P.SUB_LEVEL_RF, 0x01, 0x99))
        assertFalse(c.setControl(CATCTL_RF_GAIN, 200))
        rig.ack(P.CMD_FUNC)
        rig.reply(P.CMD_FUNC, bytes(P.SUB_FUNC_NB, 0))
        assertFalse(c.setControl(CATCTL_NB, 1))
        c.disconnect()
    }

    @Test
    fun `FIL preserves physical D3 even if cache was not DATA`() {
        val address = CivModels.ADDR_IC7851
        val rig = FakeRig(echo = true, address = address)
        rig.reply(P.CMD_READ_ID, bytes(P.SUB_ID, address))
        rig.reply(P.CMD_READ_FREQ, P.toBcdLe(14_074_000, 5)!!)
        rig.reply(P.CMD_MODE_DATA, bytes(0, P.MODE_USB, 0, 1))
        val (c, _) = makeClient(rig, address)
        assertTrue(connect(c))
        rig.reply(P.CMD_MODE_DATA, bytes(0, P.MODE_USB, 3, 1))
        rig.ack(P.CMD_MODE_DATA)
        rig.reply(P.CMD_MODE_DATA, bytes(0, P.MODE_USB, 3, 2))
        assertTrue(c.setControl(CATCTL_FIL, 2))
        assertTrue(rig.writtenFrames().any { it.contentEquals(P.writeModeData(CivModels.ADDR_IC7851, P.MODE_USB, 3, 2)!!) })
        c.disconnect()
    }

    @Test
    fun `FIX span write is refused and an unconfirmed CENTER span is not published`() {
        val (c, rig) = connectedClient()
        val old = c.sampleRateHz()
        val before = rig.writesOf(P.CMD_SCOPE)
        rig.reply(P.CMD_SCOPE, bytes(P.SUB_SCOPE_MODE, 0, 1))
        assertThrows(IllegalStateException::class.java) { c.setSampleRate(50_000) }
        assertEquals(before + 1, rig.writesOf(P.CMD_SCOPE))
        rig.reply(P.CMD_SCOPE, bytes(P.SUB_SCOPE_MODE, 0, 0))
        rig.ack(P.CMD_SCOPE)
        rig.reply(P.CMD_SCOPE, bytes(P.SUB_SCOPE_SPAN, 0) + P.toBcdLe(50_000, 5)!!)
        assertThrows(IllegalStateException::class.java) { c.setSampleRate(50_000) }
        assertEquals(old, c.sampleRateHz())
        c.disconnect()
    }

    @Test
    fun `meter poll delivers documented S9 anchor and terminates on disconnect`() {
        val rig = FakeRig(echo = true)
        scriptConnect(rig)
        rig.reply(P.CMD_READ_METER, bytes(P.SUB_METER_S, 0x01, 0x20))
        rig.reply(P.CMD_READ_FREQ, P.toBcdLe(7_100_000, 5)!!)
        val seen = java.util.concurrent.CountDownLatch(1)
        val readings = java.util.concurrent.CopyOnWriteArrayList<com.isaklab.isdrproto.RadioTelemetry>()
        val c = CivClient(rig, RIG, { _, _ -> }, { _, _ -> }, onTelemetry = { readings += it; seen.countDown() })
        assertTrue(connect(c))
        assertTrue(seen.await(2, java.util.concurrent.TimeUnit.SECONDS))
        c.disconnect()
        assertEquals(-73.0, readings.single().smeterDbm, 0.0)
        assertTrue(readings.single().hasSmeter)
        val writes = rig.totalWrites()
        Thread.sleep(300)
        assertEquals(writes, rig.totalWrites())
    }

    @Test
    fun `7610 poll preserves S9 and normalized Po SWR through telemetry encoding`() {
        val addr = CivModels.ADDR_IC7610
        val rig = FakeRig(echo = true, address = addr)
        scriptConnect(rig, addr)
        rig.reply(P.CMD_READ_METER, bytes(P.SUB_METER_S, 0x01, 0x20))
        rig.reply(P.CMD_READ_METER, bytes(P.SUB_METER_POWER, 0x02, 0x12))
        rig.reply(P.CMD_READ_METER, bytes(P.SUB_METER_SWR, 0x01, 0x20))
        rig.reply(P.CMD_READ_METER, bytes(P.SUB_METER_POWER, 0x01, 0x43))
        rig.reply(P.CMD_READ_METER, bytes(P.SUB_METER_SWR, 0x01, 0x20))
        rig.reply(P.CMD_READ_METER, bytes(P.SUB_METER_POWER, 0x02, 0x12))
        rig.nak(P.CMD_READ_METER)
        repeat(3) {
            rig.reply(P.CMD_MODE_DATA, bytes(0, P.MODE_USB, 0, 1))
            rig.reply(P.CMD_READ_FREQ, P.toBcdLe(14_074_000, 5)!!)
            rig.reply(P.CMD_PTT, bytes(P.SUB_PTT, 1))
            rig.reply(P.CMD_LEVEL, bytes(listOf(P.SUB_LEVEL_RF, P.SUB_LEVEL_SQL, P.SUB_LEVEL_AF)[it], 0, 0))
        }
        val seen = java.util.concurrent.CountDownLatch(4)
        val readings = java.util.concurrent.CopyOnWriteArrayList<com.isaklab.isdrproto.RadioTelemetry>()
        val c = CivClient(rig, addr, { _, _ -> }, { _, _ -> }, onTelemetry = {
            // Exercise the exact flags/units carried by DriverSession.
            val output = java.io.ByteArrayOutputStream()
            Frames(java.io.DataInputStream(java.io.ByteArrayInputStream(byteArrayOf())), java.io.DataOutputStream(output))
                .writeTelemetry(it)
            val frame = Frames(java.io.DataInputStream(java.io.ByteArrayInputStream(output.toByteArray())),
                java.io.DataOutputStream(java.io.ByteArrayOutputStream())).read()!!
            assertEquals(DriverProto.EV_TELEMETRY, frame.op)
            readings += frame.payload.getTelemetry()
            seen.countDown()
        })
        assertTrue(connect(c))
        try {
            assertTrue(seen.await(3, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(-73.0, readings.single { it.hasSmeter }.smeterDbm, 0.0)
            val powers = readings.filter { it.hasFwdPower }
            assertEquals(listOf(1.0, 0.5, 1.0), powers.map { it.forwardPower })
            assertEquals(listOf(0.25, 0.125), powers.filter { it.hasRevPower }.map { it.reversePower })
            for (pair in powers.take(2)) {
                val rho = kotlin.math.sqrt(pair.reversePower / pair.forwardPower)
                assertEquals(3.0, (1 + rho) / (1 - rho), 0.0)
            }
            assertFalse("missing SWR must not reuse the prior pair", powers.last().hasRevPower)
        } finally { c.disconnect() }
    }

    @Test(timeout = 5_000)
    fun `an operator waiting during a TX meter pair cannot deadlock its second read`() {
        val rig = FakeRig(echo = true)
        scriptConnect(rig)
        rig.reply(P.CMD_READ_METER, bytes(P.SUB_METER_S, 0, 0))
        rig.reply(P.CMD_MODE_DATA, bytes(0, P.MODE_USB, 0, 1))
        rig.reply(P.CMD_READ_FREQ, P.toBcdLe(14_074_000, 5)!!)
        rig.reply(P.CMD_PTT, bytes(P.SUB_PTT, 1))
        rig.reply(P.CMD_LEVEL, bytes(P.SUB_LEVEL_RF, 0x01, 0x00))
        rig.reply(P.CMD_READ_METER, bytes(P.SUB_METER_POWER, 0x02, 0x13))
        rig.reply(P.CMD_READ_METER, bytes(P.SUB_METER_SWR, 0x01, 0x20))
        rig.ack(P.CMD_LEVEL)
        rig.reply(P.CMD_LEVEL, bytes(P.SUB_LEVEL_RF, 0x02, 0x00))
        val powerReadEntered = java.util.concurrent.CountDownLatch(1)
        val releasePowerRead = java.util.concurrent.CountDownLatch(1)
        val pairReceived = java.util.concurrent.CountDownLatch(1)
        rig.beforeWrite = {
            if (it.getOrNull(4)?.toInt() == P.CMD_READ_METER && it.getOrNull(5)?.toInt() == P.SUB_METER_POWER) {
                powerReadEntered.countDown()
                assertTrue(releasePowerRead.await(2, java.util.concurrent.TimeUnit.SECONDS))
            }
        }
        val c = CivClient(rig, RIG, { _, _ -> }, { _, _ -> }, onTelemetry = {
            if (it.hasFwdPower && it.hasRevPower) pairReceived.countDown()
        })
        assertTrue(connect(c))
        var operator: Thread? = null
        val applied = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            assertTrue(powerReadEntered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            operator = thread { applied.set(c.setControl(CATCTL_RF_GAIN, 200)) }
            val waiting = CivClient::class.java.getDeclaredField("operatorWaiting").apply { isAccessible = true }
                .get(c) as java.util.concurrent.atomic.AtomicInteger
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(1)
            while (waiting.get() == 0 && System.nanoTime() < deadline) Thread.sleep(1)
            assertTrue("operator did not reach the occupied bus", waiting.get() > 0)
            releasePowerRead.countDown()
            assertTrue(pairReceived.await(1, java.util.concurrent.TimeUnit.SECONDS))
            operator.join(1_000)
            assertFalse("operator remained blocked after the pair", operator.isAlive)
            assertTrue(applied.get())
        } finally {
            releasePowerRead.countDown()
            operator?.interrupt()
            c.disconnect()
            operator?.join(1_000)
        }
    }

    @Test
    fun `original 7300 excludes MK2 tones and known band controls are guarded`() {
        assertFalse(CivModels.rigCaps(CivModels.ADDR_IC7300).extendedCtcss)
        assertEquals(1, CivModels.preampMax(CivModels.ADDR_IC705, 145_000_000))
        assertEquals(2, CivModels.preampMax(CivModels.ADDR_IC705, 50_000_000))
        assertFalse(CivModels.attenuatorAllowed(CivModels.ADDR_IC705, 145_000_000, 20))
        assertFalse(CivModels.attenuatorAllowed(CivModels.ADDR_IC905, 2_400_000_000, 10))
        assertTrue(CivModels.attenuatorAllowed(CivModels.ADDR_IC905, 1_296_000_000, 10))
        assertEquals(3, CivModels.preampMax(CivModels.ADDR_IC9700, 145_000_000))
    }

    @Test
    fun `polling publishes physical panel filter and RF gain changes`() {
        val rig = FakeRig(echo = true)
        scriptConnect(rig)
        rig.reply(P.CMD_READ_METER, bytes(P.SUB_METER_S, 0x01, 0x20))
        rig.reply(P.CMD_MODE_DATA, bytes(0, P.MODE_AM, 0, 3))
        rig.reply(P.CMD_READ_FREQ, P.toBcdLe(7_100_000, 5)!!)
        rig.reply(P.CMD_PTT, bytes(P.SUB_PTT, 0))
        rig.reply(P.CMD_LEVEL, bytes(P.SUB_LEVEL_RF, 0x02, 0x00))
        rig.reply(P.CMD_READ_METER, bytes(P.SUB_METER_S, 0x01, 0x20))
        rig.reply(P.CMD_MODE_DATA, bytes(0, P.MODE_USB, 0, 3))
        val controls = java.util.concurrent.CopyOnWriteArrayList<Pair<Int, Int>>()
        val ordered = java.util.concurrent.CopyOnWriteArrayList<String>()
        val gainRead = java.util.concurrent.CountDownLatch(1)
        val filterReads = java.util.concurrent.CountDownLatch(2)
        val c = CivClient(rig, RIG, { _, _ -> }, { _, _ -> }, onControl = { id, value ->
            controls += id to value
            if (id == CATCTL_FIL) { ordered += "FIL$value"; filterReads.countDown() }
            if (id == CATCTL_RF_GAIN) gainRead.countDown()
        })
        c.setStateListener { ordered += "MODE${c.currentCatMode()}" }
        assertTrue(connect(c))
        assertTrue(gainRead.await(2, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(filterReads.await(2, java.util.concurrent.TimeUnit.SECONDS))
        c.disconnect()
        assertTrue(controls.contains(CATCTL_FIL to 3))
        assertTrue(controls.contains(CATCTL_RF_GAIN to 200))
        assertEquals(7_100_000L, c.frequencyHz())
        assertEquals(2, controls.count { it == (CATCTL_FIL to 3) })
        assertTrue(ordered.indexOf("MODE${P.MODE_AM}") < ordered.indexOf("FIL3"))
        assertTrue(ordered.indexOf("MODE${P.MODE_USB}") < ordered.lastIndexOf("FIL3"))
    }

    @Test
    fun `readback ignores delayed ACK and unrelated level selector`() {
        val (c, rig) = connectedClient()
        rig.ack(P.CMD_LEVEL)
        val delayedAck = bytes(0xFE, 0xFE, P.CONTROLLER_ADDR, RIG, 0xFB, 0xFD)
        val wrong = P.buildFrame(P.CONTROLLER_ADDR, RIG, bytes(P.CMD_LEVEL, P.SUB_LEVEL_SQL, 0x02, 0x00))!!
        val right = P.buildFrame(P.CONTROLLER_ADDR, RIG, bytes(P.CMD_LEVEL, P.SUB_LEVEL_RF, 0x02, 0x00))!!
        rig.on(P.CMD_LEVEL, delayedAck + wrong + right)
        assertTrue(c.setControl(CATCTL_RF_GAIN, 200))
        c.disconnect()
    }

    @Test
    fun `an ACK addressed to another controller never confirms our write`() {
        val (c, rig) = connectedClient()
        val wrong = bytes(0xFE, 0xFE, 0xE1, RIG, 0xFB, 0xFD)
        val refusal = bytes(0xFE, 0xFE, P.CONTROLLER_ADDR, RIG, 0xFA, 0xFD)
        rig.on(P.CMD_FUNC, wrong + refusal)
        assertFalse(c.setControl(CATCTL_NB, 1))
        assertEquals(1, rig.writesOf(P.CMD_FUNC))
        c.disconnect()
    }

}
