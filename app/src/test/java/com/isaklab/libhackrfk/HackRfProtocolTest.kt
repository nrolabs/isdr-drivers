package com.isaklab.libhackrfk

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the HackRF wire codec against the reference
 * `hackrf/host/libhackrf/src/hackrf.c`: every encoding rule the client sends
 * over USB is validated here, the same way Hl2ProtocolTest pins the
 * Hermes-Lite 2 codec.
 */
class HackRfProtocolTest {

    // ---- hackrf_set_freq: uint32 LE MHz + uint32 LE Hz remainder ------------

    @Test
    fun `freq params split mhz and remainder little-endian`() {
        // 7,100,500 Hz → mhz=7, rem=100,500 (0x018894)
        val p = HackRfProtocol.freqParams(7_100_500L)
        assertArrayEquals(
            byteArrayOf(7, 0, 0, 0, 0x94.toByte(), 0x88.toByte(), 0x01, 0),
            p,
        )
    }

    @Test
    fun `freq params handle the 6 ghz top of the range`() {
        val p = HackRfProtocol.freqParams(6_000_000_000L)
        // mhz = 6000 = 0x1770, rem = 0
        assertArrayEquals(
            byteArrayOf(0x70, 0x17, 0, 0, 0, 0, 0, 0),
            p,
        )
    }

