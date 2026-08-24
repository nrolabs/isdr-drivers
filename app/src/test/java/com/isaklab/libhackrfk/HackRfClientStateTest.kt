package com.isaklab.libhackrfk


import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Client-level contracts exercised through the [HackRfTransport] seam:
 * state guards while keyed/sweeping, gain requests as status-bearing IN
 * transfers, and stream reassembly through the production RX plumbing.
 */
class HackRfClientStateTest {

    /** Scripted transport: records every control transfer, answers gains. */
    private class FakeTransport : HackRfTransport {
        data class Ctl(val type: Int, val request: Int, val value: Int, val index: Int)

        val controls = mutableListOf<Ctl>()
        val failedRequests = mutableSetOf<Int>()
        var gainStatus: Byte = 1
        var rxChunks: List<ByteArray> = emptyList()

        override fun controlTransfer(
            requestType: Int, request: Int, value: Int, index: Int,
            data: ByteArray?, length: Int, timeoutMs: Int,
        ): Int {
            synchronized(controls) { controls.add(Ctl(requestType, request, value, index)) }
            if (request in failedRequests) return -1
            if (requestType == HackRfProtocol.TYPE_VENDOR_IN && data != null && length >= 1) {
                data.fill(0)
                data[0] = gainStatus
                return length
            }
            return length
        }

        override fun bulkWrite(data: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int = length

        override fun bulkRead(data: ByteArray, length: Int, timeoutMs: Int): Int = -1

        override val hasTxEndpoint: Boolean get() = true

        override fun rxStream(
            transferBytes: Int,
            requests: Int,
            keepGoing: () -> Boolean,
            onBytes: (ByteArray, Int) -> Unit,
        ) {
            for (c in rxChunks) {
                if (!keepGoing()) return
                onBytes(c, c.size)
            }
            while (keepGoing()) Thread.sleep(1)
        }

        override fun cancelRx() {}

        fun sent(request: Int): Boolean =
            synchronized(controls) { controls.any { it.request == request } }

        fun clear() = synchronized(controls) { controls.clear() }
    }

    private fun client(
        fake: FakeTransport,
        onData: (FloatArray, FloatArray) -> Unit = { _, _ -> },
    ): HackRfClient {
        val c = HackRfClient(null, onData, { _, _ -> })
        c.attachTransportForTest(fake)
        return c
    }

    // ---- item 3: state guards ----------------------------------------------

    @Test
    fun `sample rate change is rejected without deferred mutation while transmitting`() {
        val fake = FakeTransport()
        val c = client(fake)
        c.forceTransmittingForTest(true)
        fake.clear()
        assertTrue(runCatching { c.setSampleRate(8_000_000) }.isFailure)
        assertFalse(fake.sent(HackRfProtocol.REQ_SAMPLE_RATE_SET))
        c.forceTransmittingForTest(false)
        c.setSampleRate(8_000_000)
        assertTrue(fake.sent(HackRfProtocol.REQ_SAMPLE_RATE_SET))
    }

    @Test
    fun `sample rate change is rejected while sweeping`() {
        val fake = FakeTransport()
        val c = client(fake)
        c.forceSweepingForTest(true)
        fake.clear()
        assertTrue(runCatching { c.setSampleRate(10_000_000) }.isFailure)
        assertFalse(fake.sent(HackRfProtocol.REQ_SAMPLE_RATE_SET))
    }

    @Test
    fun `analog filter change is rejected while transmitting or sweeping`() {
        val fake = FakeTransport()
        val c = client(fake)
        c.forceTransmittingForTest(true)
        fake.clear()
        assertTrue(runCatching { c.setAnalogFilterHz(1_750_000) }.isFailure)
        assertFalse(fake.sent(HackRfProtocol.REQ_BASEBAND_FILTER_BW_SET))
        c.forceTransmittingForTest(false)
        c.forceSweepingForTest(true)
        assertTrue(runCatching { c.setAnalogFilterHz(1_750_000) }.isFailure)
        assertFalse(fake.sent(HackRfProtocol.REQ_BASEBAND_FILTER_BW_SET))
        c.forceSweepingForTest(false)
        c.setAnalogFilterHz(1_750_000)
        assertTrue(fake.sent(HackRfProtocol.REQ_BASEBAND_FILTER_BW_SET))
        // Only the later, actually-applied value becomes authoritative.
        assertEquals(HackRfProtocol.basebandFilterFor(1_750_000), c.basebandFilterHz())
    }

    @Test
    fun `frequency change while keyed is rejected and not recorded`() {
        val fake = FakeTransport()
        val c = client(fake)
        c.forceTransmittingForTest(true)
        fake.clear()
        val before = c.frequencyHz()
        assertTrue(runCatching { c.setFrequency(7_100_000L) }.isFailure)
        assertFalse(fake.sent(HackRfProtocol.REQ_SET_FREQ))
        assertEquals(before, c.frequencyHz())
    }

