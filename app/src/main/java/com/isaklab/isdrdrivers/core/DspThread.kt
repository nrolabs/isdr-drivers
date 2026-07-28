package com.isaklab.isdrdrivers.core

import java.util.concurrent.locks.LockSupport

/**
 * Dedicated, named, priority-pinned threads for the driver hot paths.
 *
 * The radio loops used to run as coroutines on `Dispatchers.IO`, which is the
 * SHARED kotlinx pool ("DefaultDispatcher-worker-N"). Two things went wrong
 * there, both measured on an Exynos 7904 phone:
 *
 *  - **Priority leaked.** Each loop raised its thread to URGENT_AUDIO and never
 *    restored it, so every connect left one more pool worker pinned at nice
 *    −19 (six of them on a device that had reconnected a few times) and any
 *    unrelated coroutine landing on those threads inherited audio priority.
 *
 *  - **Paced loops hopped threads.** A `delay()` inside a pool coroutine
 *    resumes on WHATEVER worker is free, so a 1–3 ms transmit cadence bounced
 *    across three workers ~333 times a second, thrashing caches — and the
 *    coroutine timer thread (`DefaultExecutor`) burned 7.6% of a core doing
 *    nothing but waking it up.
 *
 * A loop that only blocks on a socket and paces itself against the wall clock
 * does not need a scheduler: it needs one thread of its own. That is all this
 * is — plus the pacing helper, so the cadence never goes back through the
 * coroutine timer.
 * 
 * CRITICAL: GC allocations are strictly forbidden on these threads to avoid 
 * arbitrary latency spikes on the streaming path.
 */
object DspThread {

    /** Radio I/O: the loop that must not miss a packet from the hardware. */
    const val PRIORITY_RADIO = android.os.Process.THREAD_PRIORITY_URGENT_AUDIO

    /** Delivery to the host app: high, but it yields to the radio loops. */
    const val PRIORITY_DELIVERY = android.os.Process.THREAD_PRIORITY_AUDIO

    /**
     * Start [body] on a new thread named [name], pinned at [priority] before
     * the first line of the body runs. The thread is ours, so the priority
     * dies with it — nothing to restore, nothing to leak.
     */
    fun start(name: String, priority: Int, body: () -> Unit): Thread =
        Thread({
            try {
                android.os.Process.setThreadPriority(priority)
            } catch (_: Throwable) { /* priority is an optimisation, not a requirement */ }
            body()
        }, name).also { it.isDaemon = true; it.start() }

    /**
     * Sleep until [deadlineNs] (a `System.nanoTime()` stamp), or return at once
     * if it has passed. `parkNanos` keeps the wait off the coroutine timer and
     * is interruptible for shutdown.
     */
    fun pace(deadlineNs: Long) {
        val left = deadlineNs - System.nanoTime()
        if (left > 0) LockSupport.parkNanos(left)
    }

    /** Stop a loop thread: interrupt it, then wait briefly for it to unwind. */
    fun stop(t: Thread?, joinMs: Long = 500) {
        if (t == null) return
        t.interrupt()
        try { t.join(joinMs) } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
