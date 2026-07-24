package com.isaklab.isdrdrivers.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** The TX queues' primitive ring must keep exact FIFO + drop-oldest semantics. */
class FloatRingTest {

    @Test fun fifoOrderPreserved() {
        val r = FloatRing(8)
        r.write(floatArrayOf(1f, 2f, 3f))
        assertEquals(3, r.size)
        assertEquals(1f, r.read())
        assertEquals(2f, r.read())
        r.write(floatArrayOf(4f))
        assertEquals(3f, r.read())
        assertEquals(4f, r.read())
        assertEquals(0, r.size)
    }

    @Test fun overflowDropsOldest() {
        val r = FloatRing(4)
        r.write(floatArrayOf(1f, 2f, 3f, 4f))
        r.write(floatArrayOf(5f, 6f))          // 1,2 dropped
        assertEquals(4, r.size)
        assertEquals(3f, r.read())
        assertEquals(4f, r.read())
        assertEquals(5f, r.read())
        assertEquals(6f, r.read())
    }

    @Test fun writeLargerThanCapacityKeepsNewestTail() {
        val r = FloatRing(4)
        r.write(FloatArray(10) { it.toFloat() })   // only 6,7,8,9 survive
        assertEquals(4, r.size)
        assertEquals(6f, r.read())
        assertEquals(7f, r.read())
        assertEquals(8f, r.read())
        assertEquals(9f, r.read())
    }

    @Test fun wrapAroundManyTimesStaysConsistent() {
        // Odd capacity + 2-in/2-out per turn forces the indices to wrap on
        // every lap without ever overflowing — pure FIFO must survive.
        val r = FloatRing(7)
        var next = 0f
        var expect = 0f
        repeat(1000) {
            r.write(floatArrayOf(next, next + 1))
            next += 2
            assertEquals(expect, r.read())
            assertEquals(expect + 1, r.read())
            expect += 2
        }
        assertEquals(0, r.size)
    }

    @Test fun clearEmpties() {
        val r = FloatRing(4)
        r.write(floatArrayOf(1f, 2f))
        r.clear()
        assertEquals(0, r.size)
        r.write(floatArrayOf(9f))
        assertEquals(9f, r.read())
    }
}
