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
package com.isaklab.isdrdrivers

import android.content.Context
import android.util.Log
import com.isaklab.isdrdrivers.core.DspThread
import com.isaklab.isdrdrivers.core.RadioClient
import com.isaklab.isdrdrivers.core.AntennaPowerCapable
import com.isaklab.isdrdrivers.core.AnalogFilterCapable
import com.isaklab.isdrdrivers.core.TransmitCapable
import com.isaklab.isdrdrivers.core.TxDriveCapable
import com.isaklab.isdrdrivers.core.TxTimingCapable
import com.isaklab.isdrproto.Frame
import com.isaklab.isdrproto.Frames
import com.isaklab.isdrproto.DriverProto
import com.isaklab.isdrproto.IqCodec
import com.isaklab.isdrproto.SpectrumCodec
import com.isaklab.isdrproto.RadioTelemetry
import com.isaklab.isdrproto.getBool
import com.isaklab.isdrproto.getFloats
import com.isaklab.isdrproto.getUtf
import com.isaklab.libg2sdrk.G2Client
import com.isaklab.libg2sdrk.G2Protocol
import com.isaklab.libhackrfk.HackRfClient
import com.isaklab.libhl2sdrk.Hl2Client
import com.isaklab.libhl2sdrk.Hl2Protocol
import com.isaklab.librtlsdrk.RTLCommand
import com.isaklab.librtlsdrk.RTLTCPClient
import com.isaklab.librtlsdrk.RTLUSBClient
import java.nio.ByteBuffer
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * One app connection: commands in, driver data/status/telemetry out. Holds at
 * most one open radio; a second CMD_OPEN closes the previous one first. The
 * driver host is a pure transport — every command maps 1:1 onto a driver call, all
 * radio policy (gain scales, drive mapping, band logic) lives in the app.
 *
 * CRITICAL PERFORMANCE CONTRACT:
 * - GC allocations are forbidden on the streaming path (IQ data RX/TX).
 * - All hardware register access is serialized.
 * - Enqueuing and buffering reuse memory through pooling.
 */
