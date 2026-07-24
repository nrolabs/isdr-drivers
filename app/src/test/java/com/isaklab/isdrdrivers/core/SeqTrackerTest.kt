package com.isaklab.isdrdrivers.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** Gap/reorder policy shared by the G2 (32-bit), HL2 (32-bit) and Flex (4-bit) streams. */
class SeqTrackerTest {

    @Test fun inOrderStreamCountsNothing() {
        val t = SeqTracker()
        for (s in 0L..999L) assertEquals(0L, t.advance(s))
        assertEquals(0L, t.gapEvents)
        assertEquals(0L, t.lostPackets)
    }

    @Test fun gapReportsMissingCountAndIncrementsCounters() {
        val t = SeqTracker()
        t.advance(10)
        assertEquals(0L, t.advance(11))
        assertEquals(3L, t.advance(15))       // 12,13,14 lost
        assertEquals(1L, t.gapEvents)
        assertEquals(3L, t.lostPackets)
        assertEquals(0L, t.advance(16))       // resynced
    }

    @Test fun lateDuplicateIsDropped() {
        val t = SeqTracker()
        t.advance(100); t.advance(101)
        assertEquals(-1L, t.advance(100))     // late/reordered: drop
        assertEquals(1L, t.gapEvents)
        assertEquals(0L, t.advance(102))      // stream position unaffected
    }

    @Test fun wrap32BitIsSeamless() {
        val t = SeqTracker()
        t.advance(0xFFFF_FFFEL)
        assertEquals(0L, t.advance(0xFFFF_FFFFL))
        assertEquals(0L, t.advance(0L))       // u32 wrap, no false gap
        assertEquals(0L, t.gapEvents)
    }

    @Test fun gapAcrossWrapIsCounted() {
        val t = SeqTracker()
        t.advance(0xFFFF_FFFEL)
        assertEquals(2L, t.advance(1L))       // 0xFFFFFFFF and 0 lost
        assertEquals(1L, t.gapEvents)
    }

    @Test fun counterRestartResyncsWithoutConcealment() {
        val t = SeqTracker()
        t.advance(500_000)
        assertEquals(0L, t.advance(0L))       // radio rebooted: resync
        assertEquals(1L, t.gapEvents)
        assertEquals(0L, t.advance(1L))
    }

    @Test fun fourBitVitaCounterWrapsAndGaps() {
        val t = SeqTracker(modulo = 16)
        for (s in 0L..15L) assertEquals(0L, t.advance(s))
        assertEquals(0L, t.advance(0L))       // 15 -> 0 wrap
        assertEquals(1L, t.advance(2L))       // packet 1 lost
        assertEquals(1L, t.gapEvents)
        assertEquals(1L, t.lostPackets)
    }

    @Test fun resetForgetsPositionKeepsCounters() {
        val t = SeqTracker()
        t.advance(5); t.advance(9)            // gap of 3
        t.reset()
        assertEquals(0L, t.advance(1234))     // new stream accepted cleanly
        assertEquals(1L, t.gapEvents)
    }
}
