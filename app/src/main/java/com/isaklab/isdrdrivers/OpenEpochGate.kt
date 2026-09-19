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

/** State-only linearisation gate for asynchronous radio OPEN callbacks. */
internal class OpenEpochGate {
    internal class Epoch internal constructor(internal val number: Long) {
        internal var phase = Phase.CONNECTING
        internal var lastStatus: Status? = null
        internal var activeCallbacks = 0
        internal var activeAttempts = 0
    }

    internal class Lease internal constructor(internal val epoch: Epoch)
    internal class AttemptLease internal constructor(internal val epoch: Epoch)

    internal data class Status(val connected: Boolean, val detail: String)

    internal sealed interface Begin {
        data class Started(val epoch: Epoch) : Begin
        data class SessionViolation(val retired: List<Epoch>) : Begin
        data object Closed : Begin
    }

    internal data class Handover(
        val accepted: Boolean,
        val retired: Epoch?,
    )

    internal sealed interface CommitStart {
        data object Ready : CommitStart
        data class Rejected(val detail: String, val lease: Lease) : CommitStart
        data object Stale : CommitStart
    }

    internal sealed interface CommitFinish {
        data class Published(val status: Status?, val lease: Lease) : CommitFinish
        data class Rejected(val detail: String, val lease: Lease) : CommitFinish
        data object Revoked : CommitFinish
    }

    internal data class Failure(val detail: String, val lease: Lease)

    internal data class Cancelled(
        val hadConnecting: Boolean,
        val hadCommitting: Boolean,
        val hadPublished: Boolean,
        val candidateStatus: Status?,
        val candidateTerminalInFlight: Boolean,
        val publishedTerminalStatus: Status?,
        val retired: List<Epoch>,
    )

    internal enum class Phase { CONNECTING, COMMITTING, PUBLISHED, TERMINATING, REVOKED }

    private val lock = Object()
    private var nextEpoch = 0L
    private var candidate: Epoch? = null
    private var published: Epoch? = null
    private var stopped = false

    /** Register the attempt synchronously, before its coroutine can race. */
    fun begin(): Begin = synchronized(lock) {
        if (stopped) return@synchronized Begin.Closed
        if (candidate != null) {
            val retired = listOfNotNull(candidate, published).distinct()
            revokeLocked(candidate)
            revokeLocked(published)
            candidate = null
            published = null
            return@synchronized Begin.SessionViolation(retired)
        }
        Begin.Started(Epoch(++nextEpoch).also { candidate = it })
    }

    /** Retire the old published epoch at the candidate hand-over boundary. */
    fun handover(epoch: Epoch): Handover = synchronized(lock) {
        if (!isConnectingLocked(epoch)) return@synchronized Handover(false, null)
        val retired = published
        revokeLocked(retired)
        published = null
        Handover(true, retired)
    }

    fun isConnecting(epoch: Epoch): Boolean = synchronized(lock) {
        isConnectingLocked(epoch)
    }

    /**
     * Hold candidate status. A published callback receives a lease and does
     * its (non-driver) delivery outside this monitor.
     */
    fun status(epoch: Epoch, connected: Boolean, detail: String): Lease? = synchronized(lock) {
        when {
            isCandidateLocked(epoch) -> {
                epoch.lastStatus = Status(connected, detail)
                null
            }
            isPublishedLocked(epoch) -> {
                epoch.lastStatus = Status(connected, detail)
                val lease = leaseLocked(epoch)
                if (!connected) revokeLocked(epoch)
                lease
            }
            else -> null
        }
    }

    /** Acquire a short delivery lease only for the current published epoch. */
    fun acquirePublished(epoch: Epoch): Lease? = synchronized(lock) {
        if (isPublishedLocked(epoch)) leaseLocked(epoch) else null
    }

    /**
     * Linearise a short ownership claim against cancel/shutdown. Once this
     * lease is granted, teardown waits for the claim to finish; if teardown
     * won first, no map/device side effect is allowed to begin.
     */
    fun acquireConnecting(epoch: Epoch): Lease? = synchronized(lock) {
        if (isConnectingLocked(epoch)) leaseLocked(epoch) else null
    }

    /** Hold the whole suspendable connect attempt until its final cleanup. */
    fun acquireAttempt(epoch: Epoch): AttemptLease? = synchronized(lock) {
        if (!isConnectingLocked(epoch)) return@synchronized null
        epoch.activeAttempts++
        AttemptLease(epoch)
    }

    fun releaseAttempt(lease: AttemptLease) {
        synchronized(lock) {
            check(lease.epoch.activeAttempts > 0) { "OPEN attempt lease released twice" }
            lease.epoch.activeAttempts--
            if (lease.epoch.activeAttempts == 0) lock.notifyAll()
        }
    }

    fun release(lease: Lease) {
        synchronized(lock) {
            check(lease.epoch.activeCallbacks > 0) { "OPEN callback lease released twice" }
            lease.epoch.activeCallbacks--
            if (lease.epoch.activeCallbacks == 0) lock.notifyAll()
        }
    }