class DriverSession(
    private val context: Context,
    private val socket: Socket,
    /**
     * Per-connection secret the client must present in CMD_AUTH before any
     * device command is honoured. Required for every session (loopback too):
     * other local apps can reach 127.0.0.1:PORT and must not be able to open
     * the radio or key the transmitter.
     */
    private val requiredToken: String?,
    private val onClosed: (DriverSession) -> Unit,
) {
    companion object {
        private const val TAG = "DriverSession"

        /** Max queued outbound frames (~4.5 MB worst case of EV_DATA blocks). */
        private const val MAX_OUT = 64

        /** Encoded-frame buffers kept for reuse (see [bufPool]). */
        private const val MAX_POOLED = 80

        private val EMPTY_FLOATS = FloatArray(0)

        /**
         * Inbound payload ceiling before CMD_AUTH succeeds. A token frame is
         * a u16-prefixed string; 4 kB is already far more than one needs, and
         * an unauthenticated peer gets no larger allocation than that.
         */
        private const val PRE_AUTH_PAYLOAD = 4096

        /**
         * Inbound payload ceiling once authenticated.
         *
         * Sized from the frames that actually arrive, not from the wire
         * format's absolute limit:
         *
         *  - CMD_TX_IQ, the only bulk inbound opcode, carries i32 count plus
         *    raw float32 interleaved IQ at the fixed 48 kSps TX rate. The app
         *    submits one 1024-sample audio block at a time, i.e. 2048 floats:
         *    4 + 2048*4 = 8196 bytes. The general form is
         *    4 + 8 * audioBlockSamples.
         *  - The largest inbound frame that is NOT IQ is a CMD_OPEN whose
         *    host string uses the full u16 length: ~65.5 kB.
         *
         * One MiB is 128x the real TX block and 16x the largest legal
         * CMD_OPEN — room for a TX block up to 128k audio samples (2.7 s of
         * audio, far past anything the streaming path would batch) without
         * anyone having to revisit this number. What it buys is the point:
         * a peer that authenticated and then desynchronised, or went hostile,
         * can force a 1 MiB array instead of a 64 MiB one, and the stream
         * fails with an IOException into a clean reconnect.
         */
        private const val MAX_SESSION_PAYLOAD = 1024 * 1024

        /**
         * Which session owns which radio (key = kind/host:port). The service
         * allows several sessions, but a BOARD tolerates exactly one driver:
         * two sessions on one HL2 interleave TX sequences and the stale one
         * keeps asserting its MOX.
         */
        private val deviceOwners =
            java.util.concurrent.ConcurrentHashMap<String, DriverSession>()

        /** Constant-time equality — never leak the token by comparison timing. */
        private fun tokensMatch(a: String, b: String): Boolean {
            val ab = a.toByteArray(Charsets.UTF_8)
            val bb = b.toByteArray(Charsets.UTF_8)
            var diff = ab.size xor bb.size
            for (i in ab.indices) diff = diff or (ab[i].toInt() xor bb.getOrElse(i) { 0 }.toInt())
            return diff == 0
        }
    }

    // Every session must authenticate with the per-connection token before
    // any device command is honoured — loopback is NOT trusted (another local
    // app could open the radio and key TX otherwise). A missing token means
    // misconfiguration, so it fails closed (never authenticated).
    @Volatile private var authenticated = false

    /**
     * The open radio, seen through the contract every library implements.
     * The typed fields below still exist for the commands that are genuinely
     * specific to one radio; everything common goes through this one.
     */
    @Volatile private var radio: RadioClient? = null

    private val frames = Frames(
        DataInputStream(BufferedInputStream(socket.getInputStream(), 64 * 1024)),
        DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 256 * 1024)),
    )
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var closed = false

    // ---- shared-memory IQ ring (FEAT_SHM_RING, loopback fast path) --------
    //
    // Created on demand by the app's SHM_TRANSACT_GET_RING binder call (see
    // DriverService.onBind) and armed only after the app confirms with
    // CMD_SHM_ATTACH. While armed, EV_DATA / EV_DATA_RX payloads go through
    // the ring (one memcpy, driver buffer -> slot) and the TCP session
    // carries only an 8-byte EV_SHM_FRAME notification, keeping ordering
    // with the status/telemetry frames around it. Publication happens BEFORE
    // the notification is enqueued and the ring never overwrites an unread
    // slot, so the app can never observe a torn block (see ShmRing docs).
    private var sharedMemory: android.os.SharedMemory? = null
    private var shmBuffer: java.nio.ByteBuffer? = null
    @Volatile private var shmRing: com.isaklab.isdrproto.ShmRing? = null
    @Volatile private var shmArmed = false
    /** Serializes ring publishes (tryPublish is a non-atomic RMW on writeIdx,
     *  and data/rx callbacks are not guaranteed to share a thread) and holds
     *  off releaseShm's unmap while a publish is copying into the mapping. */
    private val shmLock = Object()

    /** True for the authenticated session owning [token] (constant-time). */
    fun ownsToken(token: String): Boolean =
        authenticated && requiredToken != null && tokensMatch(token, requiredToken)

    /**
     * Create (once) and return the ring geometry + shared memory for the
     * binder handshake; null when unavailable (API < 27, closed, or ashmem
     * failure) — the app then simply stays on the TCP data plane.
     */
    @Synchronized
    fun acquireShm(): Triple<android.os.SharedMemory, Int, Int>? {
        if (closed || android.os.Build.VERSION.SDK_INT < 27) return null
        sharedMemory?.let {
            return Triple(it, com.isaklab.isdrproto.ShmRing.DEFAULT_SLOTS,
                com.isaklab.isdrproto.ShmRing.DEFAULT_SLOT_BYTES)
        }
        return try {
            val nSlots = com.isaklab.isdrproto.ShmRing.DEFAULT_SLOTS
            val slotBytes = com.isaklab.isdrproto.ShmRing.DEFAULT_SLOT_BYTES
            val shm = android.os.SharedMemory.create(
                "isdr-iq-ring", com.isaklab.isdrproto.ShmRing.totalBytes(nSlots, slotBytes))
            val buf = shm.mapReadWrite()
            shmBuffer = buf
            shmRing = com.isaklab.isdrproto.ShmRing.create(buf, nSlots, slotBytes)
            sharedMemory = shm
            Log.i(TAG, "shm ring created: $nSlots x $slotBytes bytes")
            Triple(shm, nSlots, slotBytes)
        } catch (e: Exception) {
            Log.w(TAG, "shm ring unavailable: ${e.message}")
            null
        }
    }

    private fun releaseShm() {
        shmArmed = false
        val buf: java.nio.ByteBuffer?
        val shm: android.os.SharedMemory?
        synchronized(shmLock) {       // no publish is mid-copy past this point
            shmRing = null
            buf = shmBuffer
            shmBuffer = null
            shm = sharedMemory
            sharedMemory = null
        }
        try {
            // Both calls are API 27+, and a SharedMemory only ever exists there;
            // the explicit guard is what lint needs to see around close() too.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                if (buf != null) android.os.SharedMemory.unmap(buf)
                shm?.close()
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Publish a data block to the ring; true when handled (published, or
     * dropped-and-counted on consumer lag). False = not armed or block too
     * big for a slot — caller falls back to the TCP frame path.
     */
    private fun publishShm(type: Int, a: Int, fft: FloatArray, iq: FloatArray, tag: Int): Boolean {
        if (!shmArmed) return false
        val seq = synchronized(shmLock) {
            val ring = shmRing ?: return false
            ring.tryPublish(type, a, fft, fft.size, iq, iq.size, tag)
        }
        if (seq == -2L) return false               // oversized: TCP fallback
        if (seq < 0) {                             // ring full: drop-newest, counted
            synchronized(outLock) { outDrops++ }
            return true
        }
        val b = obtainBuf(8)
        java.nio.ByteBuffer.wrap(b).putLong(seq)
        enqueue(OutEntry(DriverProto.EV_SHM_FRAME, b, 8, null, droppable = true))
        return true
    }

    private var rtlTcp: RTLTCPClient? = null
    private var rtlUsb: RTLUSBClient? = null
    private var hackRf: HackRfClient? = null
    // FLEX SUPPORT IS OFF. libflexk is a private repository and is no longer a
    // submodule of this driver, so nothing here may name its types — a clone
    // without access must still build. Every call site below is commented out
    // rather than deleted: turning support back on is re-adding the submodule
    // and uncommenting, not rewriting the session from memory.
    // private var flex: com.isaklab.libflexk.FlexClient? = null
    private var hl2: Hl2Client? = null
    private var g2: G2Client? = null

    /**
     * Client generation, bumped on every open/close. Driver callbacks carry
     * the generation they were created for and are ignored once stale — so a
     * replaced client's asynchronous "Disconnected" can never overwrite the
     * new client's "Connected" (the disconnect path is async in every client).
     */
    private val clientGen = java.util.concurrent.atomic.AtomicInteger()

    fun start() {
        thread(name = "driver-session") { readLoop() }
        startTxWatchdog()
        // The writer is the LAST hop before the app: every IQ block the radio
        // produced reaches the host through it. It used to run at the default
        // priority, below the radio loops AND below anything else the platform
        // felt like scheduling — measured on an A30 it had accumulated 27 s of
        // runqueue wait, which lands in the audio as gaps. Audio priority, one
        // notch below the radio loops that must not miss a packet.
        thread(name = "driver-out") {
            try {
                android.os.Process.setThreadPriority(DspThread.PRIORITY_DELIVERY)
            } catch (_: Throwable) {}
            writerLoop()
        }
    }

    // ---- outbound backpressure queue -------------------------------------
    //
    // Data-plane frames (EV_DATA / EV_DATA_RX / EV_SWEEP_BLOCK / telemetry)
    // used to be written to the TCP socket directly from the UDP receive
    // thread: a slow reader (app GC, saturated Wi-Fi backhaul) blocked the
    // receive loop and the kernel dropped radio packets. Frames are now
    // ENCODED at enqueue time (so drivers may reuse their IQ buffers) into
    // pooled byte arrays and drained by a dedicated writer thread; when the
    // queue is full the oldest droppable frame is discarded and counted.

    private class OutEntry(
        val op: Int,
        val buf: ByteArray?,
        val len: Int,
        val run: (() -> Unit)?,
        val droppable: Boolean,
    )

    private val outLock = Object()
    private val outQueue = ArrayDeque<OutEntry>()
    private val bufPool = ArrayDeque<ByteArray>()
    @Volatile private var outDrops = 0L

    private fun obtainBuf(min: Int): ByteArray {
        synchronized(bufPool) {
            val it = bufPool.iterator()
            while (it.hasNext()) {
                val b = it.next()
                if (b.size >= min) { it.remove(); return b }
            }
        }
        var cap = 1024
        while (cap < min) cap = cap shl 1
        return ByteArray(cap)
    }

    private fun recycleBuf(b: ByteArray) {
        synchronized(bufPool) { if (bufPool.size < MAX_POOLED) bufPool.addLast(b) }
    }

    private fun enqueue(e: OutEntry) {
        if (closed) { e.buf?.let(::recycleBuf); return }
        synchronized(outLock) {
            if (outQueue.size >= MAX_OUT) {
                val idx = outQueue.indexOfFirst { it.droppable }
                if (idx >= 0) {
                    outQueue.removeAt(idx).buf?.let(::recycleBuf)
                    outDrops++
                    if (outDrops == 1L || outDrops % 100 == 0L) {
                        Log.w(TAG, "outbound backpressure: $outDrops frames dropped")
                    }
                }
                // No droppable entry (all control frames): grow past the cap —
                // control frames are rare, tiny and must never be lost.
            }
            outQueue.addLast(e)
            outLock.notify()
        }
    }

    private fun writerLoop() {
        try {
            while (true) {
                val e = synchronized(outLock) {
                    while (outQueue.isEmpty() && !closed) outLock.wait()
                    if (outQueue.isEmpty()) return   // closed and drained
                    outQueue.removeFirst()
                }
                if (e.run != null) e.run.invoke()
                else frames.writeRaw(e.op, e.buf!!, e.len)
                e.buf?.let(::recycleBuf)
            }
        } catch (ex: Exception) {
            if (!closed) Log.i(TAG, "outbound writer ended: ${ex.message}")
            close()
        }
    }

    private fun readLoop() {
        try {
            while (!closed) {
                val frame = frames.read(if (authenticated) MAX_SESSION_PAYLOAD else PRE_AUTH_PAYLOAD)
                    ?: break
                handle(frame)
            }
        } catch (e: Exception) {
            if (!closed) Log.i(TAG, "session ended: ${e.message}")
        }
        close()
    }

    fun close() {
        if (closed) return
        closed = true
        closeDevice()
        releaseShm()
        synchronized(outLock) { outLock.notifyAll() }   // release the writer
        scope.cancel()
        try {
            socket.close()
        } catch (_: Exception) {
        }
        onClosed(this)
    }

    // ---- outbound (driver callbacks -> app). Failed writes end the session. ----

    private inline fun send(block: () -> Unit) {
        if (closed) return
        try {
            block()
        } catch (e: Exception) {
            close()
        }
    }

    // Data-plane callbacks encode into pooled buffers at call time (drivers
    // may reuse their IQ arrays after we return) and enqueue for the writer
    // thread — the UDP receive thread never blocks on the TCP socket.

    // Flush sequence (FEAT_SEQ_TAG): every EV_DATA gets the next seq and its
    // preceding EV_DATA_RX siblings carry the SAME seq — the app pairs
    // per-receiver blocks with the main block by tag, never by arrival
    // order (an individually dropped rx frame used to pair a stale block).
    // Data callbacks of one client share a thread, so a plain int suffices.
    private var flushSeq = 0



    // ---- transmit watchdog -------------------------------------------------
    //
    // The link can die without anyone being told. A phone that loses its
    // network mid-over sends no FIN: the relay holds the spliced pair for its
    // whole idle timeout, the station stays blocked on a read, and nothing
    // runs closeDevice() — so the radio can sit KEYED for minutes, into an
    // antenna, with no operator present. That is a burnt PA and an occupied
    // channel, not an inconvenience.
    //
    // Every path that keys PTT streams transmit samples continuously (voice,
    // digital, and the VNA sweep's carrier pump), so their absence is a
    // reliable proxy for "the far end is gone". The watchdog arms on the
    // FIRST sample after key-up rather than on key-up itself, so a mode that
    // legitimately keys without a stream of its own is never cut short.
    //
    // This is deliberately in the driver host: it is the last process that
    // still holds the radio, and it protects regardless of how the link died.
    @Volatile private var pttOn = false
    @Volatile private var lastTxIqMs = com.isaklab.isdrdrivers.core.TxWatchdogPolicy.NOT_ARMED

    /** Monotonic instant of the last key-down (for the no-stream ceilings). */
    @Volatile private var keyedAtMs = 0L

    private fun startTxWatchdog() {
        scope.launch {
            while (!closed) {
                kotlinx.coroutines.delay(250)
                val last = lastTxIqMs
                val now = android.os.SystemClock.elapsedRealtime()
                if (!com.isaklab.isdrdrivers.core.TxWatchdogPolicy
                        .shouldUnkey(pttOn, keyedAtMs, last, now)
                ) {
                    continue
                }
                Log.w(
                    TAG,
                    "transmit watchdog: keyed ${now - keyedAtMs} ms, last IQ " +
                        "${if (last == 0L) "never" else "${now - last} ms ago"} — unkeying",
                )
                pttOn = false
                lastTxIqMs = com.isaklab.isdrdrivers.core.TxWatchdogPolicy.NOT_ARMED
                try {
                    // Through the capability, not per-radio fields: every
                    // transmit-capable radio — current and future — gets the
                    // same watchdog unkey, or a new driver would ship with a
                    // silent hole in the safety net.
                    (radio as? TransmitCapable)?.setPtt(false)
                } catch (e: Exception) {
                    Log.e(TAG, "watchdog could not unkey: ${e.message}")
                }
                runCatching { sendTxState() }
            }
        }
    }

    /** Last spectrum actually put on the wire; see [onData]. */
    private var lastSentSpectrum: FloatArray? = null

    private fun onData(fft: FloatArray, iq: FloatArray) {
        val tag = flushSeq
        flushSeq = (flushSeq + 1) and 0x7fffffff
        if (publishShm(DriverProto.EV_DATA, fft.size, fft, iq, tag)) return
        // Ship the spectrum only when it is NEW.
        //
        // Every driver recomputes the FFT on a DISPLAY cadence (~12 fps) but
        // hands the cached result back with every IQ block (~115/s), so about
        // nine of every ten frames carried a byte-identical copy of the one
        // before. Wideband that was lost against hundreds of thousands of IQ
        // samples — the assumption this code used to state. Once the station
        // channelises to the mode's window that inverts: at a 6 kHz SSB
        // window the IQ is 96 kbit/s and the repeated spectrum is 2.9 Mbit/s,
        // thirty times the payload it accompanies.
        //
        // Identity is exactly the right test: each computation publishes a
        // fresh array, so an unchanged reference IS an unchanged spectrum.
        // An empty spectrum is already a valid frame (drivers send one when
        // nothing is displaying), so no peer has to learn anything new.
        val fresh = fft.isNotEmpty() && fft !== lastSentSpectrum
        if (fresh) lastSentSpectrum = fft
        val outFft = if (fresh) fft else EMPTY_FLOATS

        val fmt = DriverProto.IQ_WIRE_FORMAT
        val sfmt = DriverProto.SPECTRUM_WIRE_FORMAT
        val len = 12 + SpectrumCodec.encodedSize(sfmt, outFft.size) +
            IqCodec.encodedSize(fmt, iq.size)
        val b = obtainBuf(len)
        val bb = java.nio.ByteBuffer.wrap(b)
        bb.putInt(outFft.size)
        bb.position(SpectrumCodec.encode(outFft, outFft.size, sfmt, b, bb.position()))
        bb.putInt(iq.size)
        bb.position(IqCodec.encode(iq, iq.size, fmt, b, bb.position()))
        bb.putInt(tag)
        enqueue(OutEntry(DriverProto.EV_DATA, b, len, null, droppable = true))
    }

    private fun onDataRx(rx: Int, iq: FloatArray) {
        val tag = flushSeq                       // seq of the flush's EV_DATA
        if (publishShm(DriverProto.EV_DATA_RX, rx, EMPTY_FLOATS, iq, tag)) return
        val fmt = DriverProto.IQ_WIRE_FORMAT
        val len = 12 + IqCodec.encodedSize(fmt, iq.size)
        val b = obtainBuf(len)
        val bb = java.nio.ByteBuffer.wrap(b)
        bb.putInt(rx)
        bb.putInt(iq.size)
        bb.position(IqCodec.encode(iq, iq.size, fmt, b, bb.position()))
        bb.putInt(tag)
        enqueue(OutEntry(DriverProto.EV_DATA_RX, b, len, null, droppable = true))
    }

    private fun onSweepBlock(lowerEdgeHz: Long, iq: FloatArray) {
        val len = 12 + iq.size * 4
        val b = obtainBuf(len)
        val bb = java.nio.ByteBuffer.wrap(b)
        bb.putLong(lowerEdgeHz)
        bb.putInt(iq.size)
        bb.asFloatBuffer().put(iq)
        enqueue(OutEntry(DriverProto.EV_SWEEP_BLOCK, b, len, null, droppable = true))
    }

    private fun onStatus(connected: Boolean, status: String) {
        DriverServiceState.update {
            it.copy(radioStatus = status, radioConnected = connected)
        }
        // Through the queue (never dropped) so status stays ordered with the
        // data frames around it.
        enqueue(OutEntry(0, null, 0, { frames.writeStatus(connected, status) }, droppable = false))
    }

    /** [onStatus] gated on the client generation it was created for. */
    private fun statusFor(gen: Int): (Boolean, String) -> Unit = { connected, status ->
        if (gen == clientGen.get()) onStatus(connected, status)
    }

    /**
     * The panadapter window actually in force. Through the queue, never
     * dropped: an app that missed this frame would label its axis with a span
     * the driver is not delivering.
     */
    private fun sendSpectrumZoom(decimation: Int, offsetHz: Long) {
        val b = java.nio.ByteBuffer.allocate(12)
        b.putInt(decimation)
        b.putLong(offsetHz)
        b.flip()
        enqueue(OutEntry(0, null, 0, { frames.write(DriverProto.EV_SPECTRUM_ZOOM, b) }, droppable = false))
    }

    private fun sendTelemetry(t: RadioTelemetry) =
        enqueue(OutEntry(0, null, 0, { frames.writeTelemetry(t) }, droppable = true))

    // ---- HackRF queries -----------------------------------------------------
    //
    // These are REPLIES, not telemetry: never droppable, because a query the
    // app is waiting on that gets silently discarded by backpressure leaves
    // the device panel blank with no error to explain it.

    /** Append a u16-length-prefixed UTF-8 string, matching ByteBuffer.getUtf. */
    private fun ByteBuffer.putUtf(s: String): ByteBuffer {
        val utf = s.toByteArray(Charsets.UTF_8)
        putShort(utf.size.toShort())
        put(utf)
        return this
    }

    /**
     * Runs OFF the session thread: it issues several control transfers in
     * series, and on a board that has stopped answering each one costs its
     * full timeout — seconds during which PTT would sit in the queue behind
     * a status query.
     */
    private fun sendHackRfInfo() {
        val hrf = hackRf ?: return
        scope.launch { sendHackRfInfoBlocking(hrf) }
    }

    private fun sendHackRfInfoBlocking(hrf: HackRfClient) {
        val info = hrf.boardInfo()
        val boards = hrf.operacakeBoards()
        val clkin = hrf.clkinStatus()
        val cpld = hrf.cpldChecksum()
        val strings = listOf(
            info.boardName, info.revisionName, info.platformName,
            info.firmwareVersion, info.usbApiName, info.serialNumber,
        )
        val cap = 4 + 4 + 1 + 4 + 4 + 1 + 4 + boards.size * 4 + 8 + 1 + 4 + 1 + 4 + 4 +
            strings.sumOf { 2 + it.toByteArray(Charsets.UTF_8).size }
        val bb = ByteBuffer.allocate(cap)
        bb.putInt(info.boardId)
        bb.putUtf(info.boardName)
        bb.putInt(info.boardRevision)
        bb.putUtf(info.revisionName)
        bb.put(if (info.isGenuineGsg) 1 else 0)
        bb.putInt(info.platformBits)
        bb.putUtf(info.platformName)
        bb.putUtf(info.firmwareVersion)
        bb.putInt(info.usbApiVersion)
        bb.putUtf(info.usbApiName)
        bb.putUtf(info.serialNumber)
        bb.put(if (clkin) 1 else 0)
        bb.putInt(boards.size)
        boards.forEach { bb.putInt(it) }
        bb.putLong(cpld)
        bb.put(if (info.revisionKnown) 1 else 0)
        bb.putInt(hrf.basebandFilterHz())
        bb.put(if (hrf.hasExplicitTuning()) 1 else 0)
        bb.putInt(boards.firstOrNull()?.let { hrf.operacakeGetMode(it) } ?: -1)
        bb.putInt(hrf.supportedControls())
        bb.flip()
        send { frames.write(DriverProto.EV_HRF_INFO, bb) }
    }

    private fun sendHackRfM0State() {
        val st = hackRf?.m0State() ?: return
        val bb = ByteBuffer.allocate(11 * 4)
        bb.putInt(st.requestedMode)
        bb.putInt(st.requestFlag)
        bb.putInt(st.activeMode)
        bb.putInt(st.m0Count)
        bb.putInt(st.m4Count)
        bb.putInt(st.numShortfalls)
        bb.putInt(st.longestShortfall)
        bb.putInt(st.shortfallLimit)
        bb.putInt(st.threshold)
        bb.putInt(st.nextMode)
        bb.putInt(st.error)
        bb.flip()
        send { frames.write(DriverProto.EV_HRF_M0_STATE, bb) }
    }

    /**
     * Self-test runs OFF the session thread: the RTC oscillator check alone
     * sleeps a full second by design, and doing that inline would stall every
     * command queued behind it — including PTT.
     */
    private fun sendHackRfSelfTest() {
        val hrf = hackRf ?: return
        scope.launch {
            val test = hrf.readSelfTest()
            val rtc = hrf.testRtcOsc()
            val msg = test?.message ?: ""
            val bb = ByteBuffer.allocate(1 + 2 + msg.toByteArray(Charsets.UTF_8).size + 2)
            bb.put(if (test?.pass == true) 1 else 0)
            bb.putUtf(msg)
            bb.put(if (rtc != null) 1 else 0)
            bb.put(if (rtc == true) 1 else 0)
            bb.flip()
            send { frames.write(DriverProto.EV_HRF_SELFTEST, bb) }
        }
    }

    private fun onHl2Telemetry(t: Hl2Protocol.Telemetry) = sendTelemetry(
        RadioTelemetry(
            temperatureC = t.temperatureC, paCurrentA = t.paCurrentA,
            forwardPower = t.forwardPower.toDouble(),
            reversePower = t.reversePower.toDouble(),
            supplyVolts = t.supplyVolts,
            adcOverload = t.adcOverflow,
            keyPtt = t.keyPtt, keyDot = t.keyDot, keyDash = t.keyDash,
            rxGaps = hl2?.rxGapCount ?: 0, linkDrops = outDrops,
            hasTemperature = t.hasTemperature, hasCurrent = t.hasCurrent,
            hasFwdPower = t.hasFwdPower, hasRevPower = t.hasRevPower,
            hasSupplyVolts = t.hasSupplyVolts,
            hasAdcOverload = t.hasAdcOverflow,
            hasKeyInputs = true,
            hasRxGaps = true, hasLinkDrops = true,
        )
    )

    private fun onG2Status(st: G2Protocol.Status) = sendTelemetry(
        RadioTelemetry(
            forwardPower = st.forwardPower.toDouble(),
            reversePower = st.reversePower.toDouble(),
            supplyVolts = st.supplyVolts,
            exciterPower = st.exciterPower.toDouble(),
            adcOverload = st.adcOverload,
            pllLocked = st.pllLocked,
            keyPtt = st.pttIn, keyDot = st.dot, keyDash = st.dash,
            rxGaps = g2?.rxGapCount ?: 0, linkDrops = outDrops,
            hasFwdPower = true, hasRevPower = true, hasSupplyVolts = true,
            hasAdcOverload = true, hasPllLock = true,
            hasExciterPower = true, hasKeyInputs = true,
            hasRxGaps = true, hasLinkDrops = true,
        )
    )

    // The HackRF has no periodic status packet like the HL2/G2, so its gap
    // count arrives from the client's RX processing thread, only when it
    // changes — same telemetry field, same meaning: samples the stream lost.
    private fun onHackRfGaps(gaps: Long) = sendTelemetry(
        RadioTelemetry(
            rxGaps = gaps, linkDrops = outDrops,
            hasRxGaps = true, hasLinkDrops = true,
        )
    )

    // Only the FLEX client fed this, so it is commented out with the rest of
    // the FLEX path — an unused private function is a compiler warning, and
    // this repo treats warnings as a gate.
    // private fun onFlexGaps(gaps: Long) = sendTelemetry(
    //     RadioTelemetry(
    //         rxGaps = gaps, linkDrops = outDrops,
    //         hasRxGaps = true, hasLinkDrops = true,
    //     )
    // )

    private fun sendTxState() {
        val tx = hl2?.isTransmitting() ?: g2?.isTransmitting() ?: hackRf?.isTransmitting() ?: false
        send { frames.writeBool(DriverProto.EV_TX_STATE, tx) }
    }

    // ---- inbound dispatch ----

    private fun handle(frame: Frame) {
        val p = frame.payload
        // Before authentication only HELLO and AUTH are legal on a LAN
        // session; anything else is a probe — answer nothing and drop it.
        if (!authenticated &&
            frame.op != DriverProto.CMD_HELLO && frame.op != DriverProto.CMD_AUTH
        ) {
            Log.w(TAG, "unauthenticated command 0x${frame.op.toString(16)} — closing")
            close()
            return
        }
        when (frame.op) {
            DriverProto.CMD_HELLO -> {
                val version = p.int
                val features = DriverProto.FEAT_RX_STREAMS or
                    DriverProto.FEAT_SEQ_TAG or
                    DriverProto.FEAT_RF_PATH_CONTROL or
                    DriverProto.FEAT_ANTENNA_SWITCH or
                    DriverProto.FEAT_CLOCK_TRIGGER or
                    DriverProto.FEAT_BOARD_DIAGNOSTICS or
                    // ashmem SharedMemory needs API 27; older devices simply
                    // never advertise the ring and stay on TCP frames.
                    (if (android.os.Build.VERSION.SDK_INT >= 27) DriverProto.FEAT_SHM_RING else 0)
                send { frames.writeHello(DriverProto.VERSION, features) }
                if (version != DriverProto.VERSION) {
                    Log.w(TAG, "protocol mismatch: app=$version host=${DriverProto.VERSION}")
                }
            }
            DriverProto.CMD_AUTH -> {
                val token = p.getUtf()
                val ok = requiredToken != null && tokensMatch(token, requiredToken)
                if (ok) {
                    authenticated = true
                    // Authenticated: drop the idle-auth timeout so the long-
                    // lived IQ stream isn't interrupted (audit H2).
                    try { socket.soTimeout = 0 } catch (_: Exception) {}
                }
                send { frames.writeBool(DriverProto.EV_AUTH_RESULT, ok) }
                if (!ok) {
                    Log.w(TAG, "auth rejected — closing")
                    close()
                }
            }
            DriverProto.CMD_OPEN -> {
                val kind = p.get().toInt()
                val host = p.getUtf()
                val port = p.int
                val flags = p.int
                scope.launch { openDevice(kind, host, port, flags) }
            }
            DriverProto.CMD_CLOSE -> closeDevice()

            DriverProto.CMD_SHM_ATTACH -> {
                val want = p.getBool()
                // Only arm once the ring exists (binder handshake done); a
                // request without it — or a detach — leaves/returns the data
                // plane on plain TCP frames.
                synchronized(shmLock) {
                    val ring = shmRing
                    // A fresh consumer starts from live data: release any
                    // backlog a dead consumer left behind, otherwise a full
                    // ring drops every publish and the new consumer never
                    // receives its anchoring notify.
                    if (want && ring != null) ring.resetForAttach()
                    shmArmed = want && ring != null
                }
                send { frames.writeBool(DriverProto.EV_SHM_RESULT, shmArmed) }
                Log.i(TAG, "shm data plane ${if (shmArmed) "armed" else "off"}")
            }

            // The common set goes through the contract, not through one line
            // per radio. A radio added later is reached here the moment it is
            // constructed, with nothing to remember to extend.
            DriverProto.CMD_SET_FREQUENCY -> {
                radio?.setFrequency(p.long)
                // Answered, always, with what is IN FORCE (EV_FREQUENCY):
                // the app reconciles its display against the hardware's
                // truth, so a tune that was clamped or lost can never leave
                // the two sides silently apart. Zero = cannot say = silence.
                announceFrequency()
            }
            DriverProto.CMD_SET_SAMPLE_RATE -> {
                radio?.setSampleRate(p.int)
                // The rate rule (EV_SAMPLE_RATE): the app scales spectrum,
                // NCO and audio decimation by the rate the hardware RUNS.
                announceSampleRate()
            }
            DriverProto.CMD_SET_SPECTRUM_INTEREST -> p.getBool().let { on ->
                radio?.spectrumEnabled = on
            }
            DriverProto.CMD_SET_ANTENNA_POWER -> p.getBool().let { on ->
                (radio as? AntennaPowerCapable)?.setAntennaPower(on)
            }
            DriverProto.CMD_SET_ANALOG_FILTER -> p.int.let { hz ->
                (radio as? AnalogFilterCapable)?.setAnalogFilterHz(hz)
            }
            DriverProto.CMD_SET_SPECTRUM_ZOOM -> {
                val decimation = p.int
                val offsetHz = p.long
                // Answered with what is IN FORCE, not with what was asked
                // for: a radio with no session yet, or one that cannot narrow
                // at all, stays at 1 and the app must draw the whole span.
                val got = when (val r = radio) {
                    is com.isaklab.libhl2sdrk.Hl2Client -> r.setSpectrumZoom(decimation, offsetHz)
                    is com.isaklab.libg2sdrk.G2Client -> r.setSpectrumZoom(decimation, offsetHz)
                    is com.isaklab.librtlsdrk.RTLTCPClient -> r.setSpectrumZoom(decimation, offsetHz)
                    is com.isaklab.librtlsdrk.RTLUSBClient -> r.setSpectrumZoom(decimation, offsetHz)
                    is com.isaklab.libhackrfk.HackRfClient -> r.setSpectrumZoom(decimation, offsetHz)
                    else -> 1
                }
                sendSpectrumZoom(got, offsetHz)
            }

            // TX
            DriverProto.CMD_SET_TX_FREQUENCY -> p.long.let { hz ->
                (radio as? TransmitCapable)?.setTxFrequency(hz)
            }
            DriverProto.CMD_SET_PTT -> p.getBool().let { on ->
                (radio as? TransmitCapable)?.setPtt(on)
                pttOn = on
                // Cleared either way: the watchdog re-arms only when transmit
                // samples actually start flowing, so a mode that keys without
                // a stream of its own is never cut short by it.
                lastTxIqMs = com.isaklab.isdrdrivers.core.TxWatchdogPolicy.NOT_ARMED
                // The key-down instant anchors the no-stream and absolute
                // ceilings: without it a keyed radio whose client died before
                // the first sample had NO watchdog at all.
                if (on) keyedAtMs = android.os.SystemClock.elapsedRealtime()
                sendTxState()
            }
            DriverProto.CMD_SET_TX_DRIVE -> p.int.let { level ->
                (radio as? TxDriveCapable)?.setTxDrive(level)
            }
            DriverProto.CMD_SET_PA_ENABLED -> p.getBool().let { on ->
                (radio as? TxDriveCapable)?.setPaEnabled(on)
            }
            DriverProto.CMD_SET_TX_TIMING -> {
                val latencyMs = p.int
                val hangMs = p.int
                // Concept-level routing: any radio with a host-visible TX
                // buffer implements TxTimingCapable and clamps to its own
                // register range; the rest ignore the command.
                (radio as? TxTimingCapable)?.setTxTiming(latencyMs, hangMs)
            }
            DriverProto.CMD_TX_IQ -> p.getFloats().let { iq ->
                hackRf?.submitTxIq(iq)
                hl2?.submitTxIq(iq)
                g2?.submitTxIq(iq)
                lastTxIqMs = android.os.SystemClock.elapsedRealtime()
            }

            // Multi-receiver

            DriverProto.CMD_SET_RECEIVER_COUNT -> p.int.let { n ->
                hl2?.setReceiverCount(n)
                g2?.setReceiverCount(n)
            }
            DriverProto.CMD_SET_ACTIVE_RECEIVER -> p.int.let { i ->
                hl2?.setActiveReceiver(i)
                g2?.setActiveReceiver(i)
            }
            DriverProto.CMD_SET_FREQUENCY2 -> p.long.let { hz ->
                hl2?.setFrequency2(hz)
                g2?.setFrequency2(hz)
            }
            DriverProto.CMD_SET_RX_FREQUENCY -> {
                val idx = p.int
                val hz = p.long
                hl2?.setRxFrequency(idx, hz)
                g2?.setRxFrequency(idx, hz)
            }
            DriverProto.CMD_SET_RX_STREAM_MASK -> p.int.let { mask ->
                hl2?.setRxStreamMask(mask)
                g2?.setRxStreamMask(mask)
            }

            // HL2
            DriverProto.CMD_HL2_SET_LNA -> hl2?.setLnaGain(p.int)
            DriverProto.CMD_HL2_SET_VNA_MODE -> hl2?.setVnaMode(p.getBool())
            DriverProto.CMD_HL2_SET_TR_DISABLE -> hl2?.setTrDisable(p.getBool())
            DriverProto.CMD_HL2_SET_FILTER_OUTPUTS -> {
                val rx = p.int
                // v2 payload carries the TX word; tolerate a v1 single-int
                // frame during a mismatched-update window.
                val tx = if (p.remaining() >= 4) p.int else -1
                hl2?.setOpenCollectorOutputs(rx, tx)
            }
            DriverProto.CMD_HL2_SET_VNA_COUNT -> hl2?.setVnaCount(p.int)
            DriverProto.CMD_HL2_SET_IOBOARD -> {
                val enabled = p.getBool()
                val rfInput = p.int
                val opMode = p.int
                hl2?.setIoBoard(enabled, rfInput, opMode)
            }
            // PureSignal: shared opcode across HPSDR radios — the HL2 flips
            // the gateware routing bit, the G2 switches the reference DDC's
            // input to the TX/DUC loopback (P2 DDC-specific packet).
            DriverProto.CMD_HL2_SET_PURESIGNAL -> p.getBool().let { on ->
                hl2?.setPureSignal(on)
                g2?.setPureSignal(on)
            }
            DriverProto.CMD_HL2_SET_CW_KEYER -> {
                val en = p.getBool(); val wpm = p.int; val mode = p.int
                val weight = p.int; val spacing = p.getBool(); val rev = p.getBool()
                val delay = p.int; val hang = p.int
                hl2?.setCwKeyer(en, wpm, mode, weight, spacing, rev, delay, hang)
            }
            DriverProto.CMD_HL2_SET_AMP_KEY -> {
                val mask = p.int
                val txDelay = p.int
                val hang = p.int
                hl2?.setAmpKey(mask, txDelay, hang)
            }

            // G2
            DriverProto.CMD_G2_SET_ATTENUATOR -> g2?.setStepAttenuator(p.int)
            DriverProto.CMD_G2_SET_OC_OUTPUTS -> g2?.setOpenCollectorOutputs(p.int)

            // HackRF
            DriverProto.CMD_HRF_SET_LNA -> hackRf?.setLnaGain(p.int)
            DriverProto.CMD_HRF_SET_VGA -> hackRf?.setVgaGain(p.int)
            DriverProto.CMD_HRF_SET_TXVGA -> hackRf?.setTxVgaGain(p.int)
            DriverProto.CMD_HRF_SET_AMP -> hackRf?.setAmpEnable(p.getBool())
            DriverProto.CMD_HRF_START_RX -> hackRf?.startRx()
            DriverProto.CMD_HRF_SWEEP_START -> {
                val startMHz = p.int
                val stopMHz = p.int
                val rateHz = p.int
                val stepHz = p.int
                hackRf?.startSweep(startMHz, stopMHz, rateHz, stepHz, ::onSweepBlock)
            }
            DriverProto.CMD_HRF_SWEEP_STOP -> hackRf?.stopSweep()

            // HackRF, second block: the rest of what the board can be told.
            DriverProto.CMD_HRF_SET_FREQ_EXPLICIT -> {
                val ifHz = p.long
                val loHz = p.long
                val path = p.int
                hackRf?.setFreqExplicit(ifHz, loHz, path)
            }
            DriverProto.CMD_HRF_SET_BIAS_T_OPTS -> {
                // Decoded into plain wire values and handed over as such. The
                // host has no business building a lib's own data class: that
                // makes an internal type of one radio part of the dispatch.
                val offUpdate = p.getBool(); val offOnEntry = p.getBool()
                val offEnabled = p.getBool()
                val rxUpdate = p.getBool(); val rxOnEntry = p.getBool()
                val rxEnabled = p.getBool()
                val txUpdate = p.getBool(); val txOnEntry = p.getBool()
                val txEnabled = p.getBool()
                hackRf?.setBiasTeeOptions(
                    offUpdate, offOnEntry, offEnabled,
                    rxUpdate, rxOnEntry, rxEnabled,
                    txUpdate, txOnEntry, txEnabled,
                )
            }
            DriverProto.CMD_HRF_SET_HW_SYNC -> hackRf?.setHwSyncMode(p.getBool())
            DriverProto.CMD_HRF_SET_UI_ENABLE -> hackRf?.setUiEnable(p.getBool())
            DriverProto.CMD_HRF_SET_LEDS -> hackRf?.setLeds(p.int)
            DriverProto.CMD_HRF_SET_NARROWBAND_FILTER ->
                hackRf?.setNarrowbandFilter(p.getBool())
            DriverProto.CMD_HRF_SET_CLKOUT -> hackRf?.setClkoutEnable(p.getBool())
            DriverProto.CMD_HRF_SET_CLKIN_CTRL -> hackRf?.setClkinCtrl(p.int)
            DriverProto.CMD_HRF_SET_P1_CTRL -> hackRf?.setP1Ctrl(p.int)
            DriverProto.CMD_HRF_SET_P2_CTRL -> hackRf?.setP2Ctrl(p.int)
            DriverProto.CMD_HRF_SET_TX_UNDERRUN_LIMIT -> hackRf?.setTxUnderrunLimit(p.int)
            DriverProto.CMD_HRF_SET_RX_OVERRUN_LIMIT -> hackRf?.setRxOverrunLimit(p.int)
            DriverProto.CMD_HRF_OPERACAKE_SET_PORTS -> {
                val addr = p.int
                val portA = p.int
                val portB = p.int
                hackRf?.operacakeSetPorts(addr, portA, portB)
            }
            DriverProto.CMD_HRF_OPERACAKE_SET_MODE -> {
                val addr = p.int
                hackRf?.operacakeSetMode(addr, p.int)
            }
            DriverProto.CMD_HRF_OPERACAKE_SET_RANGES -> {
                val n = p.int
                val ranges = ArrayList<Triple<Int, Int, Int>>(n.coerceIn(0, 8))
                repeat(n.coerceIn(0, 8)) { ranges.add(Triple(p.int, p.int, p.int)) }
                if (ranges.isNotEmpty()) hackRf?.operacakeSetFreqRanges(ranges)
            }
            DriverProto.CMD_HRF_OPERACAKE_SET_DWELL -> {
                val n = p.int
                val dwells = ArrayList<Pair<Int, Int>>(n.coerceIn(0, 16))
                repeat(n.coerceIn(0, 16)) { dwells.add(Pair(p.int, p.int)) }
                if (dwells.isNotEmpty()) hackRf?.operacakeSetDwellTimes(dwells)
            }
            DriverProto.CMD_HRF_RESET -> hackRf?.reset()
            DriverProto.CMD_HRF_QUERY_INFO -> sendHackRfInfo()
            DriverProto.CMD_HRF_QUERY_M0_STATE -> sendHackRfM0State()
            DriverProto.CMD_HRF_SELFTEST -> sendHackRfSelfTest()
            DriverProto.CMD_HRF_CLEAR_FREQ_EXPLICIT -> hackRf?.clearFreqExplicit()

            // RTL tuner
            DriverProto.CMD_RTL_SET_GAIN -> p.int.let { g ->
                rtlTcp?.setGain(g)
                rtlUsb?.sendCommand(RTLCommand.SetGain(g))
            }
            DriverProto.CMD_RTL_SET_GAIN_MODE -> p.getBool().let { manual ->
                rtlTcp?.setGainMode(manual)
                rtlUsb?.sendCommand(RTLCommand.SetGainMode(manual))
            }
            DriverProto.CMD_RTL_SET_AGC -> p.getBool().let { on ->
                rtlTcp?.setAgcMode(on)
                rtlUsb?.sendCommand(RTLCommand.SetAGC(on))
            }
            DriverProto.CMD_RTL_SET_PPM -> p.int.let { ppm ->
                rtlTcp?.setFrequencyCorrection(ppm)
                rtlUsb?.sendCommand(RTLCommand.SetPPMCorrection(ppm))
            }

            DriverProto.CMD_RTL_SET_DIRECT_SAMPLING -> p.int.let { mode ->
                rtlUsb?.setDirectSamplingMode(mode)
                rtlTcp?.setDirectSampling(mode)
            }
            // rtl_tcp has no bandwidth opcode; USB only.

            else -> Log.w(TAG, "unknown opcode 0x${frame.op.toString(16)}")
        }
    }

    private suspend fun openDevice(kind: Int, host: String, port: Int, flags: Int) {
        closeDevice()
        // One radio, one session. Reconnection races left a ZOMBIE session
        // holding the same board — both threads fed it, with independent TX
        // sequences, and the zombie kept asserting its (possibly keyed) MOX.
        // The newest claim wins; the old owner is closed before this open
        // touches the hardware.
        claimDevice("$kind/$host:$port")
        // New client generation: stale callbacks from the replaced client
        // (its disconnect is asynchronous) are filtered from here on, so its
        // late "Disconnected" can never shadow this client's "Connected".
        val gen = clientGen.incrementAndGet()
        val onStatusGen = statusFor(gen)
        DriverServiceState.update { it.copy(radio = radioName(kind, flags)) }
        val ok = try {
            when (kind) {
                DriverProto.DEV_RTL_TCP -> {
                    val c = RTLTCPClient(host, port, ::onData, onStatusGen)
                    rtlTcp = c
                    radio = c
                    c.connect()
                }
                DriverProto.DEV_RTL_USB -> {
                    val c = RTLUSBClient(context, ::onData, onStatusGen)
                    rtlUsb = c
                    radio = c
                    c.connect()
                }
                DriverProto.DEV_HACKRF -> {
                    val c = HackRfClient(context, ::onData, onStatusGen, ::onHackRfGaps)
                    hackRf = c
                    radio = c
                    c.connect()
                }
                DriverProto.DEV_FLEX -> {
                    // Support withdrawn (libflexk is private and unbundled).
                    // It answers with a REFUSAL rather than falling through to
                    // the "unknown device" path: the app can still ask for a
                    // FLEX, and an operator who does deserves to be told the
                    // driver dropped it, not left watching a connect that
                    // never completes.
                    onStatus(false, "FlexRadio support is not available in this build")
                    false
                    // val c = com.isaklab.libflexk.FlexClient(::onData, onStatusGen, ::onFlexGaps)
                    // flex = c
                    // val target = host.ifEmpty { c.discover()?.ip ?: "" }
                    // if (target.isEmpty()) {
                    //     onStatus(false, "No FLEX found on the LAN")
                    //     false
                    // } else {
                    //     c.connect(target, port)
                    //     true
                    // }
                }
                DriverProto.DEV_HL2 -> {
                    val c = Hl2Client(
                        host = host.ifEmpty { Hl2Client.BROADCAST },
                        onDataReceived = ::onData,
                        onConnectionStatusChanged = onStatusGen,
                        onDataRx = ::onDataRx,
                        onTelemetry = ::onHl2Telemetry,
                        port = if (port > 0) port else Hl2Protocol.PORT,
                        classicBoard = flags and DriverProto.OPEN_FLAG_CLASSIC_BOARD != 0,
                    )
                    hl2 = c
                    radio = c
                    c.connect()
                }
                DriverProto.DEV_G2 -> {
                    val c = G2Client(
                        host = host.ifEmpty { G2Client.BROADCAST },
                        onDataReceived = ::onData,
                        onConnectionStatusChanged = onStatusGen,
                        onStatus = ::onG2Status,
                        onDataRx = ::onDataRx,
                    )
                    g2 = c
                    radio = c
                    c.connect()
                }
                else -> false
            }
        } catch (e: Exception) {
            Log.e(TAG, "open kind=$kind failed: ${e.message}")
            false
        }
        if (!ok) closeDevice()
        send { frames.writeBool(DriverProto.EV_OPEN_RESULT, ok) }
        // The state in force the moment the radio opens, BEFORE the app has
        // set anything: whatever the hardware powered up on or a previous
        // session left it holding. Announcing both means there is no window
        // in which the two sides disagree unnoticed.
        if (ok) {
            announceSampleRate()
            announceFrequency()
        }
    }

    /** EV_FREQUENCY with the frequency in force; zero (cannot say) is never announced. */
    private fun announceFrequency() {
        val hz = radio?.frequencyHz() ?: return
        if (hz > 0) send { frames.writeI64(DriverProto.EV_FREQUENCY, hz) }
    }

    /** EV_SAMPLE_RATE with the rate in force; zero (cannot say) is never announced. */
    private fun announceSampleRate() {
        val hz = radio?.sampleRateHz() ?: return
        if (hz > 0) send { frames.writeI32(DriverProto.EV_SAMPLE_RATE, hz) }
    }

    private fun radioName(kind: Int, flags: Int): String = when (kind) {
        DriverProto.DEV_RTL_TCP -> "RTL-SDR (rtl_tcp)"
        DriverProto.DEV_RTL_USB -> "RTL-SDR (USB)"
        DriverProto.DEV_HACKRF -> "HackRF"
        DriverProto.DEV_FLEX -> "FlexRadio"
        DriverProto.DEV_HL2 ->
            if (flags and DriverProto.OPEN_FLAG_CLASSIC_BOARD != 0) "ANAN (Protocol 1)"
            else "Hermes-Lite 2"
        DriverProto.DEV_G2 -> "ANAN-G2 (Saturn)"
        else -> "?"
    }

    /** Take ownership of a board, closing whichever session held it. */
    private fun claimDevice(key: String) {
        currentDeviceKey = key
        val prev = deviceOwners.put(key, this)
        if (prev != null && prev !== this) {
            Log.w(TAG, "device $key claimed by a new session — closing the stale owner")
            runCatching { prev.close() }
        }
    }

    @Volatile private var currentDeviceKey: String? = null

    private fun closeDevice() {
        currentDeviceKey?.let { deviceOwners.remove(it, this); currentDeviceKey = null }
        // Invalidate the outgoing clients' callbacks FIRST: their disconnect
        // paths are asynchronous and must not speak for the next client.
        clientGen.incrementAndGet()
        val hadClient = radio != null
        try {
            // Never leave the air keyed behind a closing session.
            (radio as? TransmitCapable)?.setPtt(false)
        } catch (_: Exception) {
        }
        try {
            radio?.disconnect()
        } catch (_: Exception) {
        }
        radio = null
        rtlTcp = null
        rtlUsb = null
        hackRf = null
        hl2 = null
        g2 = null
        DriverServiceState.update {
            it.copy(radio = null, radioStatus = null, radioConnected = false)
        }
        // Deterministic close status: the clients' own "Disconnected" events
        // are generation-filtered out, so emit the terminal status here, in
        // order, before any successor's "Connected" can be enqueued.
        if (hadClient && !closed) {
            enqueue(OutEntry(0, null, 0, { frames.writeStatus(false, "Disconnected") }, droppable = false))
        }
    }
}
