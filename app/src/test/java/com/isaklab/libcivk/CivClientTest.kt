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
        rig.reply(
            P.CMD_MODE_DATA,
            bytes(P.SUB_MODE_DATA_SELECTED, P.MODE_USB, 0, 1),
        )
        rig.ack(P.CMD_SCOPE) // scope on
        rig.ack(P.CMD_SCOPE) // waveform output on
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
        rig.ack(P.CMD_SCOPE)
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

        rig.ack(P.CMD_SCOPE)
        c.setSampleRate(2_500)
        assertEquals(2_500, c.sampleRateHz())
        val before = rig.writesOf(P.CMD_SCOPE)
        assertThrows(IllegalArgumentException::class.java) { c.setSampleRate(300_000) }
        assertThrows(IllegalArgumentException::class.java) { c.setSampleRate(1_000_000) }
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
            rig.ack(P.CMD_SCOPE)
            val (client, _) = makeClient(rig, address)
            assertTrue(connect(client))
            return Pair(client, rig)
        }

        val (r8600, r8600Rig) = connectedAt(CivModels.ADDR_ICR8600)
        r8600Rig.ack(P.CMD_SCOPE)
        r8600.setSampleRate(2_500_000)
        assertEquals(2_500_000, r8600.sampleRateHz())
        assertThrows(IllegalArgumentException::class.java) { r8600.setSampleRate(5_000_000) }
        r8600.disconnect()

        val (ic905, ic905Rig) = connectedAt(CivModels.ADDR_IC905)
        ic905Rig.ack(P.CMD_SCOPE)
        ic905.setSampleRate(25_000_000)
        assertEquals(25_000_000, ic905.sampleRateHz())
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
            assertTrue("id $id", c.setControl(id, 200))
            assertArrayEquals(
                bytes(0xFE, 0xFE, RIG, 0xE0, 0x14, sub, 0x02, 0x00, 0xFD),
                rig.lastWritten(),
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
        rig.ack(P.CMD_SCOPE)
        val (c, _) = makeClient(rig, CivModels.ADDR_ICR8600)
        assertTrue(connect(c))
        val before = rig.writesOf(P.CMD_LEVEL)
        assertFalse(c.setControl(CATCTL_RF_POWER, 128))
        assertEquals(before, rig.writesOf(P.CMD_LEVEL))
        c.disconnect()
    }

    @Test
    fun `nr off is one func write and on adds the level`() {
        val (c, rig) = connectedClient()
        rig.ack(P.CMD_FUNC)
        assertTrue(c.setControl(CATCTL_NR, 0))
        assertArrayEquals(
            bytes(0xFE, 0xFE, RIG, 0xE0, 0x16, 0x40, 0x00, 0xFD),
            rig.lastWritten(),
        )
        assertEquals(0, rig.writesOf(P.CMD_LEVEL))

        // Level 15: the full 0..255 scale.
        rig.ack(P.CMD_FUNC)
        rig.ack(P.CMD_LEVEL)
        assertTrue(c.setControl(CATCTL_NR, 15))
        assertArrayEquals(
            bytes(0xFE, 0xFE, RIG, 0xE0, 0x14, 0x06, 0x02, 0x55, 0xFD),
            rig.lastWritten(),
        )

        // Level 8: 8*255/15 = 136.
        rig.ack(P.CMD_FUNC)
        rig.ack(P.CMD_LEVEL)
        assertTrue(c.setControl(CATCTL_NR, 8))
        assertArrayEquals(
            bytes(0xFE, 0xFE, RIG, 0xE0, 0x14, 0x06, 0x01, 0x36, 0xFD),
            rig.lastWritten(),
        )

        // A NAK on the on/off leg fails without touching the level.
        val levels = rig.writesOf(P.CMD_LEVEL)
        rig.nak(P.CMD_FUNC)
        assertFalse(c.setControl(CATCTL_NR, 5))
        assertEquals(levels, rig.writesOf(P.CMD_LEVEL))
        assertFalse(c.setControl(CATCTL_NR, 16))
        c.disconnect()
    }

    @Test
    fun `on off functions and agc and preamp`() {
        val (c, rig) = connectedClient()
        rig.ack(P.CMD_FUNC)
        assertTrue(c.setControl(CATCTL_NB, 1))
        assertArrayEquals(
            bytes(0xFE, 0xFE, RIG, 0xE0, 0x16, 0x22, 0x01, 0xFD),
            rig.lastWritten(),
        )
        rig.ack(P.CMD_FUNC)
        assertTrue(c.setControl(CATCTL_NOTCH_AUTO, 1))
        assertArrayEquals(
            bytes(0xFE, 0xFE, RIG, 0xE0, 0x16, 0x41, 0x01, 0xFD),
            rig.lastWritten(),
        )
        rig.ack(P.CMD_FUNC)
        assertTrue(c.setControl(CATCTL_AGC, 3))
        assertArrayEquals(
            bytes(0xFE, 0xFE, RIG, 0xE0, 0x16, 0x12, 0x03, 0xFD),
            rig.lastWritten(),
        )
        rig.ack(P.CMD_FUNC)
        assertTrue(c.setControl(CATCTL_PREAMP, 2))
        assertArrayEquals(
            bytes(0xFE, 0xFE, RIG, 0xE0, 0x16, 0x02, 0x02, 0xFD),
            rig.lastWritten(),
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
        assertTrue(c.setControl(CATCTL_ATT, 12))
        assertArrayEquals(
            bytes(0xFE, 0xFE, RIG, 0xE0, 0x11, 0x12, 0xFD),
            rig.lastWritten(),
        )
        rig.ack(P.CMD_ATTENUATOR)
        assertTrue(c.setControl(CATCTL_ATT, 0))
        assertArrayEquals(
            bytes(0xFE, 0xFE, RIG, 0xE0, 0x11, 0x00, 0xFD),
            rig.lastWritten(),
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
    fun `filter width follows the mode cache`() {
        val (c, rig) = connectedClient() // connect read back USB
        rig.ack(P.CMD_MEM)
        assertTrue(c.setControl(CATCTL_FILTER_WIDTH, 2400))
        assertArrayEquals(
            bytes(0xFE, 0xFE, RIG, 0xE0, 0x1A, 0x03, 0x28, 0xFD),
            rig.lastWritten(),
        )

        // The rig switches itself to AM: the AM table takes over.
        rig.pushUnsolicited(
            bytes(0xFE, 0xFE, 0x00, RIG, P.CMD_TRANSCEIVE_MODE, P.MODE_AM, 0x01, 0xFD),
        )
        var deadline = System.currentTimeMillis() + 1000
        while (c.mode() != P.MODE_AM && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
        rig.ack(P.CMD_MEM)
        assertTrue(c.setControl(CATCTL_FILTER_WIDTH, 6000))
        assertArrayEquals(
            bytes(0xFE, 0xFE, RIG, 0xE0, 0x1A, 0x03, 0x29, 0xFD),
            rig.lastWritten(),
        )

        // FM has no width command: nothing is written.
        rig.pushUnsolicited(
            bytes(0xFE, 0xFE, 0x00, RIG, P.CMD_TRANSCEIVE_MODE, P.MODE_FM, 0x01, 0xFD),
        )
        deadline = System.currentTimeMillis() + 1000
        while (c.mode() != P.MODE_FM && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
        val before = rig.writesOf(P.CMD_MEM)
        assertFalse(c.setControl(CATCTL_FILTER_WIDTH, 12_000))
        assertEquals(before, rig.writesOf(P.CMD_MEM))
        c.disconnect()
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
}