    /** Finish a local terminal action and make a subsequent OPEN admissible. */
    fun completeTerminal(lease: Lease) {
        synchronized(lock) {
            val epoch = lease.epoch
            check(epoch.activeCallbacks > 0) { "OPEN terminal lease released twice" }
            epoch.activeCallbacks--
            if (candidate === epoch && epoch.phase == Phase.TERMINATING) {
                epoch.phase = Phase.REVOKED
                candidate = null
            }
            if (epoch.activeCallbacks == 0) lock.notifyAll()
        }
    }

    fun publishedEpoch(): Epoch? = synchronized(lock) {
        published?.takeIf(::isPublishedLocked)
    }

    fun isPublished(epoch: Epoch): Boolean = synchronized(lock) {
        isPublishedLocked(epoch)
    }

    /**
     * Claim the positive result immediately before the writer performs I/O.
     * From COMMITTING onward a concurrent CLOSE is a close-after-open: it may
     * revoke publication, but it must not manufacture a contradictory false
     * result after the writer already won the FIFO terminal.
     */
    fun startCommit(epoch: Epoch): CommitStart = synchronized(lock) {
        if (!isConnectingLocked(epoch)) return@synchronized CommitStart.Stale
        val retained = epoch.lastStatus
        if (retained != null && !retained.connected) {
            epoch.phase = Phase.TERMINATING
            return@synchronized CommitStart.Rejected(retained.detail, leaseLocked(epoch))
        }
        epoch.phase = Phase.COMMITTING
        CommitStart.Ready
    }

    /** Publish only after the positive result write returned successfully. */
    fun finishCommit(epoch: Epoch): CommitFinish = synchronized(lock) {
        if (!stopped && candidate === epoch && epoch.phase == Phase.COMMITTING) {
            val retained = epoch.lastStatus
            if (retained != null && !retained.connected) {
                epoch.phase = Phase.TERMINATING
                return@synchronized CommitFinish.Rejected(retained.detail, leaseLocked(epoch))
            }
            candidate = null
            epoch.phase = Phase.PUBLISHED
            published = epoch
            CommitFinish.Published(retained, leaseLocked(epoch))
        } else {
            CommitFinish.Revoked
        }
    }

    /** Resolve one pre-commit failure exactly once and return its cause. */
    fun fail(epoch: Epoch, explicitDetail: String?, fallbackDetail: String): Failure? =
        synchronized(lock) {
            if (!isCandidateLocked(epoch)) return@synchronized null
            val detail = explicitDetail?.takeIf { it.isNotBlank() }
                ?: epoch.lastStatus?.takeUnless { it.connected }?.detail
                ?: fallbackDetail
            epoch.lastStatus = Status(false, detail)
            epoch.phase = Phase.TERMINATING
            Failure(detail, leaseLocked(epoch))
        }

    /** Revoke before CMD_CLOSE performs any driver teardown. */
    fun cancel(): Cancelled = synchronized(lock) {
        val c = candidate
        val p = published
        val state = Cancelled(
            hadConnecting = c?.phase == Phase.CONNECTING,
            hadCommitting = c?.phase == Phase.COMMITTING,
            hadPublished = p?.phase == Phase.PUBLISHED,
            candidateStatus = c?.lastStatus,
            candidateTerminalInFlight = c?.phase == Phase.TERMINATING,
            publishedTerminalStatus = p?.lastStatus?.takeIf {
                p.phase == Phase.REVOKED && !it.connected
            },
            retired = listOfNotNull(c, p).distinct(),
        )
        revokeLocked(c)
        revokeLocked(p)
        candidate = null
        published = null
        state
    }

    /** Permanently revoke this session; false means another thread won. */
    fun shutdown(): Pair<Boolean, List<Epoch>> = synchronized(lock) {
        if (stopped) return@synchronized false to emptyList()
        stopped = true
        val retired = listOfNotNull(candidate, published).distinct()
        revokeLocked(candidate)
        revokeLocked(published)
        candidate = null
        published = null
        true to retired
    }

    /**
     * Wait only for callbacks that already acquired a publication lease.
     * The monitor is released by wait(); no driver call or socket I/O occurs
     * under it, so teardown cannot deadlock a client's callback thread.
     */
    fun awaitQuiescent(epochs: Collection<Epoch>) {
        if (epochs.isEmpty()) return
        synchronized(lock) {
            while (epochs.any { it.activeCallbacks != 0 }) lock.wait()
        }
    }

    /** Wait for suspendable connect attempts after requesting driver teardown. */
    fun awaitAttempts(epochs: Collection<Epoch>) {
        if (epochs.isEmpty()) return
        synchronized(lock) {
            while (epochs.any { it.activeAttempts != 0 }) lock.wait()
        }
    }

    fun isRunning(): Boolean = synchronized(lock) { !stopped }

    private fun leaseLocked(epoch: Epoch): Lease {
        epoch.activeCallbacks++
        return Lease(epoch)
    }

    private fun isConnectingLocked(epoch: Epoch): Boolean =
        !stopped && candidate === epoch && epoch.phase == Phase.CONNECTING

    private fun isCandidateLocked(epoch: Epoch): Boolean =
        !stopped && candidate === epoch &&
            (epoch.phase == Phase.CONNECTING || epoch.phase == Phase.COMMITTING)

    private fun isPublishedLocked(epoch: Epoch): Boolean =
        !stopped && published === epoch && epoch.phase == Phase.PUBLISHED

    private fun revokeLocked(epoch: Epoch?) {
        epoch?.phase = Phase.REVOKED
    }
}
