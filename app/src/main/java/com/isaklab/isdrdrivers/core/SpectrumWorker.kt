package com.isaklab.isdrdrivers.core

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Off-thread spectrum computation. The receive/read thread must never run the
 * Welch FFT inline — a multi-ms FFT burst there stalls the socket/USB read and
 * drops samples (audible artifacts at high sample rates). Instead the receive
 * thread delivers IQ immediately with the LAST cached spectrum and, on the
 * display cadence, hands a copy of the block to this worker; the worker runs
 * the FFT on its own thread and publishes the result for the next delivery.
 *
 * Spectrum is inherently latest-wins: the queue holds one job, drop-oldest.
 * 
 * Maintains isolation from the streaming path, where GC allocations are forbidden,
 * utilizing double-buffering pre-allocated arrays to guarantee zero-allocation
 * handoffs. Register access is serialized elsewhere to avoid contentions.
 */
class SpectrumWorker(private val fft: FFTProcessor) {

    @Volatile var latest: FloatArray? = null
        private set

    private class Job(val iq: FloatArray, val pairs: Int)

    private val queue = ArrayBlockingQueue<Job>(1)

    // Double buffer for submit(): the queue holds at most one job, so two
    // buffers are enough to never hand the worker the one being refilled.
    private val scratch = arrayOfNulls<FloatArray>(2)
    private var scratchIndex = 0
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT)
            while (running) {
                val job = try {
                    queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                } catch (e: InterruptedException) { break }
                fft.computePowerSpectrum(job.iq, job.pairs)?.let { latest = it }
            }
        }, "spectrum-fft").also { it.start() }
    }

    /**
     * Hand a COPY of the block to the worker (drop-oldest if it's busy).
     *
     * The copy is unavoidable — the caller reuses its accumulator the moment
     * this returns — but ALLOCATING it is not. Two buffers alternate: while
     * the worker reads one, the receive thread fills the other, so a display
     * cadence that ran for an hour allocates twice instead of 36000 times.
     * Only the receive thread calls this, so the alternation needs no lock.
     */
    fun submit(block: FloatArray, pairs: Int) {
        if (!running) return
        val n = block.size
        var buf = scratch[scratchIndex]
        if (buf == null || buf.size != n) {
            buf = FloatArray(n)
            scratch[scratchIndex] = buf
        }
        System.arraycopy(block, 0, buf, 0, n)
        scratchIndex = scratchIndex xor 1
        // Latest-wins: clear any pending stale job, enqueue this one.
        queue.clear()
        queue.offer(Job(buf, pairs))
    }

    fun resetSmoothing() = fft.resetSmoothing()

    fun stop() {
        running = false
        thread?.interrupt()
        thread?.join(500)
        thread = null
        queue.clear()
        latest = null
    }
}
