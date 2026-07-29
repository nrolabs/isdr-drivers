package com.isaklab.libhackrfk

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Byte-accuracy of the operational control encodings. */
class HackRfProtocolExtTest {

    private fun bias(update: Boolean, onEntry: Boolean, enabled: Boolean) =
        HackRfProtocol.BiasTSetting(update, onEntry, enabled)

    @Test fun biasTOptsWordBitLayout() {
        // off |= 0x4, then 0x2+en only when changeOnEntry; rx |= 0x20, then
        // 0x10+(en<<3); tx |= 0x100, then 0x80+(en<<6).
        assertEquals(
            0x4 or 0x2 or 1 or 0x20 or 0x10 or (1 shl 3) or 0x100 or 0x80 or (1 shl 6),
            HackRfProtocol.biasTOptsWord(
                bias(true, true, true), bias(true, true, true), bias(true, true, true),
            ),
        )
        // Only TX updated, changing on entry, disabled:
        assertEquals(
            0x100 or 0x80,
            HackRfProtocol.biasTOptsWord(
                bias(false, false, false), bias(false, false, false), bias(true, true, false),
            ),
        )
        assertEquals(
            0,
            HackRfProtocol.biasTOptsWord(
                bias(false, false, false), bias(false, false, false), bias(false, false, false),
            ),
        )
    }

    /**
     * Update WITHOUT change-on-entry is how a mode is handed back to the
     * firmware default. Collapsing the two flags into one makes that word
     * unreachable, so a bias-tee setting can be turned on but never released.
     */
    @Test fun biasTUpdateWithoutChangeOnEntryIsReachable() {
        assertEquals(
            0x4,
            HackRfProtocol.biasTOptsWord(
                bias(true, false, false), bias(false, false, false), bias(false, false, false),
            ),
        )
        assertEquals(
            0x20,
            HackRfProtocol.biasTOptsWord(
                bias(false, false, false), bias(true, false, true), bias(false, false, false),
            ),
        )
        assertEquals(
            0x100,
            HackRfProtocol.biasTOptsWord(
                bias(false, false, false), bias(false, false, false), bias(true, false, true),
            ),
        )
    }

