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

import com.isaklab.isdrproto.DriverProto
import com.isaklab.libcivk.CivProtocol
import com.isaklab.libcivk.CivTransport
import java.io.IOException
import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CatExactProfileTest {
    private class ScriptedTransport(vararg chunks: ByteArray) : CivTransport {
        private val reads = ArrayDeque(chunks.toList())
        val writes = mutableListOf<ByteArray>()

        override fun writeAll(bytes: ByteArray) {
            writes += bytes.copyOf()
        }

        override fun readSome(buf: ByteArray): Int {
            val next = reads.pollFirst() ?: return 0
            check(next.size <= buf.size)
            next.copyInto(buf)
            return next.size
        }
    }

    @Test
    fun `exact profiles map to one physical identity and generic claims none`() {
        assertNull(expectedCatPhysicalIdentity(DriverProto.CAT_PROFILE_GENERIC))
        assertEquals(
            CatPhysicalIdentity.Civ(0x94),
            expectedCatPhysicalIdentity(DriverProto.CAT_PROFILE_IC7300),
        )
        assertEquals(
            CatPhysicalIdentity.Civ(0xA4),
            expectedCatPhysicalIdentity(DriverProto.CAT_PROFILE_IC705),
        )
        assertEquals(
            CatPhysicalIdentity.Kenwood(24, "TS-890S"),
            expectedCatPhysicalIdentity(DriverProto.CAT_PROFILE_TS890),
        )
        assertEquals(
            CatPhysicalIdentity.Kenwood(23, "TS-990S"),
            expectedCatPhysicalIdentity(DriverProto.CAT_PROFILE_TS990),
        )

        assertNull(catPhysicalIdentityFailure(DriverProto.CAT_PROFILE_GENERIC, null))
        assertNull(
            catPhysicalIdentityFailure(
                DriverProto.CAT_PROFILE_GENERIC,
                CatPhysicalIdentity.Civ(0xA4),
            ),
        )
        assertNotNull(catPhysicalIdentityFailure(DriverProto.CAT_PROFILE_IC7300, null))
        assertNotNull(
            catPhysicalIdentityFailure(
                DriverProto.CAT_PROFILE_IC7300,
                CatPhysicalIdentity.Civ(0xA4),
            ),
        )
    }

    @Test
    fun `host CAT flag gate accepts generic and exact but rejects ambiguous words`() {
        assertNull(catOpenFlagsFailure(0))
        assertNull(
            catOpenFlagsFailure(
                DriverProto.catOpenFlags(
                    DriverProto.CAT_DIALECT_KENWOOD,
                    0,
                    DriverProto.CAT_PROFILE_GENERIC,
                ),
            ),
        )
        assertNull(
            catOpenFlagsFailure(
                DriverProto.catOpenFlags(
                    DriverProto.CAT_DIALECT_CIV,
                    0x94,
                    DriverProto.CAT_PROFILE_IC7300,
                ),
            ),
        )
        assertNotNull(catOpenFlagsFailure(0x10A4))
        assertNotNull(catOpenFlagsFailure(0xA000))
        assertNotNull(catOpenFlagsFailure(0x1_0000))
    }

    @Test
    fun `CI-V identity observer accepts fragmented matching physical ID`() {
        val raw = ScriptedTransport(
            bytes(0xFE, 0xFE, 0xE0),
            bytes(0x94, CivProtocol.CMD_READ_ID, CivProtocol.SUB_ID, 0x94, 0xFD),
        )
        val transport = ExactCivIdentityTransport(raw, DriverProto.CAT_PROFILE_IC7300)
        transport.writeAll(CivProtocol.readTransceiverId(0x94))

        val buf = ByteArray(64)
        assertEquals(3, transport.readSome(buf))
        assertEquals(5, transport.readSome(buf))
        assertEquals(CatPhysicalIdentity.Civ(0x94), transport.physicalIdentity)
        assertNull(transport.identityFailure)
        assertEquals(1, raw.writes.size)
    }

    @Test
    fun `CI-V identity observer aborts mismatched physical ID before delivery`() {
        val raw = ScriptedTransport(
            bytes(
                0xFE, 0xFE, 0xE0, 0x94,
                CivProtocol.CMD_READ_ID, CivProtocol.SUB_ID, 0xA4, 0xFD,
            ),
        )
        val transport = ExactCivIdentityTransport(raw, DriverProto.CAT_PROFILE_IC7300)
        transport.writeAll(CivProtocol.readTransceiverId(0x94))

        val error = assertThrows(IOException::class.java) {
            transport.readSome(ByteArray(64))
        }
        assertTrue(error.message!!.contains("expected CI-V ID 0x94"))
        assertTrue(error.message!!.contains("reported CI-V ID 0xA4"))
        assertEquals(CatPhysicalIdentity.Civ(0xA4), transport.physicalIdentity)
        assertEquals(error.message, transport.identityFailure)
    }

    @Test
    fun `Kenwood identity observer accepts TS-890 and aborts TS-990 mismatch`() {
        val matchingRaw = ScriptedTransport("ID0".ascii(), "24;".ascii())
        val matching = ExactKenwoodIdentityTransport(
            matchingRaw,
            DriverProto.CAT_PROFILE_TS890,
        )
        matching.writeAll("ID;".ascii())
        assertEquals(3, matching.readSome(ByteArray(64)))
        assertEquals(3, matching.readSome(ByteArray(64)))
        assertEquals(CatPhysicalIdentity.Kenwood(24, "TS-890S"), matching.physicalIdentity)
        assertNull(matching.identityFailure)

        val wrongRaw = ScriptedTransport("ID023;".ascii())
        val wrong = ExactKenwoodIdentityTransport(wrongRaw, DriverProto.CAT_PROFILE_TS890)
        wrong.writeAll("ID;".ascii())
        val error = assertThrows(IOException::class.java) {
            wrong.readSome(ByteArray(64))
        }
        assertTrue(error.message!!.contains("expected TS-890S (ID 024)"))
        assertTrue(error.message!!.contains("reported TS-990S (ID 023)"))
        assertEquals(CatPhysicalIdentity.Kenwood(23, "TS-990S"), wrong.physicalIdentity)
    }

    @Test
    fun `exact callback gate exposes neither success nor data before admission`() {
        val statuses = mutableListOf<Pair<Boolean, String>>()
        val blocks = mutableListOf<Int>()
        val gate = ExactCatAdmissionGate(
            { spectrum, iq -> blocks += spectrum.size + iq.size },
            { up, detail -> statuses += up to detail },
        )

        gate.onStatus(true, "premature")
        gate.onData(FloatArray(3), FloatArray(2))
        assertTrue(statuses.isEmpty())
        assertTrue(blocks.isEmpty())

        assertTrue(gate.finish(true, null, "IC-7300"))
        gate.onData(FloatArray(3), FloatArray(2))
        gate.onStatus(false, "link lost")
        assertEquals(listOf(true to "IC-7300", false to "link lost"), statuses)
        assertEquals(listOf(5), blocks)

        val rejectedStatuses = mutableListOf<Pair<Boolean, String>>()
        val rejected = ExactCatAdmissionGate(
            { _, _ -> throw AssertionError("mismatched identity exposed data") },
            { up, detail -> rejectedStatuses += up to detail },
        )
        val mismatch = "CAT physical identity mismatch"
        assertFalse(rejected.finish(true, mismatch, "IC-705"))
        rejected.onData(FloatArray(1), FloatArray(0))
        assertEquals(listOf(false to mismatch), rejectedStatuses)
    }

    private fun bytes(vararg values: Int): ByteArray =
        ByteArray(values.size) { values[it].toByte() }

    private fun String.ascii(): ByteArray = toByteArray(Charsets.US_ASCII)
}
