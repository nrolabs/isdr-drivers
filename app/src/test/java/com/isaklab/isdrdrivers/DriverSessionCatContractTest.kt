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
import com.isaklab.isdrdrivers.core.TxDriveCapable
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
        var catControlApplied = false
        val catControlRequests = mutableListOf<Pair<Int, Int>>()

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
        override fun setCatControl(id: Int, value: Int): Boolean {
            catControlRequests += id to value
            return catControlApplied
        }
    }

    private class IqTxDriveRadio : RadioClient, TransmitCapable, TxDriveCapable {
        var drive = -1

        override suspend fun connect(): Boolean = true
        override fun disconnect() = Unit
        override fun setFrequency(hz: Long) = Unit
        override fun frequencyHz(): Long = 14_100_000L
        override fun setSampleRate(hz: Int) = Unit
        override var spectrumEnabled: Boolean = false
        override fun setTxFrequency(hz: Long): Boolean = true
        override fun setPtt(on: Boolean) = Unit
        override fun submitTxIq(iq: FloatArray) = Unit
        override fun isTransmitting(): Boolean = false
        override fun setTxDrive(level: Int) { drive = level }
        override fun setPaEnabled(on: Boolean) = Unit
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
            assertTrue(result.payload.getUtf().contains("does not match RX frequency"))
            assertEquals(0, result.payload.remaining())
            assertEquals(0, radio.txRequests)
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
    fun `cat simplex tx frequency accepts current rx as a no-op`() {
        val listener = ServerSocket(0)
        val client = Socket("127.0.0.1", listener.localPort).apply { soTimeout = 2_000 }
        val server = listener.accept()
        listener.close()
        val radio = RejectingTxRadio()
        val session = authenticatedSession(server, radio)
        session.start()

        try {
            val wire = wire(client)
            wire.writeI64(DriverProto.CMD_SET_TX_FREQUENCY, radio.rxHz)

            assertCommandAccepted(wire, DriverProto.CMD_SET_TX_FREQUENCY)
            assertEquals(0, radio.txRequests)
            assertEquals(14_100_000L, radio.rxHz)
        } finally {
            session.close()
            client.close()
        }
    }

    @Test
    fun `cat tx frequency refuses unknown rx without touching the rig`() {
        val listener = ServerSocket(0)
        val client = Socket("127.0.0.1", listener.localPort).apply { soTimeout = 2_000 }
        val server = listener.accept()
        listener.close()
        val radio = RejectingTxRadio().apply { rxHz = 0 }
        val session = authenticatedSession(server, radio)
        session.start()

        try {
            val wire = wire(client)
            wire.writeI64(DriverProto.CMD_SET_TX_FREQUENCY, 14_100_000L)

            val result = assertCommandRejected(wire, DriverProto.CMD_SET_TX_FREQUENCY)
            assertTrue(result.contains("RX frequency is unknown"))
            assertEquals(0, radio.txRequests)
        } finally {
            session.close()
            client.close()
        }
    }

    @Test
    fun `cat tx drive emits semantic result before terminal ack`() {
        val listener = ServerSocket(0)
        val client = Socket("127.0.0.1", listener.localPort).apply { soTimeout = 2_000 }
        val server = listener.accept()
        listener.close()
        val radio = RejectingTxRadio().apply { catControlApplied = true }
        val session = authenticatedSession(server, radio)
        session.start()

        try {
            val wire = wire(client)
            wire.writeI32(DriverProto.CMD_SET_TX_DRIVE, 173)

            assertCatPowerResult(wire, 173, true)
            assertCommandAccepted(wire, DriverProto.CMD_SET_TX_DRIVE)
            assertEquals(listOf(DriverProto.CATCTL_RF_POWER to 173), radio.catControlRequests)

            radio.catControlApplied = false
            wire.writeI32(DriverProto.CMD_SET_TX_DRIVE, 91)
            assertCatPowerResult(wire, 91, false)
            val detail = assertCommandRejected(wire, DriverProto.CMD_SET_TX_DRIVE)
            assertTrue(detail.contains("RF power was not confirmed"))
            assertEquals(
                listOf(
                    DriverProto.CATCTL_RF_POWER to 173,
                    DriverProto.CATCTL_RF_POWER to 91,
                ),
                radio.catControlRequests,
            )
        } finally {
            session.close()
            client.close()
        }
    }

    @Test
    fun `iq tx drive keeps the generic drive path`() {
        val listener = ServerSocket(0)
        val client = Socket("127.0.0.1", listener.localPort).apply { soTimeout = 2_000 }
        val server = listener.accept()
        listener.close()
        val radio = IqTxDriveRadio()
        val session = authenticatedSession(server, radio)
        session.start()

        try {
            val wire = wire(client)
            wire.writeI32(DriverProto.CMD_SET_TX_DRIVE, 207)

            assertCommandAccepted(wire, DriverProto.CMD_SET_TX_DRIVE)
            assertEquals(207, radio.drive)
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

    private fun authenticatedSession(server: Socket, radio: RadioClient): DriverSession {
        return DriverSession(ContextWrapper(null), server, "test-token") {}.also { session ->
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
        }
    }

    private fun wire(client: Socket): Frames = Frames(
        DataInputStream(BufferedInputStream(client.getInputStream())),
        DataOutputStream(BufferedOutputStream(client.getOutputStream())),
    )

    private fun assertCatPowerResult(wire: Frames, requested: Int, applied: Boolean) {
        val result = wire.read()!!
        assertEquals(DriverProto.EV_CAT_CONTROL_RESULT, result.op)
        assertEquals(DriverProto.CATCTL_RF_POWER, result.payload.int)
        assertEquals(requested, result.payload.int)
        assertEquals(applied, result.payload.getBool())
        assertEquals(false, result.payload.getBool())
        assertEquals(0, result.payload.remaining())
    }

    private fun assertCommandAccepted(wire: Frames, opcode: Int) {
        val result = wire.read()!!
        assertEquals(DriverProto.EV_COMMAND_RESULT, result.op)
        assertEquals(opcode, result.payload.get().toInt() and 0xFF)
        assertEquals(DriverProto.COMMAND_ACCEPTED, result.payload.get().toInt() and 0xFF)
        result.payload.getUtf()
        assertEquals(0, result.payload.remaining())
    }

    private fun assertCommandRejected(wire: Frames, opcode: Int): String {
        val result = wire.read()!!
        assertEquals(DriverProto.EV_COMMAND_RESULT, result.op)
        assertEquals(opcode, result.payload.get().toInt() and 0xFF)
        assertEquals(DriverProto.COMMAND_REJECTED, result.payload.get().toInt() and 0xFF)
        val detail = result.payload.getUtf()
        assertEquals(0, result.payload.remaining())
        return detail
    }
}
