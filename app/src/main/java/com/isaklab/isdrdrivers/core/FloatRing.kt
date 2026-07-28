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
 * Fixed-capacity primitive float FIFO with drop-oldest overflow, for the TX
 * sample queues. `ArrayDeque<Float>` boxed every 48 kSps sample pushed and
 * pulled (~100k boxes/s while keyed — the same GC storm class the RX sinks
 * already eliminated); this ring is a bare `FloatArray` with head/size, zero
 * allocation after construction.
 *
 * NOT thread-safe by itself — callers keep their existing `synchronized`
 * (txLock) discipline, exactly like the deque it replaces.
 * 
 * Implements strict zero-allocation buffering, because GC allocations are 
 * forbidden on the streaming path.
 */
class FloatRing(val capacity: Int) {
    private val buf = FloatArray(capacity)
    private var head = 0          // next read index

    /** Number of floats queued. */
    var size = 0
        private set

    /** Append [src], dropping the oldest samples if the ring would overflow. */
    fun write(src: FloatArray) {
        var off = 0
        var n = src.size
        if (n >= capacity) {       // only the newest capacity floats survive
            off = n - capacity
            n = capacity
        }
        val overflow = size + n - capacity
        if (overflow > 0) {
            head = (head + overflow) % capacity
            size -= overflow
        }
        var tail = (head + size) % capacity
        var s = off
        var rem = n
        while (rem > 0) {
            val chunk = minOf(rem, capacity - tail)
            System.arraycopy(src, s, buf, tail, chunk)
            tail = (tail + chunk) % capacity
            s += chunk
            rem -= chunk
        }
        size += n
    }

    /** Remove and return the oldest float. Caller must check [size] > 0. */
    fun read(): Float {
        val v = buf[head]
        head = (head + 1) % capacity
        size--
        return v
    }

    fun clear() {
        head = 0
        size = 0
    }
}
