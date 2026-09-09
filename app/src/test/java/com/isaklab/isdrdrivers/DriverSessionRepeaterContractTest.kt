/*
 * isdr-drivers - GPL driver host for the iSDR app
 *
 * Copyright (C) 2026 Isak Ruas <isakruas@gmail.com>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 */
package com.isaklab.isdrdrivers

import android.content.ContextWrapper
import com.isaklab.isdrdrivers.core.CatRepeaterCapable
import com.isaklab.isdrdrivers.core.RadioClient
import com.isaklab.isdrdrivers.core.TransmitCapable
import com.isaklab.isdrproto.CatRepeater
import com.isaklab.isdrproto.CatRepeaterConfig
import com.isaklab.isdrproto.DriverProto
import com.isaklab.isdrproto.Frames
import com.isaklab.isdrproto.getUtf
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriverSessionRepeaterContractTest {

    private class RepeaterRadio(
        private val caps: Int,
        private val blockUntilCancel: Boolean = false,
        private val blockUntilReleased: Boolean = false,
    ) : RadioClient, TransmitCapable, CatRepeaterCapable {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val events = Collections.synchronizedList(ArrayList<String>())
        private val cancel = AtomicBoolean(false)
        private val repeaterCalls = AtomicInteger()

        override suspend fun connect(): Boolean = true
        override fun disconnect() = Unit
        override fun setFrequency(hz: Long) = Unit
        override fun setSampleRate(hz: Int) = Unit
        override var spectrumEnabled: Boolean = false
        override fun setTxFrequency(hz: Long): Boolean = true
        override fun submitTxIq(iq: FloatArray) = Unit
        override fun isTransmitting(): Boolean = false
        override fun catRepeaterCapabilities(): Int = caps

        override fun setCatRepeater(config: CatRepeaterConfig): String? {
            val call = repeaterCalls.incrementAndGet()
            events.add("repeater-enter")
            entered.countDown()
            if (blockUntilReleased && call == 1) release.await(1, TimeUnit.SECONDS)
            if (blockUntilCancel) {
                while (!cancel.get()) Thread.sleep(2)
                events.add("repeater-cancelled")
                return "cancelled at safe CAT frame boundary"
            }
            events.add("repeater-confirmed")
            return null
        }

        override fun requestCatRepeaterCancelForUnkey() {
            events.add("cancel-intent")
            cancel.set(true)
        }

        override fun setPtt(on: Boolean) {
            if (!on && blockUntilCancel) {
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                while (!events.contains("repeater-cancelled")) Thread.sleep(1)
                events.add("ptt-off")
            }
        }
    }

    private data class Harness(
        val session: DriverSession,
        val socket: Socket,
        val wire: Frames,
    )

    private fun harness(radio: RadioClient): Harness {
        val listener = ServerSocket(0)
        val client = Socket("127.0.0.1", listener.localPort).apply { soTimeout = 2_000 }
        val server = listener.accept()
        listener.close()
        val session = DriverSession(ContextWrapper(null), server, "test-token") {}
        DriverSession::class.java.getDeclaredField("authenticated").apply {
            isAccessible = true
            setBoolean(session, true)
        }
        DriverSession::class.java.getDeclaredField("radio").apply {
            isAccessible = true
            set(session, radio)
        }
        DriverSession::class.java.getDeclaredField("radioControlReady").apply {
            isAccessible = true
            setBoolean(session, true)
        }
        session.start()
        return Harness(
            session,
            client,
            Frames(
                DataInputStream(BufferedInputStream(client.getInputStream())),
                DataOutputStream(BufferedOutputStream(client.getOutputStream())),
            ),
        )
    }

    private fun config() = CatRepeaterConfig(
        CatRepeater.DUPLEX_PLUS, 600_000,
        CatRepeater.TONE_CTCSS, 885, CatRepeater.DCS_NORMAL,
        CatRepeater.TONE_CTCSS, 885, CatRepeater.DCS_NORMAL,
    )

    private fun assertTerminal(wire: Frames, opcode: Int, disposition: Int) {
        val frame = wire.read()!!
        assertEquals(DriverProto.EV_COMMAND_RESULT, frame.op)
        assertEquals(opcode, frame.payload.get().toInt() and 0xFF)
        assertEquals(disposition, frame.payload.get().toInt() and 0xFF)
        frame.payload.getUtf()
        assertEquals(0, frame.payload.remaining())
    }

    @Test
    fun `hello advertises the implemented repeater command`() {
        val h = harness(RepeaterRadio(0))
        try {
            h.wire.writeI32(DriverProto.CMD_HELLO, DriverProto.VERSION)
            val hello = h.wire.read()!!
            assertEquals(DriverProto.EV_HELLO, hello.op)
            val version = hello.payload.int
            val features = hello.payload.int
            assertEquals(DriverProto.VERSION, version)
            assertTrue(features and DriverProto.FEAT_CAT_REPEATER != 0)
            assertTrue(features and DriverProto.FEAT_CAT_EXACT_PROFILE != 0)
        } finally {
            h.session.close()
            h.socket.close()
        }
    }

    @Test
    fun `valid repeater waits for driver physical terminal`() {
        val radio = RepeaterRadio(CatRepeater.CAP_DUPLEX or CatRepeater.CAP_OFFSET)
        val h = harness(radio)
        try {
            h.wire.write(DriverProto.CMD_CAT_SET_REPEATER, ByteBuffer.wrap(config().encode()))
            assertTerminal(h.wire, DriverProto.CMD_CAT_SET_REPEATER, DriverProto.COMMAND_ACCEPTED)
            assertEquals(listOf("repeater-enter", "repeater-confirmed"), radio.events)
        } finally {
            h.session.close()
            h.socket.close()
        }
    }

    @Test
    fun `malformed repeater is rejected before the driver`() {
        val radio = RepeaterRadio(CatRepeater.CAP_DUPLEX)
        val h = harness(radio)
        try {
            val invalid = ByteArray(21).apply { this[0] = 3 }
            h.wire.write(DriverProto.CMD_CAT_SET_REPEATER, ByteBuffer.wrap(invalid))
            assertTerminal(h.wire, DriverProto.CMD_CAT_SET_REPEATER, DriverProto.COMMAND_MALFORMED)
            assertTrue(radio.events.isEmpty())
        } finally {
            h.session.close()
            h.socket.close()
        }
    }

    @Test
    fun `zero profile is explicitly unsupported`() {
        val radio = RepeaterRadio(0)
        val h = harness(radio)
        try {
            h.wire.write(DriverProto.CMD_CAT_SET_REPEATER, ByteBuffer.wrap(config().encode()))
            assertTerminal(h.wire, DriverProto.CMD_CAT_SET_REPEATER, DriverProto.COMMAND_UNSUPPORTED)
            assertTrue(radio.events.isEmpty())
        } finally {
            h.session.close()
            h.socket.close()
        }
    }

    @Test
    fun `ptt off preempts deferred repeater before unkey`() {
        val radio = RepeaterRadio(CatRepeater.CAP_DUPLEX, blockUntilCancel = true)
        val h = harness(radio)
        try {
            h.wire.write(DriverProto.CMD_CAT_SET_REPEATER, ByteBuffer.wrap(config().encode()))
            assertTrue(radio.entered.await(1, TimeUnit.SECONDS))
            h.wire.writeBool(DriverProto.CMD_SET_PTT, false)

            val terminals = ArrayList<com.isaklab.isdrproto.Frame>()
            while (terminals.count { it.op == DriverProto.EV_COMMAND_RESULT } < 2) {
                terminals.add(h.wire.read()!!)
            }
            val events = ArrayList(radio.events)
            assertTrue(events.indexOf("cancel-intent") < events.indexOf("repeater-cancelled"))
            assertTrue(events.indexOf("repeater-cancelled") < events.indexOf("ptt-off"))
            assertFalse(radio.isTransmitting())
        } finally {
            h.session.close()
            h.socket.close()
        }
    }

    @Test
    fun `duplicate repeater results remain FIFO by opcode`() {
        val radio = RepeaterRadio(
            CatRepeater.CAP_DUPLEX or CatRepeater.CAP_OFFSET,
            blockUntilReleased = true,
        )
        val h = harness(radio)
        try {
            h.wire.write(DriverProto.CMD_CAT_SET_REPEATER, ByteBuffer.wrap(config().encode()))
            assertTrue(radio.entered.await(1, TimeUnit.SECONDS))
            h.wire.write(DriverProto.CMD_CAT_SET_REPEATER, ByteBuffer.wrap(config().encode()))
            radio.release.countDown()

            assertTerminal(
                h.wire,
                DriverProto.CMD_CAT_SET_REPEATER,
                DriverProto.COMMAND_ACCEPTED,
            )
            assertTerminal(
                h.wire,
                DriverProto.CMD_CAT_SET_REPEATER,
                DriverProto.COMMAND_REJECTED,
            )
            assertEquals(1, radio.events.count { it == "repeater-enter" })
        } finally {
            h.session.close()
            h.socket.close()
        }
    }
}
