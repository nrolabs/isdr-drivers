/*
 * librtlsdrk - Kotlin driver for RTL-SDR receivers
 * Copyright (C) 2026 Isak Ruas <isakruas@gmail.com>
 */
package com.isaklab.librtlsdrk

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RTLTCPHeaderTest {

    @Test fun headerUsesNetworkByteOrderForTunerAndGainCount() {
        val bytes = byteArrayOf(
            'R'.code.toByte(), 'T'.code.toByte(), 'L'.code.toByte(), '0'.code.toByte(),
            0, 0, 0, 5,
            0, 0, 0, 29,
        )

        val header = readRtlTcpHeader(DataInputStream(ByteArrayInputStream(bytes)))

        assertEquals(5, header.tunerType)
        assertEquals(29, header.tunerGainCount)
    }

    @Test fun invalidMagicIsRejectedBeforeIdentityIsRead() {
        val bytes = byteArrayOf(
            'R'.code.toByte(), 'T'.code.toByte(), 'L'.code.toByte(), '1'.code.toByte(),
            0, 0, 0, 5,
            0, 0, 0, 29,
        )
        assertThrows(IOException::class.java) {
            readRtlTcpHeader(DataInputStream(ByteArrayInputStream(bytes)))
        }
    }

    @Test fun maliciousGainCountIsRejectedBeforeAllocation() {
        val bytes = byteArrayOf(
            'R'.code.toByte(), 'T'.code.toByte(), 'L'.code.toByte(), '0'.code.toByte(),
            0, 0, 0, 5,
            0, 0, 1, 1,
        )
        assertThrows(IOException::class.java) {
            readRtlTcpHeader(DataInputStream(ByteArrayInputStream(bytes)))
        }
    }

    @Test fun standardTableIsKnownOnlyWhenTunerAndCountBothMatch() {
        val exact = rtlTcpTunerInfo(RtlTunerType.R820T, R82xxTuner.GAINS.size)
        assertTrue(exact.gainTableKnown)
        assertEquals(R82xxTuner.GAINS.toList(), exact.gainsTenthsDb)

        val mismatch = rtlTcpTunerInfo(RtlTunerType.R820T, R82xxTuner.GAINS.size - 1)
        assertFalse(mismatch.gainTableKnown)
        assertTrue(mismatch.gainsTenthsDb.isEmpty())
    }

    @Test fun rateAndGainAreRejectedInsteadOfSnappedOrDropped() {
        val client = RTLTCPClient(
            host = "127.0.0.1",
            port = 1234,
            onDataReceived = { _, _ -> },
            onConnectionStatusChanged = { _, _ -> },
        )

        assertThrows(IllegalArgumentException::class.java) {
            client.setSampleRate(2_000_001)
        }
        assertThrows(IllegalStateException::class.java) {
            client.setSampleRate(1_024_000)
        }
        assertEquals(2_048_000, client.sampleRateHz())

        assertThrows(IllegalArgumentException::class.java) { client.setGain(501) }
        assertThrows(IllegalArgumentException::class.java) { client.setGain(496) }
    }
}
