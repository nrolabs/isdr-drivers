package com.isaklab.libhackrfk

import com.isaklab.isdrproto.AntennaSwitch
import com.isaklab.isdrproto.ClockTrigger
import com.isaklab.isdrproto.PanelLeds
import com.isaklab.isdrproto.RfPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire vocabulary in the wire vocabulary and the encodings in [HackRfProtocol]
 * describe the same hardware from two modules. Where both name a value, the
 * two must agree: a drift here is invisible in testing, because it only shows
 * on a board that exercises the one value that differs.
 *
 * Firmware versions are deliberately absent — they live only in the driver,
 * which sends the app the RESULT (a supported-control mask) instead of the
 * criterion.
 */
class WireVocabularyAgreementTest {

    @Test fun enumsAgreeAcrossTheWireBoundary() {
        assertEquals(HackRfProtocol.RF_PATH_FILTER_BYPASS, RfPath.BYPASS)
        assertEquals(HackRfProtocol.RF_PATH_FILTER_LOW_PASS, RfPath.LOW_PASS)
        assertEquals(HackRfProtocol.RF_PATH_FILTER_HIGH_PASS, RfPath.HIGH_PASS)

        assertEquals(HackRfProtocol.OPERACAKE_MAX_BOARDS, AntennaSwitch.MAX_BOARDS)
        assertEquals(HackRfProtocol.OPERACAKE_MAX_FREQ_RANGES, AntennaSwitch.MAX_FREQ_RANGES)
        assertEquals(HackRfProtocol.OPERACAKE_MAX_DWELL_TIMES, AntennaSwitch.MAX_DWELL_TIMES)
        assertEquals(HackRfProtocol.OPERACAKE_MODE_MANUAL, AntennaSwitch.MODE_MANUAL)
        assertEquals(HackRfProtocol.OPERACAKE_MODE_FREQUENCY, AntennaSwitch.MODE_FREQUENCY)
        assertEquals(HackRfProtocol.OPERACAKE_MODE_TIME, AntennaSwitch.MODE_TIME)
        assertEquals(HackRfProtocol.OPERACAKE_PA1, AntennaSwitch.PORT_A1)
        assertEquals(HackRfProtocol.OPERACAKE_PA4, AntennaSwitch.PORT_A4)
        assertEquals(HackRfProtocol.OPERACAKE_PB1, AntennaSwitch.PORT_B1)
        assertEquals(HackRfProtocol.OPERACAKE_PB4, AntennaSwitch.PORT_B4)

        assertEquals(HackRfProtocol.LED_USB, PanelLeds.USB)
        assertEquals(HackRfProtocol.LED_RX, PanelLeds.RX)
        assertEquals(HackRfProtocol.LED_TX, PanelLeds.TX)
        assertEquals(HackRfProtocol.CLKIN_SIGNAL_P1, ClockTrigger.CLKIN_P1)
        assertEquals(HackRfProtocol.CLKIN_SIGNAL_P22, ClockTrigger.CLKIN_P22)
        assertEquals(HackRfProtocol.P1_SIGNAL_TRIGGER_IN, ClockTrigger.P1_TRIGGER_IN)
        assertEquals(HackRfProtocol.P1_SIGNAL_AUX_CLK2, ClockTrigger.P1_AUX_CLK2)
        assertEquals(HackRfProtocol.P2_SIGNAL_CLK3, ClockTrigger.P2_CLK3)
        assertEquals(HackRfProtocol.P2_SIGNAL_TRIGGER_OUT, ClockTrigger.P2_TRIGGER_OUT)
    }

    @Test fun portRulesAndNamesAgree() {
        for (a in 0..7) {
            for (b in 0..7) {
                assertEquals(
                    "ports $a/$b",
                    HackRfProtocol.operacakePortsValid(a, b),
                    AntennaSwitch.portsValid(a, b),
                )
            }
            assertEquals(HackRfProtocol.operacakePortName(a), AntennaSwitch.portName(a))
        }
    }

    @Test fun explicitTuningBoundsAgree() {
        assertTrue(
            HackRfProtocol.freqExplicitValid(
                RfPath.IF_MIN_HZ, RfPath.LO_MIN_HZ, RfPath.LOW_PASS,
            ),
        )
        assertTrue(
            HackRfProtocol.freqExplicitValid(
                RfPath.IF_MAX_HZ, RfPath.LO_MAX_HZ, RfPath.LOW_PASS,
            ),
        )
        assertFalse(
            HackRfProtocol.freqExplicitValid(
                RfPath.IF_MIN_HZ - 1, RfPath.LO_MIN_HZ, RfPath.LOW_PASS,
            ),
        )
        assertFalse(
            HackRfProtocol.freqExplicitValid(
                RfPath.IF_DEFAULT_HZ, RfPath.LO_MIN_HZ - 1, RfPath.LOW_PASS,
            ),
        )
        // The default IF must itself be a legal one.
        assertTrue(
            HackRfProtocol.freqExplicitValid(
                RfPath.IF_DEFAULT_HZ, 0L, RfPath.BYPASS,
            ),
        )
    }

}
