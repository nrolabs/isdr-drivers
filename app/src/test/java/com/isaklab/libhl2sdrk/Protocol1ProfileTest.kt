package com.isaklab.libhl2sdrk

import com.isaklab.libg2sdrk.G2Protocol
import com.isaklab.isdrproto.DriverProto
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Protocol1ProfileTest {
    @Test fun protocolNamespacesGiveBoardTenItsProtocolExactMeaning() {
        assertEquals(10, Protocol1DiscoveryBoardId.ORION_MK_II)
        assertEquals(10, G2Protocol.DiscoveryBoardId.SATURN)
        assertEquals(
            Protocol1DiscoveryBoardId.ORION_MK_II,
            Protocol1Profile.ANAN_7000DLE.discoveryBoardId,
        )
        assertEquals("Saturn (ANAN-G2)", G2Protocol.DiscoveryBoardId.name(10))
    }

    @Test fun exactFlagsMapToExactProfilesAndLegacyDoesNot() {
        assertEquals(
            listOf(0, 3, 5, 7, 9, 11, 13, 15, 17),
            Protocol1Profile.entries.map { it.openFlags },
        )
        Protocol1Profile.entries.forEach { profile ->
            assertEquals(profile, Protocol1Profile.fromOpenFlags(profile.openFlags))
        }
        assertNull(Protocol1Profile.fromOpenFlags(DriverProto.OPEN_FLAG_CLASSIC_BOARD))
        assertNull(Protocol1Profile.fromOpenFlags(19))
    }

    @Test fun discoveryBoardFamilyMustMatchTheSelectedProfile() {
        fun reply(boardId: Int) = ByteArray(64).also {
            it[0] = 0xEF.toByte()
            it[1] = 0xFE.toByte()
            it[10] = boardId.toByte()
        }

        val expectedIds = listOf(6, 1, 1, 2, 2, 4, 5, 10, 10)
        assertEquals(expectedIds, Protocol1Profile.entries.map { it.discoveryBoardId })
        Protocol1Profile.entries.forEach { profile ->
            assertTrue(profile.acceptsDiscoveryReply(reply(profile.discoveryBoardId), 64))
            val wrong = if (profile.discoveryBoardId == 6) 1 else 6
            assertFalse(profile.acceptsDiscoveryReply(reply(wrong), 64))
        }
        assertFalse(
            Protocol1Profile.ANAN_100D.acceptsDiscoveryReply(reply(4), 8),
        )
    }

    @Test fun receiverAndDiversityCapsAreProfileExact() {
        assertEquals(
            listOf(4, 4, 4, 2, 2, 4, 4, 4, 4),
            Protocol1Profile.entries.map { it.receiverCapacity },
        )
        assertEquals(
            listOf(false, false, false, false, false, true, true, true, true),
            Protocol1Profile.entries.map { it.diversitySupported },
        )
        assertEquals(
            listOf(1, 1, 1, 1, 1, 2, 2, 2, 2),
            Protocol1Profile.entries.map { it.physicalAdcCount },
        )
        assertEquals(
            listOf(true, false, false, false, false, false, false, false, false),
            Protocol1Profile.entries.map { it.pureSignalSupported },
        )
    }

    @Test fun driverHostAdvertisesAndPreflightsExactProfilesBeforeMutation() {
        val candidates = listOf(
            File("src/main/java/com/isaklab/isdrdrivers/DriverSession.kt"),
            File("app/src/main/java/com/isaklab/isdrdrivers/DriverSession.kt"),
        )
        val source = candidates.firstOrNull(File::isFile)?.readText()
            ?: error("DriverSession source not found")
        assertTrue(source.contains("DriverProto.FEAT_HPSDR_EXACT_PROFILE"))
        assertTrue(source.contains("DriverProto.FEAT_RX_ADC_ROUTING"))
        assertFalse(source.contains("classicBoard"))
        assertTrue(source.contains("radioControlReady = connected"))
        assertTrue(source.contains("radio control transport is not connected"))
        val preflight = source.indexOf("Protocol1Discovery.find(")
        val mutation = source.indexOf("closeDevice()", preflight)
        assertTrue("discovery preflight must precede close/claim", preflight >= 0 && mutation > preflight)
    }
}
