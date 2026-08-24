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
import com.isaklab.isdrproto.DriverProto
import com.isaklab.isdrproto.Frames
import com.isaklab.isdrproto.getUtf
import com.isaklab.libhl2sdrk.Hl2Client
import com.isaklab.libhl2sdrk.Hl2Protocol
import com.isaklab.librtlsdrk.RtlTunerInfo
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Test

/** Strict V2 payload grammar: malformed prefixes never reach an adapter. */
class DriverSessionPayloadContractTest {

    @Test
    fun `RTL metadata bytes match the cross-platform golden payload`() {
        val encoded = encodeRtlInfo(
            RtlTunerInfo(5, "R820T", true, listOf(0, 9, 14)),
        )
        val actual = ByteArray(encoded.remaining()).also { encoded.get(it) }
        val expected = byteArrayOf(
            0, 0, 0, 5,
            0, 5, 'R'.code.toByte(), '8'.code.toByte(), '2'.code.toByte(),
            '0'.code.toByte(), 'T'.code.toByte(),
            1,
            0, 0, 0, 3,
            0, 0, 0, 0,
            0, 0, 0, 9,
            0, 0, 0, 14,
        )
        assertArrayEquals(expected, actual)
    }

    @Test
    fun `HackRF info preserves query failures apart from physical placeholders`() {
        val allFailures = hackRfInfoQueryFailedMask(
            firmwareQueryFailed = true,
            boardIdQueryFailed = true,
            serialQueryFailed = true,
            boardRevisionQueryFailed = true,
            platformQueryFailed = true,
            supportsClkinQuery = true,
            clkinResult = null,
            supportsOperaCakeQuery = true,
            boardsResult = null,
            supportsCpldQuery = true,
            cpldResult = null,
            modeResult = null,
        )
        assertEquals(
            DriverProto.HRF_INFO_QUERY_FAILED_CLKIN or
                DriverProto.HRF_INFO_QUERY_FAILED_OPERACAKE_BOARDS or
                DriverProto.HRF_INFO_QUERY_FAILED_CPLD_CHECKSUM or
                DriverProto.HRF_INFO_QUERY_FAILED_FIRMWARE or
                DriverProto.HRF_INFO_QUERY_FAILED_BOARD_ID or
                DriverProto.HRF_INFO_QUERY_FAILED_SERIAL or
                DriverProto.HRF_INFO_QUERY_FAILED_BOARD_REVISION or
                DriverProto.HRF_INFO_QUERY_FAILED_PLATFORM,
            allFailures,
        )

        val modeFailureOnly = hackRfInfoQueryFailedMask(
            firmwareQueryFailed = false,
            boardIdQueryFailed = false,
            serialQueryFailed = false,
            boardRevisionQueryFailed = false,
            platformQueryFailed = false,
            supportsClkinQuery = false,
            clkinResult = null,
            supportsOperaCakeQuery = true,
            boardsResult = intArrayOf(0),
            supportsCpldQuery = false,
            cpldResult = null,
            modeResult = null,
        )
        assertEquals(DriverProto.HRF_INFO_QUERY_FAILED_OPERACAKE_MODE, modeFailureOnly)

        val unsupportedOrAuthoritativelyEmpty = hackRfInfoQueryFailedMask(
            firmwareQueryFailed = false,
            boardIdQueryFailed = false,
            serialQueryFailed = false,
            boardRevisionQueryFailed = false,
            platformQueryFailed = false,
            supportsClkinQuery = false,
            clkinResult = null,
            supportsOperaCakeQuery = true,
            boardsResult = IntArray(0),
            supportsCpldQuery = false,
            cpldResult = null,
            modeResult = null,
        )
        assertEquals(0, unsupportedOrAuthoritativelyEmpty)
    }

    @Test
    fun `single-word filter payload is rejected before state mutation`() {
        val listener = ServerSocket(0)
        val client = Socket("127.0.0.1", listener.localPort).apply { soTimeout = 2_000 }
        val server = listener.accept()
        listener.close()
        val radio = Hl2Client(
            host = "127.0.0.1",
            onDataReceived = { _, _ -> },
            onConnectionStatusChanged = { _, _ -> },
        )
        val session = DriverSession(ContextWrapper(null), server, "test-token") {}
        setField(session, "authenticated", true)
        setField(session, "radio", radio)
        setField(session, "hl2", radio)
        setField(session, "radioControlReady", true)
        session.start()

        try {
            val wire = Frames(
                DataInputStream(BufferedInputStream(client.getInputStream())),
                DataOutputStream(BufferedOutputStream(client.getOutputStream())),
            )
            val shortPayload = ByteBuffer.allocate(4).putInt(0x23).apply { flip() }
            wire.write(DriverProto.CMD_HL2_SET_FILTER_OUTPUTS, shortPayload)

            val result = wire.read()!!
            assertEquals(DriverProto.EV_COMMAND_RESULT, result.op)
            assertEquals(
                DriverProto.CMD_HL2_SET_FILTER_OUTPUTS,
                result.payload.get().toInt() and 0xFF,
            )
            assertEquals(
                DriverProto.COMMAND_MALFORMED,
                result.payload.get().toInt() and 0xFF,
            )
            assertEquals("payload length 4, expected 8", result.payload.getUtf())
            assertEquals(0, result.payload.remaining())

            val state = Hl2Client::class.java.getDeclaredField("state")
                .apply { isAccessible = true }
                .get(radio) as Hl2Protocol.ControlState
            assertEquals(0, state.ocOutputs)
            assertEquals(-1, state.ocOutputsTx)
        } finally {
            session.close()
            client.close()
            radio.disconnect()
        }
    }

    private fun setField(target: Any, name: String, value: Any) {
        target.javaClass.getDeclaredField(name).apply {
            isAccessible = true
            when (value) {
                is Boolean -> setBoolean(target, value)
                else -> set(target, value)
            }
        }
    }
}
