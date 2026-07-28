/*
 * isdr-drivers - GPL driver host for the iSDR app
 *
 * Copyright (C) 2026 Isak Ruas <isakruas@gmail.com>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 */
package com.isaklab.isdrdrivers.core

/**
 * When a keyed radio must be unkeyed because the far end went silent.
 *
 * A link can die without anyone being told. A phone that loses its network
 * mid-over sends no FIN: the relay holds the spliced pair for its whole idle
 * timeout, the station stays blocked on a read, and nothing tears the session
 * down — so the radio can sit KEYED for minutes, into an antenna, with no
 * operator present. That is a burnt PA and an occupied channel.
 *
 * Every path that keys PTT streams transmit samples continuously — voice,
 * digital, and the VNA sweep's carrier pump — so their absence is a sound
 * proxy for "the far end is gone".
 *
 * Kept as a pure decision so the rule can be tested without a radio, a
 * socket or a clock: the plumbing around it cannot be. Register access triggered 
 * by the watchdog timeout must still be safely serialized.
 */
object TxWatchdogPolicy {

    /**
     * Silence that means the far end is gone rather than merely late.
     *
     * Not sized from speech: a pause in a QSO keeps the PTT down and the
     * transmit chain keeps submitting blocks, which simply carry silence, so
     * the stream never stops for that reason. What this has to survive is a
     * CONGESTED uplink, where blocks bunch up behind a stall and arrive in
     * bursts — cutting an operator off mid-over because the link hiccuped is
     * worse than a carrier that lingers a few more seconds.
     *
     * The number is an operating decision, not a derivation. Fifteen seconds
     * is generous against jitter and still bounds an unattended carrier to
     * twenty times less than the relay's idle timeout, which is what used to
     * hold a keyed radio for minutes.
     */
    const val SILENCE_MS = 15_000L

    /** Sentinel for "no transmit sample has arrived since key-up". */
    const val NOT_ARMED = 0L

    /**
     * @param pttOn whether the radio is currently keyed
     * @param lastTxIqMs monotonic time of the last transmit block, or
     *   [NOT_ARMED] when none has arrived since key-up
     * @param nowMs current monotonic time
     */
    fun shouldUnkey(pttOn: Boolean, lastTxIqMs: Long, nowMs: Long): Boolean {
        // Arms on the first sample, never on key-up itself: a mode that keys
        // without a stream of its own must not be cut short by this.
        if (!pttOn || lastTxIqMs == NOT_ARMED) return false
        return nowMs - lastTxIqMs >= SILENCE_MS
    }
}
