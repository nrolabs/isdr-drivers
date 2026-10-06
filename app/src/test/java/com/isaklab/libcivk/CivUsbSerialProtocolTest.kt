package com.isaklab.libcivk

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class CivUsbSerialProtocolTest {
    private data class Request(val type: Int, val request: Int, val value: Int, val index: Int, val bytes: ByteArray?)
    private fun configured(failRequest: Int? = null, wrongBaud: Boolean = false): List<Request> {
        val requests = mutableListOf<Request>()
        val transfer = { type: Int, request: Int, value: Int, index: Int, data: ByteArray? ->
            requests += Request(type, request, value, index, data?.copyOf())
            if (type == 0xC1 && data != null) when (request) {
                0x1D -> ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putInt(if (wrongBaud) 9600 else 115200)
                0x04 -> ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putShort(0x0800)
            }
            if (request == failRequest) -1 else data?.size ?: 0
        }
        if (failRequest != null || wrongBaud) assertThrows(IOException::class.java) {
            CivUsbSerialProtocol.configureCp210x(115200, 1, transfer)
        } else CivUsbSerialProtocol.configureCp210x(115200, 1, transfer)
        return requests
    }

    @Test fun `cp210x configures exact baud and 8N1 without asserting SEND lines`() {
        val r = configured()
        assertTrue(r.all { it.index == 1 })
        assertEquals(listOf(0x00, 0x13, 0x07, 0x1E, 0x03, 0x1D, 0x04, 0x08), r.map { it.request })
        assertArrayEquals(ByteArray(16), r[1].bytes)
        assertEquals(0x0300, r[2].value)
        assertArrayEquals(byteArrayOf(0x00, 0xC2.toByte(), 0x01, 0x00), r[3].bytes)
        assertEquals(0x0800, r[4].value)
        assertTrue(r.take(5).all { it.type == 0x41 })
        assertTrue(r.drop(5).all { it.type == 0xC1 })
    }

    @Test fun `failed or unconfirmed CP210x setup disables UART`() {
        for (request in listOf(0x13, 0x07, 0x1E, 0x03, 0x1D, 0x04, 0x08)) {
            val last = configured(failRequest = request).last()
            assertEquals(0x00, last.request)
            assertEquals(0, last.value)
        }
        assertEquals(0, configured(wrongBaud = true).last().value)
    }

    @Test fun `CDC readback is exact and modem control remains inactive`() {
        var coding = ByteArray(0)
        var inactive = false
        CivUsbSerialProtocol.configureCdc(115200, 3) { type, request, value, index, data ->
            assertEquals(3, index)
            when (request) {
                0x20 -> coding = data!!.copyOf()
                0x22 -> { assertEquals(0x21, type); inactive = value == 0 }
                0x21 -> coding.copyInto(data!!)
            }
            data?.size ?: 0
        }
        assertTrue(inactive)
        assertArrayEquals(byteArrayOf(0, 0xC2.toByte(), 1, 0, 0, 0, 8), coding)
        assertThrows(IOException::class.java) {
            CivUsbSerialProtocol.configureCdc(115200, 0) { _, _, _, _, _ -> -1 }
        }
    }

    @Test fun `vendor protocol is never inferred from arbitrary bulk interfaces`() {
        assertTrue(CivUsbSerialProtocol.isCp210x(0x10C4, 0xEA60))
        assertTrue(CivUsbSerialProtocol.isCp210x(0x10C4, 0xEA70))
        assertFalse(CivUsbSerialProtocol.isCp210x(0x0C26, 0x0012))
        assertFalse(CivUsbSerialProtocol.isCp210x(0x10C4, 0x0001))
    }
    @Test fun `UART selection requires correlated CI-V reply not echo or other bus traffic`() {
        val address = CivModels.ADDR_IC7300
        val reply = CivProtocol.buildFrame(CivProtocol.CONTROLLER_ADDR, address,
            byteArrayOf(CivProtocol.CMD_READ_ID.toByte(), 0, address.toByte()))!!
        var clock = 0L
        val pending = java.util.ArrayDeque<ByteArray>()
        val writes = mutableListOf<ByteArray>()
        val proof = CivUsbSerialProtocol.probeCiv(intArrayOf(0x98, address), write = {
            writes += it
            pending.add(it) // Adapter echo is not a radio.
            if (it[2].toInt() and 0xFF == address) {
                pending.add(reply.copyOfRange(0, 4))
                pending.add(reply.copyOfRange(4, reply.size))
            }
        }, read = { out ->
            val bytes = pending.pollFirst()
            if (bytes == null) 0 else { bytes.copyInto(out); bytes.size }
        }, nowNanos = { clock += 20_000_000; clock })
        assertTrue(proof)
        assertEquals(2, writes.size)
        clock = 0
        assertFalse(CivUsbSerialProtocol.probeCiv(intArrayOf(address), {}, { 0 }, { clock += 50_000_000; clock }))
    }

}