    @Test fun vendorRequestIdsArePinned() {
        assertEquals(2, HackRfProtocol.REQ_MAX283X_WRITE)
        assertEquals(3, HackRfProtocol.REQ_MAX283X_READ)
        assertEquals(4, HackRfProtocol.REQ_SI5351C_WRITE)
        assertEquals(5, HackRfProtocol.REQ_SI5351C_READ)
        assertEquals(8, HackRfProtocol.REQ_RFFC5071_WRITE)
        assertEquals(9, HackRfProtocol.REQ_RFFC5071_READ)
        assertEquals(10, HackRfProtocol.REQ_SPIFLASH_ERASE)
        assertEquals(11, HackRfProtocol.REQ_SPIFLASH_WRITE)
        assertEquals(12, HackRfProtocol.REQ_SPIFLASH_READ)
        assertEquals(14, HackRfProtocol.REQ_BOARD_ID_READ)
        assertEquals(18, HackRfProtocol.REQ_BOARD_PARTID_SERIALNO_READ)
        assertEquals(24, HackRfProtocol.REQ_SET_FREQ_EXPLICIT)
        assertEquals(27, HackRfProtocol.REQ_OPERACAKE_GET_BOARDS)
        assertEquals(28, HackRfProtocol.REQ_OPERACAKE_SET_PORTS)
        assertEquals(29, HackRfProtocol.REQ_SET_HW_SYNC_MODE)
        assertEquals(30, HackRfProtocol.REQ_RESET)
        assertEquals(31, HackRfProtocol.REQ_OPERACAKE_SET_RANGES)
        assertEquals(32, HackRfProtocol.REQ_CLKOUT_ENABLE)
        assertEquals(33, HackRfProtocol.REQ_SPIFLASH_STATUS)
        assertEquals(34, HackRfProtocol.REQ_SPIFLASH_CLEAR_STATUS)
        assertEquals(35, HackRfProtocol.REQ_OPERACAKE_GPIO_TEST)
        assertEquals(36, HackRfProtocol.REQ_CPLD_CHECKSUM)
        assertEquals(37, HackRfProtocol.REQ_UI_ENABLE)
        assertEquals(38, HackRfProtocol.REQ_OPERACAKE_SET_MODE)
        assertEquals(39, HackRfProtocol.REQ_OPERACAKE_GET_MODE)
        assertEquals(40, HackRfProtocol.REQ_OPERACAKE_SET_DWELL_TIMES)
        assertEquals(42, HackRfProtocol.REQ_SET_TX_UNDERRUN_LIMIT)
        assertEquals(43, HackRfProtocol.REQ_SET_RX_OVERRUN_LIMIT)
        assertEquals(44, HackRfProtocol.REQ_GET_CLKIN_STATUS)
        assertEquals(45, HackRfProtocol.REQ_BOARD_REV_READ)
        assertEquals(46, HackRfProtocol.REQ_SUPPORTED_PLATFORM_READ)
        assertEquals(47, HackRfProtocol.REQ_SET_LEDS)
        assertEquals(48, HackRfProtocol.REQ_SET_USER_BIAS_T_OPTS)
        assertEquals(49, HackRfProtocol.REQ_FPGA_WRITE_REG)
        assertEquals(50, HackRfProtocol.REQ_FPGA_READ_REG)
        assertEquals(51, HackRfProtocol.REQ_P2_CTRL)
        assertEquals(52, HackRfProtocol.REQ_P1_CTRL)
        assertEquals(53, HackRfProtocol.REQ_SET_NARROWBAND_FILTER)
        assertEquals(54, HackRfProtocol.REQ_SET_FPGA_BITSTREAM)
        assertEquals(55, HackRfProtocol.REQ_CLKIN_CTRL)
        assertEquals(56, HackRfProtocol.REQ_READ_SELFTEST)
        assertEquals(57, HackRfProtocol.REQ_READ_ADC)
        assertEquals(58, HackRfProtocol.REQ_TEST_RTC_OSC)
        assertEquals(59, HackRfProtocol.REQ_RADIO_WRITE_REG)
        assertEquals(60, HackRfProtocol.REQ_RADIO_READ_REG)
        assertEquals(61, HackRfProtocol.REQ_GET_BUFFER_SIZE)
    }

    // ---- explicit tuning ----------------------------------------------------

    @Test fun freqExplicitPayloadIsTwoLeU64sAndAPathByte() {
        val p = HackRfProtocol.freqExplicitParams(2_400_000_000L, 100_000_000L, 1)
        assertEquals(17, p.size)
        assertEquals(2_400_000_000L, HackRfProtocol.leU64(p, 0))
        assertEquals(100_000_000L, HackRfProtocol.leU64(p, 8))
        assertEquals(1, p[16].toInt())
    }

    @Test fun freqExplicitRejectsOutOfRangeIfAndLo() {
        // IF outside 2000-3000 MHz.
        assertFalse(HackRfProtocol.freqExplicitValid(1_999_999_999L, 100_000_000L, 1))
        assertFalse(HackRfProtocol.freqExplicitValid(3_000_000_001L, 100_000_000L, 1))
        // LO out of the synthesiser's range, but only when the mixer is used.
        assertFalse(HackRfProtocol.freqExplicitValid(2_400_000_000L, 84_374_999L, 1))
        assertTrue(HackRfProtocol.freqExplicitValid(2_400_000_000L, 0L, 0))
        // Unknown filter path.
        assertFalse(HackRfProtocol.freqExplicitValid(2_400_000_000L, 100_000_000L, 3))
        assertTrue(HackRfProtocol.freqExplicitValid(2_400_000_000L, 100_000_000L, 2))
    }

    // ---- Opera Cake ---------------------------------------------------------

    @Test fun operacakePortsMustStraddleTheSwitch() {
        assertTrue(HackRfProtocol.operacakePortsValid(HackRfProtocol.OPERACAKE_PA1, HackRfProtocol.OPERACAKE_PB1))
        assertTrue(HackRfProtocol.operacakePortsValid(HackRfProtocol.OPERACAKE_PB4, HackRfProtocol.OPERACAKE_PA4))
        assertFalse(HackRfProtocol.operacakePortsValid(HackRfProtocol.OPERACAKE_PA1, HackRfProtocol.OPERACAKE_PA2))
        assertFalse(HackRfProtocol.operacakePortsValid(HackRfProtocol.OPERACAKE_PB1, HackRfProtocol.OPERACAKE_PB2))
        assertFalse(HackRfProtocol.operacakePortsValid(0, 8))
    }

