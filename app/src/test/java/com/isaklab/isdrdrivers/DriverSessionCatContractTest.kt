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
import com.isaklab.isdrdrivers.core.CatControlCapable
import com.isaklab.isdrdrivers.core.RadioClient
import com.isaklab.isdrdrivers.core.TransmitCapable
import com.isaklab.isdrproto.DriverProto
import com.isaklab.isdrproto.Frames
import com.isaklab.isdrproto.getBool
import com.isaklab.isdrproto.getUtf
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.net.ServerSocket
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DriverSessionCatContractTest {

    private class RejectingTxRadio : RadioClient, TransmitCapable, CatControlCapable {
        var rxHz = 14_100_000L
        var txRequests = 0
        var pttRequests = 0

        override suspend fun connect(): Boolean = true
        override fun disconnect() = Unit
        override fun setFrequency(hz: Long) { rxHz = hz }
        override fun frequencyHz(): Long = rxHz
        override fun setSampleRate(hz: Int) = Unit
        override var spectrumEnabled: Boolean = false

        override fun setTxFrequency(hz: Long): Boolean {
            txRequests++
            return false
        }

        override fun setPtt(on: Boolean) { pttRequests++ }
        override fun submitTxIq(iq: FloatArray) = Unit
        override fun isTransmitting(): Boolean = false

        override fun setCatMode(mode: Int): Boolean = false
        override fun currentCatMode(): Int = 1
        override fun setCatControl(id: Int, value: Int): Boolean = false
    }

    @Test
    fun `rejected tx frequency returns negative ack and leaves rx unchanged`() {
        val listener = ServerSocket(0)
        val client = Socket("127.0.0.1", listener.localPort).apply { soTimeout = 2_000 }
        val server = listener.accept()
        listener.close()
        val radio = RejectingTxRadio()
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

        try {
            val wire = Frames(
                DataInputStream(BufferedInputStream(client.getInputStream())),
                DataOutputStream(BufferedOutputStream(client.getOutputStream())),
            )
            wire.writeI64(DriverProto.CMD_SET_TX_FREQUENCY, 14_250_000)

            val result = wire.read()!!
            assertEquals(DriverProto.EV_COMMAND_RESULT, result.op)
            assertEquals(
                DriverProto.CMD_SET_TX_FREQUENCY,
                result.payload.get().toInt() and 0xFF,
            )
            assertEquals(
                DriverProto.COMMAND_REJECTED,
                result.payload.get().toInt() and 0xFF,
            )
            assertTrue(result.payload.getUtf().contains("without changing RX"))
            assertEquals(0, result.payload.remaining())
            assertEquals(1, radio.txRequests)
            assertEquals(14_100_000L, radio.rxHz)

            wire.writeBool(DriverProto.CMD_SET_PTT, true)
            val pttResult = wire.read()!!
            assertEquals(DriverProto.EV_COMMAND_RESULT, pttResult.op)
            assertEquals(DriverProto.CMD_SET_PTT, pttResult.payload.get().toInt() and 0xFF)
            assertEquals(
                DriverProto.COMMAND_REJECTED,
                pttResult.payload.get().toInt() and 0xFF,
            )
            assertTrue(pttResult.payload.getUtf().contains("unconfirmed TX frequency"))
            assertEquals(0, radio.pttRequests)
        } finally {
            session.close()
            client.close()
        }
    }

    @Test
    fun `rejected cat mode and control emit semantic result then negative ack`() {
        val listener = ServerSocket(0)
        val client = Socket("127.0.0.1", listener.localPort).apply { soTimeout = 2_000 }
        val server = listener.accept()
        listener.close()
        val radio = RejectingTxRadio()
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

        try {
            val wire = Frames(
                DataInputStream(BufferedInputStream(client.getInputStream())),
                DataOutputStream(BufferedOutputStream(client.getOutputStream())),
            )
            wire.writeI32(DriverProto.CMD_CAT_SET_MODE, 6)

            val mode = wire.read()!!
            assertEquals(DriverProto.EV_CAT_MODE_RESULT, mode.op)
            assertEquals(6, mode.payload.int)
            assertEquals(1, mode.payload.int)
            assertEquals(false, mode.payload.getBool())
            assertCommandRejected(wire, DriverProto.CMD_CAT_SET_MODE)

            val controlRequest = ByteBuffer.allocate(8).putInt(12).putInt(3_200).apply { flip() }
            wire.write(DriverProto.CMD_CAT_SET_CONTROL, controlRequest)
            val control = wire.read()!!
            assertEquals(DriverProto.EV_CAT_CONTROL_RESULT, control.op)
            assertEquals(12, control.payload.int)
            assertEquals(3_200, control.payload.int)
            assertEquals(false, control.payload.getBool())
            assertEquals(false, control.payload.getBool())
            assertCommandRejected(wire, DriverProto.CMD_CAT_SET_CONTROL)
        } finally {
            session.close()
            client.close()
        }
    }

    private fun assertCommandRejected(wire: Frames, opcode: Int) {
        val result = wire.read()!!
        assertEquals(DriverProto.EV_COMMAND_RESULT, result.op)
        assertEquals(opcode, result.payload.get().toInt() and 0xFF)
        assertEquals(DriverProto.COMMAND_REJECTED, result.payload.get().toInt() and 0xFF)
        result.payload.getUtf()
        assertEquals(0, result.payload.remaining())
    }
}
