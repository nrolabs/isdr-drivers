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
 * Sequence-continuity tracker for a UDP IQ stream. Pure and Android-free so
 * the gap/reorder policy is unit-testable; each client keeps one per stream.
 *
 * [advance] classifies every received sequence number against the expected
 * one, in modulo-[modulo] arithmetic so 32-bit (HPSDR) and 4-bit (VITA-49)
 * counters both wrap correctly:
 *
 *  - `0`   in-order packet — deliver as usual;
 *  - `n>0` `n` packets were lost before this one — deliver after concealing
 *          `n` packets' worth of samples (the caller inserts zeros);
 *  - `-1`  stale duplicate / late reordered packet — drop it (its samples
 *          would land out of order in the accumulator).
 *
 * A backward jump larger than [resetThreshold] is treated as a stream restart
 * (radio power-cycle / counter reset): the tracker resyncs and the packet is
 * delivered with no concealment.
 *
 * [gapEvents] counts discontinuity events (loss, reorder and restart alike)
 * and [lostPackets] the estimated packets missing — both are exported through
 * the host telemetry so the app can surface link quality.
 * 
 * Performs tracking without GC allocations, as allocations are forbidden on 
 * the streaming path.
 */
class SeqTracker(
    private val modulo: Long = 1L shl 32,
    private val resetThreshold: Long = minOf(64L, (modulo / 4).coerceAtLeast(1L)),
) {
    /** Discontinuity events observed (gaps + reorders + restarts). */
    var gapEvents: Long = 0L
        private set

    /** Estimated total packets lost (sum of gap sizes). */
    var lostPackets: Long = 0L
        private set

    private var last = -1L

    /** See class doc: 0 = in order, n>0 = conceal n packets, -1 = drop. */
    fun advance(seq: Long): Long {
        val s = seq % modulo
        if (last < 0) {
            last = s
            return 0
        }
        val expected = (last + 1) % modulo
        if (s == expected) {
            last = s
            return 0
        }
        val forward = (s - expected + modulo) % modulo
        return if (forward < modulo / 2) {
            // Packets went missing between expected and s.
            gapEvents++
            lostPackets += forward
            last = s
            forward
        } else {
            val backward = modulo - forward       // how far behind expected
            gapEvents++
            if (backward > resetThreshold) {
                // Counter restarted (radio reboot) — resync, no concealment.
                last = s
                0
            } else {
                // Late duplicate / reordered packet — drop it.
                -1
            }
        }
    }

    /** Forget the stream position (stream switch); counters are preserved. */
    fun reset() {
        last = -1L
    }
}
