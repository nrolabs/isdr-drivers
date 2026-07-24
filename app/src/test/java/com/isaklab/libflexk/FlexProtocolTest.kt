package com.isaklab.libflexk

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Byte-accuracy vs the published FlexLib API v4.2.20 (flexradio.com). */
class FlexProtocolTest {

    /** Hand-build a VITA packet the way VitaPacketPreamble.cs lays it out. */
    private fun vita(
        pktType: Int, streamId: Long, oui: Int, classCode: Int,
        payload: ByteArray, tsi: Int = 0, tsf: Int = 0, count: Int = 5,
    ): ByteArray {
        val header = 4 + 4 + 8 + (if (tsi != 0) 4 else 0) + (if (tsf != 0) 8 else 0)
        val total = header + payload.size
        val bb = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
        var w0 = (pktType shl 28) or (1 shl 27) or (tsi shl 22) or (tsf shl 20) or
            ((count and 0xF) shl 16) or (total / 4)
        bb.putInt(w0)
        bb.putInt(streamId.toInt())
        bb.putInt(oui)
        bb.putShort(0x534C.toShort())                 // information class ("SL")
        bb.putShort(classCode.toShort())
        if (tsi != 0) bb.putInt(0x12345678)
        if (tsf != 0) bb.putLong(0x1122334455667788L)
        bb.put(payload)
        return bb.array()
    }

    @Test
    fun vitaPreambleParsesBackExactly() {
        val payload = ByteArray(16) { it.toByte() }
        val p = vita(1, 0x40000001L, FlexProtocol.FLEX_OUI, 0x02E4, payload, tsi = 1, tsf = 1)
        val v = FlexProtocol.parseVita(p, p.size)!!
        assertEquals(1, v.pktType)
        assertEquals(0x40000001L, v.streamId)
        assertEquals(FlexProtocol.FLEX_OUI, v.oui)
        assertEquals(0x02E4, v.classCode)
        assertEquals(16, v.payloadBytes)
        assertEquals(5, v.packetCount)
        // Payload window lands exactly on our bytes.
        assertEquals(0, p[v.payloadOffset].toInt())
        assertEquals(15, p[v.payloadOffset + 15].toInt())
    }

    @Test
    fun discoveryDecodesKeyValuePayload() {
        val text = "discovery_protocol_version=3.0.0.1 model=FLEX-6400 serial=1234-5678 " +
            "version=3.4.21 nickname=Estacao ip=192.168.1.71 port=4992 status=Available   "
        val p = vita(3, 0x800L, FlexProtocol.FLEX_OUI, FlexProtocol.CLASS_DISCOVERY, text.toByteArray())
        val r = FlexProtocol.parseDiscovery(p, p.size)!!
        assertEquals("192.168.1.71", r.ip)
        assertEquals(4992, r.commandPort)
        assertEquals("FLEX-6400", r.model)
        assertEquals("Estacao", r.nickname)
        assertTrue(r.available)
        // Wrong OUI is not a Flex discovery.
        val alien = vita(3, 0x800L, 0x0BAD, FlexProtocol.CLASS_DISCOVERY, text.toByteArray())
        assertNull(FlexProtocol.parseDiscovery(alien, alien.size))
    }

    @Test
    fun commandAndReplyRoundTrip() {
        assertEquals("C24|slice tune 0 14.074000", FlexProtocol.command(24, "slice tune 0 14.074000"))
        val r = FlexProtocol.parseLine("R24|0|OK") as FlexProtocol.Line.Reply
        assertEquals(24, r.seq); assertEquals(0L, r.code); assertEquals("OK", r.message)
        val err = FlexProtocol.parseLine("R7|50000015|incorrect param count") as FlexProtocol.Line.Reply
        assertEquals(0x50000015L, err.code)
        val st = FlexProtocol.parseLine("S28BE57B7|slice 0 RF_frequency=7.100000 mode=USB") as FlexProtocol.Line.Status
        assertEquals(0x28BE57B7L, st.handle)
        assertTrue(st.text.startsWith("slice 0 "))
        val h = FlexProtocol.parseLine("H41929B85") as FlexProtocol.Line.Handle
        assertEquals(0x41929B85L, h.handle)
        val v = FlexProtocol.parseLine("V1.4.0.0") as FlexProtocol.Line.Version
        assertEquals("1.4.0.0", v.version)
        assertNull(FlexProtocol.parseLine(""))
    }

    @Test
    fun ifDataWideDecodesFloat32BeWithReferenceScale() {
        // VitaIFDataPacket.cs: float32 BE multiplied by 1/2^15.
        val bb = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        bb.putFloat(16384.0f)                          // -> 0.5
        bb.putFloat(-32768.0f)                         // -> -1.0
        val out = FloatArray(2)
        val n = FlexProtocol.decodeIfDataWide(bb.array(), 0, 8, out)
        assertEquals(2, n)
        assertEquals(0.5f, out[0], 1e-6f)
        assertEquals(-1.0f, out[1], 1e-6f)
        assertTrue(FlexProtocol.isWideIq(FlexProtocol.CLASS_IF_WIDE_48))
        assertTrue(!FlexProtocol.isWideIq(FlexProtocol.CLASS_FFT))
    }
}
