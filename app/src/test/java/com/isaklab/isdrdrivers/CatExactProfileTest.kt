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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CatExactProfileTest {
    @Test
    fun `exact profiles map to one wire evidence and generic claims none`() {
        assertNull(expectedCatProfileEvidence(DriverProto.CAT_PROFILE_GENERIC))
        assertEquals(
            CatProfileEvidence.CivAddress(0x94),
            expectedCatProfileEvidence(DriverProto.CAT_PROFILE_IC7300),
        )
        assertEquals(
            CatProfileEvidence.CivAddress(0xA4),
            expectedCatProfileEvidence(DriverProto.CAT_PROFILE_IC705),
        )
        assertEquals(
            CatProfileEvidence.KenwoodModel(24, "TS-890S"),
            expectedCatProfileEvidence(DriverProto.CAT_PROFILE_TS890),
        )
        assertEquals(
            CatProfileEvidence.KenwoodModel(23, "TS-990S"),
            expectedCatProfileEvidence(DriverProto.CAT_PROFILE_TS990),
        )
    }

    @Test
    fun `native client evidence is exact while generic remains compatible`() {
        assertNull(requiredReportedCivAddress(DriverProto.CAT_PROFILE_GENERIC))
        assertNull(requiredKenwoodModelId(DriverProto.CAT_PROFILE_GENERIC))
        assertEquals(0x94, requiredReportedCivAddress(DriverProto.CAT_PROFILE_IC7300))
        assertEquals(0xA4, requiredReportedCivAddress(DriverProto.CAT_PROFILE_IC705))
        assertEquals(24, requiredKenwoodModelId(DriverProto.CAT_PROFILE_TS890))
        assertEquals(23, requiredKenwoodModelId(DriverProto.CAT_PROFILE_TS990))
        assertNull(requiredKenwoodModelId(DriverProto.CAT_PROFILE_IC7300))
        assertNull(requiredReportedCivAddress(DriverProto.CAT_PROFILE_TS890))
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
}
