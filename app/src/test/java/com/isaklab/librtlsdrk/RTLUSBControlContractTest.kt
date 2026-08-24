/*
 * librtlsdrk - Kotlin driver for RTL-SDR receivers
 * Copyright (C) 2026 Isak Ruas <isakruas@gmail.com>
 */
package com.isaklab.librtlsdrk

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RTLUSBControlContractTest {

    @Test fun sampleRateMustBeAnExactAdvertisedChoice() {
        assertNull(rtlUsbSampleRateError(2_048_000))
        assertNull(rtlUsbSampleRateError(1_024_000))
        assertNotNull(rtlUsbSampleRateError(2_000_001))
    }

    @Test fun tunerGainMustMatchOneExactHardwareTableEntry() {
        val gains = intArrayOf(-99, -40, 71, 179, 192)
        assertNull(rtlUsbGainError(gains, 179))
        assertNotNull(rtlUsbGainError(gains, 180))
        assertNotNull(rtlUsbGainError(gains, 500))
    }
}