    // ---- item 4: gain requests carry a status byte -------------------------

    @Test
    fun `gain requests are vendor IN and honour the status byte`() {
        val fake = FakeTransport()
        val c = client(fake)
        assertTrue(c.setVgaGain(20))
        assertTrue(c.setTxVgaGain(10))
        val gains = synchronized(fake.controls) {
            fake.controls.filter {
                it.request == HackRfProtocol.REQ_SET_VGA_GAIN ||
                    it.request == HackRfProtocol.REQ_SET_TXVGA_GAIN
            }
        }
        assertEquals(2, gains.size)
        gains.forEach {
            assertEquals(HackRfProtocol.TYPE_VENDOR_IN, it.type)
            assertEquals(0, it.value) // gain rides in wIndex, wValue is zero
        }
        assertEquals(20, gains[0].index)
        assertEquals(10, gains[1].index)
    }

    @Test
    fun `a firmware rejection is reported to the caller`() {
        val fake = FakeTransport()
        fake.gainStatus = 0
        val c = client(fake)
        assertFalse(c.setVgaGain(20))
        assertFalse(c.setTxVgaGain(10))
    }

    @Test
    fun `control setters reject non representable values instead of clipping`() {
        val fake = FakeTransport()
        val c = client(fake)
        assertTrue(runCatching { c.setLnaGain(7) }.isFailure)
        assertTrue(runCatching { c.setVgaGain(21) }.isFailure)
        assertTrue(runCatching { c.setTxVgaGain(48) }.isFailure)
        assertTrue(runCatching { c.setAnalogFilterHz(2_000_000) }.isFailure)
        assertTrue(runCatching { c.setSampleRate(1_999_999) }.isFailure)
        assertTrue(fake.controls.isEmpty())
    }

    @Test
    fun `identity failures remain distinct from empty values and unsupported queries`() {
        val failed = FakeTransport().apply {
            failedRequests += HackRfProtocol.REQ_VERSION_STRING_READ
            failedRequests += HackRfProtocol.REQ_BOARD_ID_READ
            failedRequests += HackRfProtocol.REQ_BOARD_PARTID_SERIALNO_READ
            failedRequests += HackRfProtocol.REQ_BOARD_REV_READ
            failedRequests += HackRfProtocol.REQ_SUPPORTED_PLATFORM_READ
        }
        val failedClient = client(failed)
        failedClient.readIdentityForTest(HackRfProtocol.API_SUPPORTED_PLATFORM)
        val failedInfo = failedClient.boardInfo()
        assertTrue(failedInfo.firmwareQueryFailed)
        assertTrue(failedInfo.boardIdQueryFailed)
        assertTrue(failedInfo.serialQueryFailed)
        assertTrue(failedInfo.boardRevisionQueryFailed)
        assertTrue(failedInfo.platformQueryFailed)

        val emptyButAnsweredClient = client(FakeTransport())
        emptyButAnsweredClient.readIdentityForTest(0)
        val emptyButAnswered = emptyButAnsweredClient.boardInfo()
        assertFalse(emptyButAnswered.firmwareQueryFailed)
        assertFalse(emptyButAnswered.boardIdQueryFailed)
        assertFalse(emptyButAnswered.serialQueryFailed)
        // Firmware that does not advertise these requests is unsupported,
        // not a failed physical readback.
        assertFalse(emptyButAnswered.boardRevisionQueryFailed)
        assertFalse(emptyButAnswered.platformQueryFailed)
    }

    // ---- item 1: reassembly through the production RX path -----------------

    @Test
    fun `rx path delivers every byte across short and odd transfers`() {
        val blockBytes = HackRfProtocol.RX_BLOCK_PAIRS * 2
        val src = ByteArray(2 * blockBytes) { ((it * 7) % 251 - 125).toByte() }
        // Split into deliberately hostile transfer sizes: short, odd, huge.
        val fake = FakeTransport()
        fake.rxChunks = listOf(
            src.copyOfRange(0, 100),
            src.copyOfRange(100, 101),
            src.copyOfRange(101, blockBytes + 3),
            src.copyOfRange(blockBytes + 3, src.size),
        )
        val received = mutableListOf<Float>()
        val lock = Object()
        val c = client(fake) { _, iq ->
            synchronized(lock) {
                iq.forEach { received.add(it) }
                lock.notifyAll()
            }
        }
        c.startRx()
        synchronized(lock) {
            val deadline = System.currentTimeMillis() + 5000
            while (received.size < src.size && System.currentTimeMillis() < deadline) {
                lock.wait(100)
            }
        }
        c.disconnect()
        assertEquals(src.size, received.size)
        for (i in src.indices) {
            assertEquals("byte $i", HackRfProtocol.s8ToFloat(src[i]), received[i], 0f)
        }
    }
}