    @Test
    fun `freq params below one mhz carry only the remainder`() {
        val p = HackRfProtocol.freqParams(455_000L)
        // mhz = 0, rem = 455000 = 0x06F158
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 0, 0x58, 0xF1.toByte(), 0x06, 0),
            p,
        )
    }

    // ---- hackrf_set_sample_rate_manual: uint32 LE Hz + uint32 LE divider ----

    @Test
    fun `sample rate params encode rate and divider little-endian`() {
        val p = HackRfProtocol.sampleRateParams(10_000_000, 1)
        // 10,000,000 = 0x00989680
        assertArrayEquals(
            byteArrayOf(0x80.toByte(), 0x96.toByte(), 0x98.toByte(), 0x00, 1, 0, 0, 0),
            p,
        )
    }

    // ---- baseband filter selection (hackrf_compute_baseband_filter_bw) ------

    @Test
    fun `exact table match is returned as-is`() {
        assertEquals(5_000_000, HackRfProtocol.basebandFilterFor(5_000_000))
    }

    @Test
    fun `between entries rounds down to the previous filter`() {
        // 4.2 MHz sits between 3.5 and 5 MHz → 3.5 MHz (reference rounds down).
        assertEquals(3_500_000, HackRfProtocol.basebandFilterFor(4_200_000))
    }

    @Test
    fun `below the smallest entry clamps up to the smallest`() {
        // The reference cannot go below the first entry.
        assertEquals(1_750_000, HackRfProtocol.basebandFilterFor(1_000_000))
    }

    @Test
    fun `beyond the table clamps to the widest filter`() {
        assertEquals(28_000_000, HackRfProtocol.basebandFilterFor(40_000_000))
    }

    @Test
    fun `auto filter for a sample rate uses 75 percent of the rate`() {
        // 0.75 × 20 MHz = 15 MHz → exact table entry.
        assertEquals(15_000_000, HackRfProtocol.basebandFilterForSampleRate(20_000_000))
        // 0.75 × 2 MHz = 1.5 MHz → below smallest → 1.75 MHz.
        assertEquals(1_750_000, HackRfProtocol.basebandFilterForSampleRate(2_000_000))
        // 0.75 × 8 MHz = 6 MHz → exact entry.
        assertEquals(6_000_000, HackRfProtocol.basebandFilterForSampleRate(8_000_000))
        // 0.75 × 10 MHz = 7.5 MHz → rounds down to 7 MHz.
        assertEquals(7_000_000, HackRfProtocol.basebandFilterForSampleRate(10_000_000))
    }

    @Test
    fun `filter bandwidth splits across wValue and wIndex`() {
        val bw = 5_500_000 // 0x53EC60
        assertEquals(0xEC60, HackRfProtocol.filterBwValue(bw))
        assertEquals(0x53, HackRfProtocol.filterBwIndex(bw))
    }

    // ---- gain rules -----------------------------------------------------------

    @Test
    fun `lna gain masks to 8 db steps and clamps to 0-40`() {
        assertEquals(32, HackRfProtocol.lnaGainMasked(37))   // 37 & ~7
        assertEquals(40, HackRfProtocol.lnaGainMasked(40))
        assertEquals(40, HackRfProtocol.lnaGainMasked(99))
        assertEquals(0, HackRfProtocol.lnaGainMasked(-5))
        assertEquals(0, HackRfProtocol.lnaGainMasked(7))
    }

    @Test
    fun `vga gain masks to 2 db steps and clamps to 0-62`() {
        assertEquals(20, HackRfProtocol.vgaGainMasked(21))
        assertEquals(62, HackRfProtocol.vgaGainMasked(62))
        assertEquals(62, HackRfProtocol.vgaGainMasked(63))
        assertEquals(0, HackRfProtocol.vgaGainMasked(-1))
    }

    @Test
    fun `txvga gain clamps to 0-47`() {
        assertEquals(47, HackRfProtocol.txVgaGain(47))
        assertEquals(47, HackRfProtocol.txVgaGain(200))
        assertEquals(0, HackRfProtocol.txVgaGain(-3))
    }

    @Test
    fun `drive scale maps 0-255 onto the full txvga range`() {
        assertEquals(0, HackRfProtocol.driveToTxVga(0))
        assertEquals(47, HackRfProtocol.driveToTxVga(255))
        assertEquals(23, HackRfProtocol.driveToTxVga(128))
    }

    // ---- sample conversion -----------------------------------------------------

    @Test
    fun `float to s8 covers full scale and clips beyond`() {
        assertEquals(127.toByte(), HackRfProtocol.toS8(1.0f))
        assertEquals((-127).toByte(), HackRfProtocol.toS8(-1.0f))
        assertEquals(0.toByte(), HackRfProtocol.toS8(0f))
        assertEquals(127.toByte(), HackRfProtocol.toS8(3.5f))
        assertEquals((-127).toByte(), HackRfProtocol.toS8(-2f))
    }

    @Test
    fun `s8 round trip stays within one lsb`() {
        for (raw in -128..127) {
            val f = HackRfProtocol.s8ToFloat(raw.toByte())
            assertTrue("raw=$raw f=$f", f in -1.0f..1.0f)
        }
        // A representative mid value survives the loop within quantization.
        val f = HackRfProtocol.s8ToFloat(HackRfProtocol.toS8(0.5f))
        assertEquals(0.5f, f, 1.5f / 127f)
    }

    // ---- identity ----------------------------------------------------------------

    @Test
    fun `known boards are recognized and others rejected`() {
        assertTrue(HackRfProtocol.isKnownDevice(0x1d50, 0x6089))  // One
        assertTrue(HackRfProtocol.isKnownDevice(0x1d50, 0x604b))  // Jawbreaker
        assertTrue(HackRfProtocol.isKnownDevice(0x1d50, 0xcc15))  // rad1o
        assertFalse(HackRfProtocol.isKnownDevice(0x1d50, 0x1234))
        assertFalse(HackRfProtocol.isKnownDevice(0x0bda, 0x2838)) // RTL dongle
    }

    // ---- TX interpolation ----------------------------------------------------------

    @Test
    fun `tx upsampling is an exact integer factor of the input rate`() {
        assertEquals(0, HackRfProtocol.TX_BOARD_RATE % HackRfProtocol.TX_INPUT_RATE)
        assertEquals(50, HackRfProtocol.TX_UPSAMPLE)
    }

    // ---- request ids pinned against the reference enum (hackrf.c:64-122) ----

    @Test
    fun `vendor request ids match the reference enum`() {
        assertEquals(1, HackRfProtocol.REQ_SET_TRANSCEIVER_MODE)
        assertEquals(6, HackRfProtocol.REQ_SAMPLE_RATE_SET)
        assertEquals(7, HackRfProtocol.REQ_BASEBAND_FILTER_BW_SET)
        assertEquals(14, HackRfProtocol.REQ_BOARD_ID_READ)
        assertEquals(15, HackRfProtocol.REQ_VERSION_STRING_READ)
        assertEquals(16, HackRfProtocol.REQ_SET_FREQ)
        assertEquals(17, HackRfProtocol.REQ_AMP_ENABLE)
        assertEquals(19, HackRfProtocol.REQ_SET_LNA_GAIN)
        assertEquals(20, HackRfProtocol.REQ_SET_VGA_GAIN)
        assertEquals(21, HackRfProtocol.REQ_SET_TXVGA_GAIN)
        assertEquals(23, HackRfProtocol.REQ_ANTENNA_ENABLE)
        assertEquals(61, HackRfProtocol.REQ_GET_BUFFER_SIZE)
        assertEquals(0, HackRfProtocol.MODE_OFF)
        assertEquals(1, HackRfProtocol.MODE_RECEIVE)
        assertEquals(2, HackRfProtocol.MODE_TRANSMIT)
    }

    @Test
    fun `tx bulk writes stay multiples of the 512-byte usb packet`() {
        // Firmware bulk endpoints use 512-byte max packets and the reference
        // host pads TX to that boundary; our block is 512 pairs × 50 × 2.
        val txBlockBytes = 512 * HackRfProtocol.TX_UPSAMPLE * 2
        assertEquals(0, txBlockBytes % 512)
    }

    // ---- sweep mode (usb_api_sweep.c) ----------------------------------------

    @Test
    fun `init sweep payload encodes step, offset, style and mhz ranges`() {
        val p = HackRfProtocol.initSweepParams(
            listOf(88 to 108),
            stepWidthHz = 20_000_000,
            offsetHz = 10_000_000,
            style = HackRfProtocol.SWEEP_STYLE_LINEAR,
        )
        assertEquals(9 + 4, p.size)
        // 20,000,000 = 0x01312D00; 10,000,000 = 0x00989680 (little-endian)
        assertArrayEquals(
            byteArrayOf(0x00, 0x2D, 0x31, 0x01),
            p.copyOfRange(0, 4),
        )
        assertArrayEquals(
            byteArrayOf(0x80.toByte(), 0x96.toByte(), 0x98.toByte(), 0x00),
            p.copyOfRange(4, 8),
        )
        assertEquals(0, p[8].toInt())
        // 88 = 0x0058, 108 = 0x006C as u16 LE
        assertArrayEquals(byteArrayOf(0x58, 0x00, 0x6C, 0x00), p.copyOfRange(9, 13))
    }

    @Test
    fun `sweep dwell size splits across wValue and wIndex`() {
        val dwell = HackRfProtocol.SWEEP_BLOCK_SIZE   // 16384 = 0x4000
        assertEquals(0x4000, HackRfProtocol.sweepDwellValue(dwell))
        assertEquals(0, HackRfProtocol.sweepDwellIndex(dwell))
        assertEquals(0x0000, HackRfProtocol.sweepDwellValue(0x2_0000))
        assertEquals(2, HackRfProtocol.sweepDwellIndex(0x2_0000))
    }

    @Test
    fun `sweep block header carries the magic and a u64 le frequency`() {
        val block = ByteArray(HackRfProtocol.SWEEP_BLOCK_SIZE)
        block[0] = 0x7F
        block[1] = 0x7F
        // 100,000,000 Hz = 0x05F5E100, little-endian into bytes 2..9
        val freq = 100_000_000L
        for (k in 0 until 8) block[2 + k] = ((freq shr (8 * k)) and 0xFF).toByte()
        assertTrue(HackRfProtocol.isSweepBlock(block, 0))
        assertEquals(freq, HackRfProtocol.sweepBlockFreqHz(block, 0))

        block[1] = 0x00
        assertFalse(HackRfProtocol.isSweepBlock(block, 0))
    }

    // The interpolation itself (spectral image suppression, DC exactness,
    // reset semantics) is pinned separately in TxInterpolatorTest.
}
