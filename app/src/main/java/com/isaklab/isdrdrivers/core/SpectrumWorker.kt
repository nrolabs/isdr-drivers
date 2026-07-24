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
 */
class SpectrumWorker(private val fft: FFTProcessor) {

    @Volatile var latest: FloatArray? = null
        private set

    private class Job(val iq: FloatArray, val pairs: Int)

    private val queue = ArrayBlockingQueue<Job>(1)
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

    /** Hand a COPY of the block to the worker (drop-oldest if it's busy). */
    fun submit(block: FloatArray, pairs: Int) {
        if (!running) return
        val copy = block.copyOf()
        // Latest-wins: clear any pending stale job, enqueue this one.
        queue.clear()
        queue.offer(Job(copy, pairs))
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
