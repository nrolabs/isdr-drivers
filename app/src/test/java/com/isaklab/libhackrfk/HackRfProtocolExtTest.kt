package com.isaklab.libhackrfk

import org.junit.Assert.assertEquals
import org.junit.Test

/** Byte-accuracy of the operational extensions vs reference hackrf.c. */
class HackRfProtocolExtTest {
    @Test fun biasTOptsWordMatchesReferenceBitLayout() {
        // hackrf_set_user_bias_t_opts: off|=0x4,0x2+en; rx|=0x20,0x10+(en<<3);
        // tx|=0x100,0x80+(en<<6). All three updated, all enabled:
        assertEquals(
            0x4 or 0x2 or 1 or 0x20 or 0x10 or (1 shl 3) or 0x100 or 0x80 or (1 shl 6),
            HackRfProtocol.biasTOptsWord(true, true, true, true, true, true),
        )
        // Only TX updated, disabled:
        assertEquals(0x100 or 0x80, HackRfProtocol.biasTOptsWord(false, false, false, false, true, false))
        // Nothing updated -> no modification word:
        assertEquals(0, HackRfProtocol.biasTOptsWord(false, false, false, false, false, false))
    }

    @Test fun requestNumbersMatchReferenceEnum() {
        assertEquals(18, HackRfProtocol.REQ_BOARD_PARTID_SERIALNO_READ)
        assertEquals(27, HackRfProtocol.REQ_OPERACAKE_GET_BOARDS)
        assertEquals(28, HackRfProtocol.REQ_OPERACAKE_SET_PORTS)
        assertEquals(30, HackRfProtocol.REQ_RESET)
        assertEquals(32, HackRfProtocol.REQ_CLKOUT_ENABLE)
        assertEquals(38, HackRfProtocol.REQ_OPERACAKE_SET_MODE)
        assertEquals(42, HackRfProtocol.REQ_SET_TX_UNDERRUN_LIMIT)
        assertEquals(43, HackRfProtocol.REQ_SET_RX_OVERRUN_LIMIT)
        assertEquals(44, HackRfProtocol.REQ_GET_CLKIN_STATUS)
        assertEquals(45, HackRfProtocol.REQ_BOARD_REV_READ)
        assertEquals(48, HackRfProtocol.REQ_SET_USER_BIAS_T_OPTS)
    }
}