    @Test fun operacakePortsIndexPacksBIntoTheHighByte() {
        assertEquals(
            0x0400,
            HackRfProtocol.operacakePortsIndex(HackRfProtocol.OPERACAKE_PA1, HackRfProtocol.OPERACAKE_PB1),
        )
        assertEquals(0x0003, HackRfProtocol.operacakePortsIndex(3, 0))
    }

    /** This payload is big-endian, unlike every other one on this device. */
    @Test fun operacakeFreqRangesAreBigEndianMhzPairsPlusPort() {
        val p = HackRfProtocol.operacakeFreqRangesParams(
            listOf(Triple(144, 148, 1), Triple(430, 440, 5)),
        )
        assertArrayEquals(
            byteArrayOf(
                0, 144.toByte(), 0, 148.toByte(), 1,
                1, 174.toByte(), 1, 184.toByte(), 5,
            ),
            p,
        )
    }

    @Test fun operacakeDwellTimesAreLeU32PlusPort() {
        val p = HackRfProtocol.operacakeDwellTimesParams(listOf(Pair(0x11223344, 7)))
        assertArrayEquals(
            byteArrayOf(0x44, 0x33, 0x22, 0x11, 7),
            p,
        )
    }

    @Test fun operacakeAddressAndPortNames() {
        assertTrue(HackRfProtocol.operacakeValidAddress(0))
        assertTrue(HackRfProtocol.operacakeValidAddress(7))
        assertFalse(HackRfProtocol.operacakeValidAddress(8))
        assertFalse(HackRfProtocol.operacakeValidAddress(-1))
        assertEquals("A1", HackRfProtocol.operacakePortName(HackRfProtocol.OPERACAKE_PA1))
        assertEquals("A4", HackRfProtocol.operacakePortName(HackRfProtocol.OPERACAKE_PA4))
        assertEquals("B1", HackRfProtocol.operacakePortName(HackRfProtocol.OPERACAKE_PB1))
        assertEquals("B4", HackRfProtocol.operacakePortName(HackRfProtocol.OPERACAKE_PB4))
    }

    // ---- identity -----------------------------------------------------------

    /** The platform word answers big-endian; every other read is LE. */
    @Test fun platformWordIsBigEndian() {
        val bits = HackRfProtocol.parsePlatform(byteArrayOf(0, 0, 0, 0x0A))
        assertEquals(
            HackRfProtocol.PLATFORM_HACKRF1_OG or HackRfProtocol.PLATFORM_HACKRF1_R9,
            bits,
        )
        assertEquals("HackRF One OG/HackRF One R9", HackRfProtocol.platformName(bits))
    }

    @Test fun boardNameAndRevisionTables() {
        assertEquals("HackRF One OG", HackRfProtocol.boardIdName(HackRfProtocol.BOARD_ID_HACKRF1_OG))
        assertEquals("rad1o", HackRfProtocol.boardIdName(HackRfProtocol.BOARD_ID_RAD1O))
        assertEquals("undetected", HackRfProtocol.boardIdName(0xFF))
        assertEquals("r9", HackRfProtocol.boardRevName(4))
        // 0x84 is the Great Scott Gadgets flavour of the same r9.
        assertEquals("r9", HackRfProtocol.boardRevName(0x84))
        assertTrue(HackRfProtocol.boardRevIsGsg(0x84))
        assertFalse(HackRfProtocol.boardRevIsGsg(4))
        assertFalse(HackRfProtocol.boardRevIsGsg(0xFF))
    }

