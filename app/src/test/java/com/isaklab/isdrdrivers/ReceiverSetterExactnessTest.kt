package com.isaklab.isdrdrivers

import com.isaklab.libg2sdrk.G2Client
import com.isaklab.libhl2sdrk.Hl2Client
import org.junit.Assert.assertTrue
import org.junit.Test

/** The libraries must reject invalid topology values instead of clipping or ignoring them. */
class ReceiverSetterExactnessTest {
    private fun rejected(block: () -> Unit): Boolean = runCatching(block).isFailure

    @Test
    fun hl2ReceiverSettersAreExactAcrossAllFourDdcs() {
        val client = Hl2Client(onDataReceived = { _, _ -> }, onConnectionStatusChanged = { _, _ -> })
        assertTrue(rejected { client.setReceiverCount(5) })
        assertTrue(rejected { client.setActiveReceiver(1) })
        assertTrue(rejected { client.setRxFrequency(1, 14_200_000) })
        assertTrue(rejected { client.setRxStreamMask(0b1) })

        // A valid value with no live transport is still not an applied command.
        assertTrue(rejected { client.setReceiverCount(4) })
        assertTrue(rejected { client.setRxFrequency(0, 14_200_000) })
        assertTrue(rejected { client.setRxStreamMask(0) })
    }

    @Test
    fun g2ReceiverSettersRejectInsteadOfMaskingValues() {
        val client = G2Client(onDataReceived = { _, _ -> }, onConnectionStatusChanged = { _, _ -> })
        assertTrue(rejected { client.setReceiverCount(8) })
        assertTrue(rejected { client.setActiveReceiver(1) })
        assertTrue(rejected { client.setRxFrequency(1, 14_200_000) })
        assertTrue(rejected { client.setRxStreamMask(-1) })

        assertTrue(rejected { client.setReceiverCount(7) })
        assertTrue(rejected { client.setRxFrequency(0, 14_200_000) })
        assertTrue(rejected { client.setRxStreamMask(0) })
    }
}
