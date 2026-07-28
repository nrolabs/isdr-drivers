package com.isaklab.isdrdrivers.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that decides whether an unattended radio stays on the air.
 *
 * Both directions matter and they pull against each other: failing to unkey
 * burns a PA and occupies a channel, and unkeying too eagerly cuts an
 * operator off mid-sentence. Neither shows up as an error anywhere.
 */
class TxWatchdogPolicyTest {

    private val T = TxWatchdogPolicy.SILENCE_MS

    @Test fun an_unkeyed_radio_is_never_touched() {
        assertFalse(TxWatchdogPolicy.shouldUnkey(pttOn = false, lastTxIqMs = 1, nowMs = 1_000_000))
    }

    @Test fun keying_alone_does_not_arm_it() {
        // The VNA sweep keys, engages the band filter and only then starts
        // pumping its carrier. Arming on key-up would race that setup and
        // unkey a sweep that was about to be perfectly legitimate.
        assertFalse(
            TxWatchdogPolicy.shouldUnkey(
                pttOn = true,
                lastTxIqMs = TxWatchdogPolicy.NOT_ARMED,
                nowMs = 10 * T,
            ),
        )
    }

    @Test fun a_stream_that_keeps_flowing_is_left_alone() {
        val now = 500_000L
        for (gap in longArrayOf(0, 100, 500, T - 1)) {
            assertFalse(
                "a $gap ms gap is normal jitter, not a dead link",
                TxWatchdogPolicy.shouldUnkey(true, now - gap, now),
            )
        }
    }

    @Test fun silence_past_the_limit_unkeys() {
        val now = 500_000L
        assertTrue(TxWatchdogPolicy.shouldUnkey(true, now - T, now))
        assertTrue(TxWatchdogPolicy.shouldUnkey(true, now - 60_000, now))
    }

    @Test fun the_limit_is_seconds_not_minutes() {
        // The exposure must stay far below the relay's idle timeout, which is
        // what used to hold a dead session — and a keyed radio with it — for
        // minutes. The lower bound guards the opposite mistake: a congested
        // uplink delivers transmit blocks in bursts, and a tight limit would
        // cut a live over.
        assertTrue("an unattended carrier must be bounded well under a minute", T <= 30_000)
        assertTrue("but not so short that a bursty uplink trips it", T >= 10_000)
    }

    @Test fun a_pause_in_a_qso_does_not_trip_it() {
        // Holding the PTT through a pause keeps the transmit chain running:
        // the blocks still arrive, they just carry silence. Several seconds
        // of bunched delivery must still read as alive.
        val now = 500_000L
        for (gap in longArrayOf(1_000, 3_000, 8_000, T - 1)) {
            assertFalse(
                "a $gap ms gap is a congested uplink, not a dead one",
                TxWatchdogPolicy.shouldUnkey(true, now - gap, now),
            )
        }
    }

    @Test fun a_clock_that_does_not_advance_never_trips_it() {
        // elapsedRealtime is monotonic, but a stalled reading must fail SAFE
        // in the direction of not cutting a live transmission.
        assertFalse(TxWatchdogPolicy.shouldUnkey(true, 1_000, 1_000))
    }
}