    @Test fun partIdSerialSplitsIntoTwoAndFourLeWords() {
        val buf = ByteArray(24)
        // serial word 0 = 0xDEADBEEF, little-endian at offset 8.
        buf[8] = 0xEF.toByte(); buf[9] = 0xBE.toByte()
        buf[10] = 0xAD.toByte(); buf[11] = 0xDE.toByte()
        val ids = HackRfProtocol.parsePartIdSerial(buf)!!
        assertEquals(2, ids.partId.size)
        assertEquals(4, ids.serialNo.size)
        assertEquals(0xDEADBEEFL, ids.serialNo[0])
        assertEquals("deadbeef000000000000000000000000", ids.serialHex())
        assertNull(HackRfProtocol.parsePartIdSerial(ByteArray(23)))
    }

    @Test fun usbApiVersionRendersAsBcd() {
        assertEquals("1.09", HackRfProtocol.usbApiVersionName(0x0109))
        assertEquals("1.12", HackRfProtocol.usbApiVersionName(0x0112))
        assertTrue(HackRfProtocol.apiAtLeast(0x0112, HackRfProtocol.API_M0_STATE))
        assertFalse(HackRfProtocol.apiAtLeast(0x0105, HackRfProtocol.API_M0_STATE))
    }

    // ---- registers and diagnostics -----------------------------------------

    @Test fun radioRegisterWritePayloadIsRegisterThenLeU64() {
        val p = HackRfProtocol.radioRegWriteParams(0x2A, 0x0102030405060708L)
        assertEquals(9, p.size)
        assertEquals(0x2A, p[0].toInt())
        assertEquals(0x0102030405060708L, HackRfProtocol.leU64(p, 1))
    }

    @Test fun adcChannelRangeIsZeroToSeven() {
        assertTrue(HackRfProtocol.adcChannelValid(0))
        assertTrue(HackRfProtocol.adcChannelValid(7))
        assertFalse(HackRfProtocol.adcChannelValid(8))
        // The high bit is a mode flag, not part of the channel number.
        assertTrue(HackRfProtocol.adcChannelValid(0x87))
    }

    @Test fun selfTestSplitsPassFlagFromMessage() {
        val buf = ByteArray(32)
        buf[0] = 1
        "ok".toByteArray(Charsets.US_ASCII).copyInto(buf, 1)
        val st = HackRfProtocol.parseSelfTest(buf)!!
        assertTrue(st.pass)
        assertEquals("ok", st.message)
        assertNull(HackRfProtocol.parseSelfTest(ByteArray(0)))
    }

    /**
     * A FAILED self-test puts a zero in byte 0. Searching the whole reply for
     * the terminator finds that zero, so the message would run to the end of
     * the buffer and carry kilobytes of padding into the UI — and a failed
     * self-test is exactly the case whose message matters.
     */
    @Test fun selfTestFailureKeepsTheMessageShort() {
        val buf = ByteArray(HackRfProtocol.SELFTEST_SIZE)
        buf[0] = 0
        "rf path fault".toByteArray(Charsets.US_ASCII).copyInto(buf, 1)
        val st = HackRfProtocol.parseSelfTest(buf)!!
        assertFalse(st.pass)
        assertEquals("rf path fault", st.message)
    }

    @Test fun selfTestWithNoMessageIsEmptyNotPadding() {
        val buf = ByteArray(64)
        buf[0] = 1
        val st = HackRfProtocol.parseSelfTest(buf)!!
        assertTrue(st.pass)
        assertEquals("", st.message)
    }

    /**
     * The bin must not get coarser as the span widens — that is the opposite
     * of why an operator widens the rate. A fixed 800 bins put 25 kHz in one
     * column at the top rate, wide enough to swallow a whole FM channel.
     */
    @Test fun spectrumResolutionFollowsTheSpan() {
        val widths = listOf(2_000_000, 8_000_000, 20_000_000).map {
            it.toDouble() / HackRfProtocol.spectrumBinsFor(it)
        }
        widths.forEach { assertTrue("bin too wide: $it Hz", it <= 6_000.0) }
        // And more span never buys fewer bins.
        assertTrue(
            HackRfProtocol.spectrumBinsFor(20_000_000) >=
                HackRfProtocol.spectrumBinsFor(2_000_000),
        )
    }

    @Test fun rtcOscOnlyPassesOnACountOfExactlyOne() {
        assertTrue(HackRfProtocol.rtcOscPassed(1))
        assertFalse(HackRfProtocol.rtcOscPassed(0))
        assertFalse(HackRfProtocol.rtcOscPassed(2))
    }
}
