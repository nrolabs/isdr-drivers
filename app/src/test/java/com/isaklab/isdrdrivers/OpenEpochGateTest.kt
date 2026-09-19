/*
 * isdr-drivers - GPL driver host for the iSDR app
 *
 * Copyright (C) 2026 Isak Ruas <isakruas@gmail.com>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 */
package com.isaklab.isdrdrivers

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenEpochGateTest {
    @Test
    fun `result precedes publication status state and data`() {
        val gate = OpenEpochGate()
        val epoch = (gate.begin() as OpenEpochGate.Begin.Started).epoch
        val events = mutableListOf<String>()

        assertNull(gate.status(epoch, true, "Connected"))
        assertNull(gate.acquirePublished(epoch))
        assertEquals(OpenEpochGate.CommitStart.Ready, gate.startCommit(epoch))
        events += "open-result"
        val finish = gate.finishCommit(epoch) as OpenEpochGate.CommitFinish.Published
        events += "status:${finish.status?.detail}"
        events += "initial-state"
        gate.release(finish.lease)
        val lease = checkNotNull(gate.acquirePublished(epoch))
        try {
            events += "data"
        } finally {
            gate.release(lease)
        }

        assertEquals(
            listOf("open-result", "status:Connected", "initial-state", "data"),
            events,
        )
    }

    @Test
    fun `close while connecting revokes original attempt once`() {
        val gate = OpenEpochGate()
        val epoch = (gate.begin() as OpenEpochGate.Begin.Started).epoch
        val cancelled = gate.cancel()

        assertTrue(cancelled.hadConnecting)
        assertFalse(cancelled.hadCommitting)
        assertEquals(OpenEpochGate.CommitStart.Stale, gate.startCommit(epoch))
        assertNull(gate.fail(epoch, "duplicate", "fallback"))
        assertNull(gate.acquirePublished(epoch))
    }

    @Test
    fun `callbacks from A cannot leak after handover to B`() {
        val gate = OpenEpochGate()
        val a = publish(gate, "A")
        val aBefore = checkNotNull(gate.acquirePublished(a))
        gate.release(aBefore)

        val b = (gate.begin() as OpenEpochGate.Begin.Started).epoch
        // Read-only preflight deliberately leaves A live.
        val aDuringPreflight = checkNotNull(gate.acquirePublished(a))
        gate.release(aDuringPreflight)
        val handover = gate.handover(b)
        assertTrue(handover.accepted)
        assertEquals(a, handover.retired)
        assertNull(gate.acquirePublished(a))
        assertNull(gate.acquirePublished(b))

        assertNull(gate.status(b, true, "B"))
        assertEquals(OpenEpochGate.CommitStart.Ready, gate.startCommit(b))
        val publishedB = gate.finishCommit(b) as OpenEpochGate.CommitFinish.Published
        gate.release(publishedB.lease)
        val bLease = checkNotNull(gate.acquirePublished(b))
        gate.release(bLease)
    }

    @Test
    fun `second pending open is an uncorrelatable session violation`() {
        val gate = OpenEpochGate()
        val first = (gate.begin() as OpenEpochGate.Begin.Started).epoch
        val violation = gate.begin() as OpenEpochGate.Begin.SessionViolation
        assertEquals(listOf(first), violation.retired)
        assertEquals(OpenEpochGate.CommitStart.Stale, gate.startCommit(first))
        assertNull(gate.acquirePublished(first))
    }

    @Test(timeout = 2_000)
    fun `close does not wait for writer blocked in committing`() {
        val gate = OpenEpochGate()
        val epoch = (gate.begin() as OpenEpochGate.Begin.Started).epoch
        gate.status(epoch, true, "Connected")
        val writerClaimed = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        var finish: OpenEpochGate.CommitFinish? = null
        val writer = thread {
            assertEquals(OpenEpochGate.CommitStart.Ready, gate.startCommit(epoch))
            writerClaimed.countDown()
            assertTrue(releaseWrite.await(1, TimeUnit.SECONDS))
            finish = gate.finishCommit(epoch)
        }

        assertTrue(writerClaimed.await(1, TimeUnit.SECONDS))
        val cancelled = gate.cancel()
        assertTrue(cancelled.hadCommitting)
        releaseWrite.countDown()
        writer.join()
        assertEquals(OpenEpochGate.CommitFinish.Revoked, finish)
    }

    @Test(timeout = 2_000)
    fun `session shutdown does not wait for writer blocked in committing`() {
        val gate = OpenEpochGate()
        val epoch = (gate.begin() as OpenEpochGate.Begin.Started).epoch
        gate.status(epoch, true, "Connected")
        assertEquals(OpenEpochGate.CommitStart.Ready, gate.startCommit(epoch))

        val shutdown = gate.shutdown()
        assertTrue(shutdown.first)
        assertEquals(listOf(epoch), shutdown.second)
        assertEquals(OpenEpochGate.CommitFinish.Revoked, gate.finishCommit(epoch))
        assertEquals(OpenEpochGate.Begin.Closed, gate.begin())
    }

    @Test(timeout = 2_000)
    fun `revocation waits only for already admitted callback lease`() {
        val gate = OpenEpochGate()
        val epoch = publish(gate, "Connected")
        val lease = checkNotNull(gate.acquirePublished(epoch))
        val quiescent = CountDownLatch(1)

        val cancelled = gate.cancel()
        thread {
            gate.awaitQuiescent(cancelled.retired)
            quiescent.countDown()
        }
        assertFalse(quiescent.await(50, TimeUnit.MILLISECONDS))
        gate.release(lease)
        assertTrue(quiescent.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun `disconnect before writer claim retains the specific failure`() {
        val gate = OpenEpochGate()
        val epoch = (gate.begin() as OpenEpochGate.Begin.Started).epoch
        gate.status(epoch, true, "Connected")
        gate.status(epoch, false, "link lost")

        val rejected = gate.startCommit(epoch) as OpenEpochGate.CommitStart.Rejected
        assertEquals("link lost", rejected.detail)
        gate.completeTerminal(rejected.lease)
        assertNull(gate.acquirePublished(epoch))
    }

    @Test
    fun `disconnect during result write rejects publication and all data`() {
        val gate = OpenEpochGate()
        val epoch = (gate.begin() as OpenEpochGate.Begin.Started).epoch
        gate.status(epoch, true, "Connected")
        assertEquals(OpenEpochGate.CommitStart.Ready, gate.startCommit(epoch))

        gate.status(epoch, false, "link lost during OPEN write")
        val rejected = gate.finishCommit(epoch) as OpenEpochGate.CommitFinish.Rejected
        assertEquals("link lost during OPEN write", rejected.detail)
        gate.completeTerminal(rejected.lease)
        assertNull(gate.acquirePublished(epoch))
    }

    @Test
    fun `published disconnect revokes data before status lease is delivered`() {
        val gate = OpenEpochGate()
        val epoch = publish(gate, "Connected")

        val statusLease = checkNotNull(gate.status(epoch, false, "link lost"))
        assertNull(gate.acquirePublished(epoch))
        gate.release(statusLease)
        assertNull(gate.acquirePublished(epoch))
    }

    @Test(timeout = 2_000)
    fun `connecting ownership lease makes cancellation wait for claim`() {
        val gate = OpenEpochGate()
        val epoch = (gate.begin() as OpenEpochGate.Begin.Started).epoch
        val claim = checkNotNull(gate.acquireConnecting(epoch))
        val cancelled = gate.cancel()
        val quiescent = CountDownLatch(1)

        thread {
            gate.awaitQuiescent(cancelled.retired)
            quiescent.countDown()
        }
        assertFalse(quiescent.await(50, TimeUnit.MILLISECONDS))
        gate.release(claim)
        assertTrue(quiescent.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun `cancel preserves an already published terminal cause`() {
        val gate = OpenEpochGate()
        val epoch = publish(gate, "Connected")
        val terminal = checkNotNull(gate.status(epoch, false, "RF link lost"))

        val cancelled = gate.cancel()
        assertEquals(
            OpenEpochGate.Status(false, "RF link lost"),
            cancelled.publishedTerminalStatus,
        )
        gate.release(terminal)
    }

    private fun publish(gate: OpenEpochGate, detail: String): OpenEpochGate.Epoch {
        val epoch = (gate.begin() as OpenEpochGate.Begin.Started).epoch
        gate.status(epoch, true, detail)
        assertEquals(OpenEpochGate.CommitStart.Ready, gate.startCommit(epoch))
        val published = gate.finishCommit(epoch) as OpenEpochGate.CommitFinish.Published
        gate.release(published.lease)
        return epoch
    }
}
