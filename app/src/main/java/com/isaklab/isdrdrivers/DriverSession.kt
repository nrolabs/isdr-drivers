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
import com.isaklab.isdrdrivers.core.CatControlCapable
import com.isaklab.isdrdrivers.core.CatRepeaterCapable
import com.isaklab.isdrdrivers.core.TransmitCapable
import com.isaklab.isdrdrivers.core.TxDriveCapable
import com.isaklab.isdrdrivers.core.TxTimingCapable
import com.isaklab.isdrproto.Frame
import com.isaklab.isdrproto.Frames
import com.isaklab.isdrproto.BoardControls
import com.isaklab.isdrproto.DriverProto
import com.isaklab.isdrproto.IqCodec
import com.isaklab.isdrproto.SpectrumCodec
import com.isaklab.isdrproto.RadioTelemetry
import com.isaklab.isdrproto.CatRepeaterConfig
import com.isaklab.isdrproto.ReceiverWireContract
import com.isaklab.isdrproto.getBool
import com.isaklab.isdrproto.getFloats
import com.isaklab.isdrproto.getUtf
import com.isaklab.libcivk.CivClient
import com.isaklab.libcivk.TcpTransport
import com.isaklab.libcivk.UsbCdcTransport
import com.isaklab.libkenwoodk.KenwoodClient
import com.isaklab.libg2sdrk.G2Client
import com.isaklab.libg2sdrk.G2Protocol
import com.isaklab.libhackrfk.HackRfClient
import com.isaklab.libhackrfk.HackRfProtocol
import com.isaklab.libhl2sdrk.Hl2Client
import com.isaklab.libhl2sdrk.Hl2Protocol
import com.isaklab.libhl2sdrk.Protocol1Profile
import com.isaklab.libhl2sdrk.Protocol1Discovery
import com.isaklab.librtlsdrk.RTLCommand
import com.isaklab.librtlsdrk.RTLTCPClient
import com.isaklab.librtlsdrk.RTLUSBClient
import com.isaklab.librtlsdrk.RtlTunerInfo
import java.nio.ByteBuffer
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.BufferUnderflowException
import java.net.InetAddress
import java.net.Socket
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Build the append-only EV_HRF_INFO failure word. Unsupported diagnostics do
 * not count as failed queries; the supported-controls contract describes
 * availability independently.
 */
internal fun hackRfInfoQueryFailedMask(
    firmwareQueryFailed: Boolean,
    boardIdQueryFailed: Boolean,
    serialQueryFailed: Boolean,
    boardRevisionQueryFailed: Boolean,
    platformQueryFailed: Boolean,
    supportsClkinQuery: Boolean,
    clkinResult: Boolean?,
    supportsOperaCakeQuery: Boolean,
    boardsResult: IntArray?,
    supportsCpldQuery: Boolean,
    cpldResult: Long?,
    modeResult: Int?,
): Int {
    var mask = 0
    if (firmwareQueryFailed) {
        mask = mask or DriverProto.HRF_INFO_QUERY_FAILED_FIRMWARE
    }
    if (boardIdQueryFailed) {
        mask = mask or DriverProto.HRF_INFO_QUERY_FAILED_BOARD_ID
    }
    if (serialQueryFailed) {
        mask = mask or DriverProto.HRF_INFO_QUERY_FAILED_SERIAL
    }
    if (boardRevisionQueryFailed) {
        mask = mask or DriverProto.HRF_INFO_QUERY_FAILED_BOARD_REVISION
    }
    if (platformQueryFailed) {
        mask = mask or DriverProto.HRF_INFO_QUERY_FAILED_PLATFORM
    }
    if (supportsClkinQuery && clkinResult == null) {
        mask = mask or DriverProto.HRF_INFO_QUERY_FAILED_CLKIN
    }
    if (supportsOperaCakeQuery && boardsResult == null) {
        mask = mask or DriverProto.HRF_INFO_QUERY_FAILED_OPERACAKE_BOARDS
    }
    if (supportsCpldQuery && cpldResult == null) {
        mask = mask or DriverProto.HRF_INFO_QUERY_FAILED_CPLD_CHECKSUM
    }
    if (boardsResult?.isNotEmpty() == true && modeResult == null) {
        mask = mask or DriverProto.HRF_INFO_QUERY_FAILED_OPERACAKE_MODE
    }
    return mask
}

/** Canonical EV_RTL_INFO encoder shared with byte-parity tests. */
internal fun encodeRtlInfo(info: RtlTunerInfo): ByteBuffer {
    val name = info.tunerName.toByteArray(Charsets.UTF_8)
    val gains = info.gainsTenthsDb
    return ByteBuffer.allocate(4 + 2 + name.size + 1 + 4 + gains.size * 4).apply {
        putInt(info.tunerType)
        putShort(name.size.toShort())
        put(name)
        put(if (info.gainTableKnown) 1 else 0)
        putInt(gains.size)
        gains.forEach { putInt(it) }
        flip()
    }
}

/**
 * Stable identity of the physical endpoint a CMD_OPEN will actually use.
 * Defaults and documented aliases must collapse to one key, otherwise two
 * sessions can bypass the single-owner guard while driving the same radio.
 */
internal fun normalizedDeviceKey(
    kind: Int,
    host: String,
    port: Int,
    flags: Int,
    verifiedHost: String? = null,
): String {
    fun normalizedHost(value: String): String =
        value.trim().removeSuffix(".").lowercase(Locale.ROOT)
    fun resolvedHost(value: String): String {
        val normalized = normalizedHost(value)
        if (normalized.isEmpty()) return normalized
        return runCatching { InetAddress.getByName(normalized).hostAddress }
            .getOrNull()
            ?.let(::normalizedHost)
            ?: normalized
    }

    return when (kind) {
        DriverProto.DEV_RTL_USB -> "$kind/usb"
        DriverProto.DEV_HACKRF -> "$kind/usb"
        DriverProto.DEV_ICOM_IQ -> "$kind/usb"
        DriverProto.DEV_RTL_TCP -> "$kind/${resolvedHost(host)}:$port"
        DriverProto.DEV_HPSDR_P1 -> {
            val target = resolvedHost(
                verifiedHost ?: host.ifEmpty { Hl2Client.BROADCAST },
            )
            val targetPort = if (port > 0) port else Hl2Protocol.PORT
            "$kind/$target:$targetPort"
        }
        // G2 discovery currently occurs inside connect() and CMD_OPEN's port
        // is ignored. Until its verified IP is exposed, one conservative key
        // prevents broadcast and explicit aliases from driving one board.
        DriverProto.DEV_G2 -> "$kind/protocol2"
        DriverProto.DEV_CAT -> {
            val dialect = DriverProto.catDialect(flags)
            val target = if (dialect == DriverProto.CAT_DIALECT_KENWOOD) {
                val at = host.lastIndexOf('@')
                if (at >= 0) host.substring(at + 1) else host
            } else {
                host
            }
            if (target.isEmpty() || target == "usb") {
                // Baud does not identify the USB adapter. Empty and "usb"
                // both open the same first compatible CDC device.
                "$kind/usb"
            } else {
                val defaultPort = if (dialect == DriverProto.CAT_DIALECT_KENWOOD) 60000 else 4532
                val targetPort = if (port > 0) port else defaultPort
                "$kind/${resolvedHost(target)}:$targetPort"
            }
        }
        else -> "$kind/${normalizedHost(host)}:$port"
    }
}

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
    /**
     * A retained adapter object is not proof that its transport is alive.
     * Every terminal command is refused once the current-generation client
     * reports disconnect, so an adapter's best-effort/no-session path can
     * never be promoted to COMMAND_ACCEPTED.
     */
    @Volatile private var radioControlReady = false

    /** Authoritative receiver leaf state used to reject, never normalise, a transition. */
    @Volatile private var receiverCount = 1
    /** Exact physical/profile ceiling derived from the accepted OPEN identity. */
    @Volatile private var receiverCapacity = 1
    @Volatile private var activeReceiver = 0
    @Volatile private var rxStreamMask = 0
    /** Confirmed semantic diversity state; never inferred from [rxStreamMask]. */
    @Volatile private var diversitySupported = false
    @Volatile private var diversityEnabled = false
    @Volatile private var diversityReference = 0
    @Volatile private var diversityMemberMask = 0

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
    private fun publishShm(
        type: Int,
        a: Int,
        fft: FloatArray,
        iq: FloatArray,
        tag: Int,
        epoch: OpenEpochGate.Epoch,
    ): Boolean {
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
        enqueue(
            OutEntry(
                DriverProto.EV_SHM_FRAME,
                b,
                8,
                null,
                droppable = true,
                epoch = epoch,
            ),
        )
        return true
    }

    /** Discard shared-memory frames belonging to a revoked radio generation. */
    private fun discardShmBacklog() {
        synchronized(shmLock) { shmRing?.resetForAttach() }
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
    private var cat: CivClient? = null
    private var kenwoodCat: KenwoodClient? = null
    private val repeaterCommandInFlight = AtomicBoolean(false)
    private val repeaterTerminalLock = Object()
    private var repeaterTerminalTail: CompletableDeferred<Unit>? = null

    /** One universal publication gate for every asynchronous driver callback. */
    private val openEpochs = OpenEpochGate()
    /** Serialises local status effects after the epoch decision is made. */
    private val statusDeliveryLock = Any()
    /** Hold synchronous/callback readbacks until their command terminal enters FIFO. */
    private val commandReplyLock = Any()
    private var commandReplyEpoch: OpenEpochGate.Epoch? = null
    private var commandReplies: MutableList<OutEntry>? = null
    /** Protects only session pointers; hardware calls always happen outside it. */
    private val radioLock = Any()

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
        val epoch: OpenEpochGate.Epoch? = null,
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
                // Revalidate at the sole physical writer, not merely when a
                // callback enqueues. If revocation wins, the stale payload is
                // recycled. If this check wins, FIFO guarantees this frame
                // completes before every later close/new-OPEN terminal.
                if (e.epoch != null && !openEpochs.isPublished(e.epoch)) {
                    e.buf?.let(::recycleBuf)
                    continue
                }
                try {
                    if (e.run != null) e.run.invoke()
                    else frames.writeRaw(e.op, e.buf!!, e.len)
                } finally {
                    e.buf?.let(::recycleBuf)
                }
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
        val shutdown = openEpochs.shutdown()
        if (!shutdown.first) return
        closed = true
        (currentRadio() as? CatRepeaterCapable)?.requestCatRepeaterCancelForUnkey()
        openEpochs.awaitQuiescent(shutdown.second)
        discardShmBacklog()
        val detached = detachRadio()
        disconnectRadio(detached)
        openEpochs.awaitAttempts(shutdown.second)
        // A connect() that allocated resources after the first cancellation
        // request must still reach an idempotent terminal teardown before EOF.
        disconnectRadio(detached)
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
    /** False after an unconfirmed TX tune; key-down then fails closed. */
    @Volatile private var txFrequencyReady = true
    @Volatile private var lastTxIqMs = com.isaklab.isdrdrivers.core.TxWatchdogPolicy.NOT_ARMED

    /** Monotonic instant of the last key-down (for the no-stream ceilings). */
    @Volatile private var keyedAtMs = 0L

    private fun startTxWatchdog() {
        scope.launch {
            while (!closed) {
                kotlinx.coroutines.delay(250)
                runTxWatchdogTick(android.os.SystemClock.elapsedRealtime())
            }
        }
    }

    /** One generation-bound watchdog decision, split out for race tests. */
    private fun runTxWatchdogTick(now: Long): Boolean {
        val epoch = openEpochs.publishedEpoch() ?: return false
        val lease = openEpochs.acquirePublished(epoch) ?: return false
        try {
            val current = currentRadio() ?: return false
            val last = lastTxIqMs
            if (!com.isaklab.isdrdrivers.core.TxWatchdogPolicy
                    .shouldUnkeyFor(
                        pttOn,
                        keyedAtMs,
                        last,
                        now,
                        streamsTxIq = current !is CatControlCapable,
                    )
            ) {
                return false
            }
            Log.w(
                TAG,
                "transmit watchdog: keyed ${now - keyedAtMs} ms, last IQ " +
                    "${if (last == 0L) "never" else "${now - last} ms ago"} — unkeying",
            )
            pttOn = false
            lastTxIqMs = com.isaklab.isdrdrivers.core.TxWatchdogPolicy.NOT_ARMED
            try {
                // The epoch lease prevents handover from installing B while
                // an expiry decision captured for A is still unkeying A.
                (current as? TransmitCapable)?.setPtt(false)
            } catch (e: Exception) {
                Log.e(TAG, "watchdog could not unkey: ${e.message}")
            }
            enqueueTxState(epoch, current)
            return true
        } finally {
            openEpochs.release(lease)
        }
    }

    /** Last spectrum actually put on the wire; see [onData]. */
    private var lastSentSpectrum: FloatArray? = null

    private fun onData(epoch: OpenEpochGate.Epoch, fft: FloatArray, iq: FloatArray) {
        val tag = flushSeq
        flushSeq = (flushSeq + 1) and 0x7fffffff
        if (publishShm(DriverProto.EV_DATA, fft.size, fft, iq, tag, epoch)) return
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
        enqueue(
            OutEntry(
                DriverProto.EV_DATA,
                b,
                len,
                null,
                droppable = true,
                epoch = epoch,
            ),
        )
    }

    private fun onDataRx(epoch: OpenEpochGate.Epoch, rx: Int, iq: FloatArray) {
        val tag = flushSeq                       // seq of the flush's EV_DATA
        if (publishShm(DriverProto.EV_DATA_RX, rx, EMPTY_FLOATS, iq, tag, epoch)) return
        val fmt = DriverProto.IQ_WIRE_FORMAT
        val len = 12 + IqCodec.encodedSize(fmt, iq.size)
        val b = obtainBuf(len)
        val bb = java.nio.ByteBuffer.wrap(b)
        bb.putInt(rx)
        bb.putInt(iq.size)
        bb.position(IqCodec.encode(iq, iq.size, fmt, b, bb.position()))
        bb.putInt(tag)
        enqueue(
            OutEntry(
                DriverProto.EV_DATA_RX,
                b,
                len,
                null,
                droppable = true,
                epoch = epoch,
            ),
        )
    }

    private fun onSweepBlock(
        epoch: OpenEpochGate.Epoch,
        lowerEdgeHz: Long,
        iq: FloatArray,
    ) {
        val len = 12 + iq.size * 4
        val b = obtainBuf(len)
        val bb = java.nio.ByteBuffer.wrap(b)
        bb.putLong(lowerEdgeHz)
        bb.putInt(iq.size)
        bb.asFloatBuffer().put(iq)
        enqueue(
            OutEntry(
                DriverProto.EV_SWEEP_BLOCK,
                b,
                len,
                null,
                droppable = true,
                epoch = epoch,
            ),
        )
    }

    private fun onStatus(
        epoch: OpenEpochGate.Epoch,
        connected: Boolean,
        status: String,
    ) {
        DriverServiceState.update {
            it.copy(radioStatus = status, radioConnected = connected)
        }
        // Through the queue (never dropped) so status stays ordered with the
        // data frames around it.
        enqueue(
            OutEntry(
                0,
                null,
                0,
                { frames.writeStatus(connected, status) },
                droppable = false,
                // A link-down status is the terminal that revoked this epoch;
                // it must survive that revocation. Positive/status-progress
                // callbacks remain generation-tagged.
                epoch = epoch.takeIf { connected },
            ),
        )
    }

    /** Hold pre-open status and reject every stale generation after hand-over. */
    private fun statusFor(epoch: OpenEpochGate.Epoch): (Boolean, String) -> Unit =
        { connected, status ->
            val lease = openEpochs.status(epoch, connected, status)
            if (lease != null) deliverStatusLease(epoch, connected, status, lease)
        }

    /**
     * Apply status effects in epoch order without holding the lifecycle gate.
     * A terminal false revokes the epoch before reaching this method. If an
     * older true callback had already acquired a lease but resumes after that
     * false, the recheck under this delivery lock prevents it from restoring
     * a locally connected/command-ready state.
     */
    private fun deliverStatusLease(
        epoch: OpenEpochGate.Epoch,
        connected: Boolean,
        status: String,
        lease: OpenEpochGate.Lease,
    ) {
        try {
            synchronized(statusDeliveryLock) {
                if (connected && !openEpochs.isPublished(epoch)) return@synchronized
                if (!connected) discardShmBacklog()
                radioControlReady = connected
                onStatus(epoch, connected, status)
            }
        } finally {
            openEpochs.release(lease)
        }
    }

    private fun deliverPublished(epoch: OpenEpochGate.Epoch, block: () -> Unit) {
        val lease = openEpochs.acquirePublished(epoch) ?: return
        try {
            block()
        } finally {
            openEpochs.release(lease)
        }
    }

    private fun dataFor(epoch: OpenEpochGate.Epoch): (FloatArray, FloatArray) -> Unit =
        { fft, iq -> deliverPublished(epoch) { onData(epoch, fft, iq) } }

    private fun dataRxFor(epoch: OpenEpochGate.Epoch): (Int, FloatArray) -> Unit =
        { rx, iq -> deliverPublished(epoch) { onDataRx(epoch, rx, iq) } }

    private fun sweepFor(epoch: OpenEpochGate.Epoch): (Long, FloatArray) -> Unit =
        { lowerEdgeHz, iq ->
            deliverPublished(epoch) { onSweepBlock(epoch, lowerEdgeHz, iq) }
        }

    private fun hl2TelemetryFor(epoch: OpenEpochGate.Epoch): (Hl2Protocol.Telemetry) -> Unit =
        { telemetry -> deliverPublished(epoch) { onHl2Telemetry(epoch, telemetry) } }

    private fun g2StatusFor(epoch: OpenEpochGate.Epoch): (G2Protocol.Status) -> Unit =
        { status -> deliverPublished(epoch) { onG2Status(epoch, status) } }

    private fun hackRfGapsFor(epoch: OpenEpochGate.Epoch): (Long) -> Unit =
        { gaps -> deliverPublished(epoch) { onHackRfGaps(epoch, gaps) } }

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
        enqueueReadback(openEpochs.publishedEpoch()) {
            frames.write(DriverProto.EV_SPECTRUM_ZOOM, b)
        }
    }

    private fun sendTelemetry(epoch: OpenEpochGate.Epoch, t: RadioTelemetry) =
        enqueue(
            OutEntry(
                0,
                null,
                0,
                { frames.writeTelemetry(t) },
                droppable = true,
                epoch = epoch,
            ),
        )

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
        val epoch = openEpochs.publishedEpoch() ?: return
        scope.launch { sendHackRfInfoBlocking(hrf, epoch) }
    }

    private fun sendHackRfInfoBlocking(hrf: HackRfClient, epoch: OpenEpochGate.Epoch) {
        if (!openEpochs.isPublished(epoch)) return
        val info = hrf.boardInfo()
        val clkinResult = hrf.tryClkinStatus()
        val boardsResult = hrf.tryOperacakeBoards()
        val boards = boardsResult ?: IntArray(0)
        val cpldResult = hrf.tryCpldChecksum()
        val modeResult = boards.firstOrNull()?.let(hrf::tryOperacakeGetMode)
        val queryFailedMask = hackRfInfoQueryFailedMask(
            firmwareQueryFailed = info.firmwareQueryFailed,
            boardIdQueryFailed = info.boardIdQueryFailed,
            serialQueryFailed = info.serialQueryFailed,
            boardRevisionQueryFailed = info.boardRevisionQueryFailed,
            platformQueryFailed = info.platformQueryFailed,
            supportsClkinQuery = hrf.supportsApi(HackRfProtocol.API_CLKIN_STATUS),
            clkinResult = clkinResult,
            supportsOperaCakeQuery = hrf.supportsApi(HackRfProtocol.API_OPERACAKE_MODE),
            boardsResult = boardsResult,
            supportsCpldQuery = hrf.supportsApi(HackRfProtocol.API_CPLD_CHECKSUM),
            cpldResult = cpldResult,
            modeResult = modeResult,
        )
        val strings = listOf(
            info.boardName, info.revisionName, info.platformName,
            info.firmwareVersion, info.usbApiName, info.serialNumber,
        )
        val cap = 4 + 4 + 1 + 4 + 4 + 1 + 4 + boards.size * 4 + 8 + 1 + 4 + 1 + 4 + 4 + 4 +
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
        bb.put(if (clkinResult == true) 1 else 0)
        bb.putInt(boards.size)
        boards.forEach { bb.putInt(it) }
        bb.putLong(cpldResult ?: -1L)
        bb.put(if (info.revisionKnown) 1 else 0)
        bb.putInt(hrf.basebandFilterHz())
        bb.put(if (hrf.hasExplicitTuning()) 1 else 0)
        bb.putInt(modeResult ?: -1)
        bb.putInt(hrf.supportedControls())
        bb.putInt(queryFailedMask)
        bb.flip()
        enqueueReadback(epoch) { frames.write(DriverProto.EV_HRF_INFO, bb) }
    }

    private fun sendHackRfM0State() {
        val epoch = openEpochs.publishedEpoch() ?: return
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
        enqueueReadback(epoch) { frames.write(DriverProto.EV_HRF_M0_STATE, bb) }
    }

    /**
     * Self-test runs OFF the session thread: the RTC oscillator check alone
     * sleeps a full second by design, and doing that inline would stall every
     * command queued behind it — including PTT.
     */
    private fun sendHackRfSelfTest() {
        val hrf = hackRf ?: return
        val epoch = openEpochs.publishedEpoch() ?: return
        scope.launch {
            if (!openEpochs.isPublished(epoch)) return@launch
            val test = hrf.readSelfTest()
            val rtc = hrf.testRtcOsc()
            val msg = test?.message ?: ""
            val bb = ByteBuffer.allocate(1 + 2 + msg.toByteArray(Charsets.UTF_8).size + 2)
            bb.put(if (test?.pass == true) 1 else 0)
            bb.putUtf(msg)
            bb.put(if (rtc != null) 1 else 0)
            bb.put(if (rtc == true) 1 else 0)
            bb.flip()
            enqueueReadback(epoch) { frames.write(DriverProto.EV_HRF_SELFTEST, bb) }
        }
    }

    private fun onHl2Telemetry(
        epoch: OpenEpochGate.Epoch,
        t: Hl2Protocol.Telemetry,
    ) = sendTelemetry(
        epoch,
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

    private fun onG2Status(
        epoch: OpenEpochGate.Epoch,
        st: G2Protocol.Status,
    ) = sendTelemetry(
        epoch,
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
    private fun onHackRfGaps(epoch: OpenEpochGate.Epoch, gaps: Long) = sendTelemetry(
        epoch,
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
        val epoch = openEpochs.publishedEpoch() ?: return
        val lease = openEpochs.acquirePublished(epoch) ?: return
        try {
            currentRadio()?.let { enqueueTxState(epoch, it) }
        } finally {
            openEpochs.release(lease)
        }
    }

    private fun enqueueTxState(epoch: OpenEpochGate.Epoch, current: RadioClient) {
        val tx = (current as? TransmitCapable)?.isTransmitting() ?: false
        enqueueReadback(epoch) { frames.writeBool(DriverProto.EV_TX_STATE, tx) }
    }

    /** Commands whose completion is the V2 EV_COMMAND_RESULT contract. */
    private fun needsCommandResult(op: Int): Boolean = when (op) {
        DriverProto.CMD_HELLO,
        DriverProto.CMD_AUTH,
        DriverProto.CMD_OPEN,
        DriverProto.CMD_CLOSE,
        DriverProto.CMD_SHM_ATTACH,
        DriverProto.CMD_TX_IQ,
        DriverProto.CMD_TX_IQ_NARROW -> false
        else -> op in 0x10..0x7F
    }

    /**
     * Validate the complete payload before touching hardware. A valid prefix
     * followed by junk is malformed too; applying the prefix and complaining
     * afterwards would leave the radio changed by a rejected command.
     */
    private fun controlPayloadError(op: Int, p: ByteBuffer): String? {
        val fixed = when (op) {
            DriverProto.CMD_HRF_START_RX,
            DriverProto.CMD_HRF_SWEEP_STOP,
            DriverProto.CMD_HRF_RESET,
            DriverProto.CMD_HRF_QUERY_INFO,
            DriverProto.CMD_HRF_QUERY_M0_STATE,
            DriverProto.CMD_HRF_SELFTEST,
            DriverProto.CMD_HRF_CLEAR_FREQ_EXPLICIT,
            DriverProto.CMD_RTL_QUERY_INFO -> 0

            DriverProto.CMD_SET_SPECTRUM_INTEREST,
            DriverProto.CMD_SET_ANTENNA_POWER,
            DriverProto.CMD_SET_PTT,
            DriverProto.CMD_SET_PA_ENABLED,
            DriverProto.CMD_HL2_SET_VNA_MODE,
            DriverProto.CMD_HL2_SET_TR_DISABLE,
            DriverProto.CMD_HL2_SET_PURESIGNAL,
            DriverProto.CMD_HRF_SET_AMP,
            DriverProto.CMD_HRF_SET_HW_SYNC,
            DriverProto.CMD_HRF_SET_UI_ENABLE,
            DriverProto.CMD_HRF_SET_NARROWBAND_FILTER,
            DriverProto.CMD_HRF_SET_CLKOUT,
            DriverProto.CMD_RTL_SET_GAIN_MODE,
            DriverProto.CMD_RTL_SET_AGC -> 1

            DriverProto.CMD_SET_FREQUENCY,
            DriverProto.CMD_SET_TX_FREQUENCY,
            DriverProto.CMD_SET_FREQUENCY2 -> 8

            DriverProto.CMD_SET_SAMPLE_RATE,
            DriverProto.CMD_SET_ANALOG_FILTER,
            DriverProto.CMD_SET_TX_DRIVE,
            DriverProto.CMD_SET_RECEIVER_COUNT,
            DriverProto.CMD_SET_ACTIVE_RECEIVER,
            DriverProto.CMD_SET_RX_STREAM_MASK,
            DriverProto.CMD_HL2_SET_LNA,
            DriverProto.CMD_HL2_SET_VNA_COUNT,
            DriverProto.CMD_G2_SET_ATTENUATOR,
            DriverProto.CMD_G2_SET_OC_OUTPUTS,
            DriverProto.CMD_HRF_SET_LNA,
            DriverProto.CMD_HRF_SET_VGA,
            DriverProto.CMD_HRF_SET_TXVGA,
            DriverProto.CMD_HRF_SET_LEDS,
            DriverProto.CMD_HRF_SET_CLKIN_CTRL,
            DriverProto.CMD_HRF_SET_P1_CTRL,
            DriverProto.CMD_HRF_SET_P2_CTRL,
            DriverProto.CMD_HRF_SET_TX_UNDERRUN_LIMIT,
            DriverProto.CMD_HRF_SET_RX_OVERRUN_LIMIT,
            DriverProto.CMD_RTL_SET_GAIN,
            DriverProto.CMD_RTL_SET_PPM,
            DriverProto.CMD_RTL_SET_DIRECT_SAMPLING,
            DriverProto.CMD_CAT_SET_MODE -> 4

            DriverProto.CMD_SET_TX_TIMING,
            DriverProto.CMD_HL2_SET_FILTER_OUTPUTS,
            DriverProto.CMD_HRF_OPERACAKE_SET_MODE,
            DriverProto.CMD_CAT_SET_CONTROL -> 8

            DriverProto.CMD_CAT_SET_REPEATER -> DriverProto.CAT_REPEATER_PAYLOAD_LEN

            DriverProto.CMD_SET_SPECTRUM_ZOOM,
            DriverProto.CMD_SET_NARROWBAND,
            DriverProto.CMD_SET_RX_FREQUENCY,
            DriverProto.CMD_HL2_SET_AMP_KEY,
            DriverProto.CMD_HRF_OPERACAKE_SET_PORTS -> 12

            DriverProto.CMD_HRF_SWEEP_START -> 16
            DriverProto.CMD_HRF_SET_FREQ_EXPLICIT -> 20
            DriverProto.CMD_HL2_SET_CW_KEYER -> 23
            DriverProto.CMD_HL2_SET_IOBOARD,
            DriverProto.CMD_HRF_SET_BIAS_T_OPTS,
            DriverProto.CMD_SET_DIVERSITY -> 9
            else -> null
        }
        if (fixed != null) {
            if (p.remaining() != fixed) {
                return "payload length ${p.remaining()}, expected $fixed"
            }
            if (op == DriverProto.CMD_CAT_SET_REPEATER) {
                val bytes = ByteArray(p.remaining())
                p.duplicate().get(bytes)
                if (CatRepeaterConfig.decode(bytes) == null) {
                    return "invalid CAT repeater state"
                }
            }
            return null
        }
        if (op == DriverProto.CMD_HRF_OPERACAKE_SET_RANGES ||
            op == DriverProto.CMD_HRF_OPERACAKE_SET_DWELL
        ) {
            if (p.remaining() < 4) return "missing entry count"
            val n = p.duplicate().int
            val max = if (op == DriverProto.CMD_HRF_OPERACAKE_SET_RANGES) 8 else 16
            val stride = if (op == DriverProto.CMD_HRF_OPERACAKE_SET_RANGES) 12 else 8
            if (n !in 1..max) return "entry count $n outside 1..$max"
            val expected = 4 + n * stride
            if (p.remaining() != expected) {
                return "payload length ${p.remaining()}, expected $expected for $n entries"
            }
            return null
        }
        return "unknown control opcode 0x${op.toString(16)}"
    }

    /** Refuse a control that the active adapter cannot implement. */
    private fun supportFailure(op: Int): Pair<Int, String>? {
        val r = radio ?: return DriverProto.COMMAND_NO_RADIO to "no radio is open"
        if (!radioControlReady) {
            return DriverProto.COMMAND_NO_RADIO to "radio control transport is not connected"
        }
        val supported = when (op) {
            DriverProto.CMD_SET_FREQUENCY,
            DriverProto.CMD_SET_SAMPLE_RATE,
            DriverProto.CMD_SET_SPECTRUM_INTEREST -> true
            DriverProto.CMD_SET_ANTENNA_POWER -> r is AntennaPowerCapable
            DriverProto.CMD_SET_ANALOG_FILTER -> r is AnalogFilterCapable
            DriverProto.CMD_SET_SPECTRUM_ZOOM ->
                r is Hl2Client || r is G2Client || r is RTLTCPClient ||
                    r is RTLUSBClient || r is HackRfClient
            // Narrowband is supplied by the station bridge, never by a radio
            // adapter in the local driver host.
            DriverProto.CMD_SET_NARROWBAND -> false
            DriverProto.CMD_SET_TX_FREQUENCY,
            DriverProto.CMD_SET_PTT -> r is TransmitCapable
            DriverProto.CMD_SET_TX_DRIVE -> r is TxDriveCapable || r is CatControlCapable
            DriverProto.CMD_SET_PA_ENABLED -> r is TxDriveCapable
            DriverProto.CMD_SET_TX_TIMING -> r is TxTimingCapable
            DriverProto.CMD_SET_RECEIVER_COUNT,
            DriverProto.CMD_SET_ACTIVE_RECEIVER,
            DriverProto.CMD_SET_RX_FREQUENCY,
            DriverProto.CMD_SET_RX_STREAM_MASK -> r is Hl2Client || r is G2Client
            DriverProto.CMD_SET_DIVERSITY ->
                (r is Hl2Client && r.supportsDiversity()) || r is G2Client
            // Superseded by the indexed command. Keeping two leaf grammars
            // lets an old caller bypass the atomic ReceiverControlContract.
            DriverProto.CMD_SET_FREQUENCY2 -> false
            DriverProto.CMD_HL2_SET_PURESIGNAL ->
                (r is Hl2Client && r.supportsPureSignal()) || r is G2Client
            DriverProto.CMD_HL2_SET_LNA,
            DriverProto.CMD_HL2_SET_VNA_MODE,
            DriverProto.CMD_HL2_SET_TR_DISABLE,
            DriverProto.CMD_HL2_SET_FILTER_OUTPUTS,
            DriverProto.CMD_HL2_SET_AMP_KEY,
            DriverProto.CMD_HL2_SET_VNA_COUNT,
            DriverProto.CMD_HL2_SET_IOBOARD,
            DriverProto.CMD_HL2_SET_CW_KEYER -> r is Hl2Client
            DriverProto.CMD_G2_SET_ATTENUATOR,
            DriverProto.CMD_G2_SET_OC_OUTPUTS -> r is G2Client
            DriverProto.CMD_HRF_SET_LNA,
            DriverProto.CMD_HRF_SET_VGA,
            DriverProto.CMD_HRF_SET_TXVGA,
            DriverProto.CMD_HRF_SET_AMP,
            DriverProto.CMD_HRF_START_RX,
            DriverProto.CMD_HRF_SWEEP_START,
            DriverProto.CMD_HRF_SWEEP_STOP,
            DriverProto.CMD_HRF_SET_FREQ_EXPLICIT,
            DriverProto.CMD_HRF_SET_BIAS_T_OPTS,
            DriverProto.CMD_HRF_SET_HW_SYNC,
            DriverProto.CMD_HRF_SET_UI_ENABLE,
            DriverProto.CMD_HRF_SET_LEDS,
            DriverProto.CMD_HRF_SET_NARROWBAND_FILTER,
            DriverProto.CMD_HRF_SET_CLKOUT,
            DriverProto.CMD_HRF_SET_CLKIN_CTRL,
            DriverProto.CMD_HRF_SET_P1_CTRL,
            DriverProto.CMD_HRF_SET_P2_CTRL,
            DriverProto.CMD_HRF_SET_TX_UNDERRUN_LIMIT,
            DriverProto.CMD_HRF_SET_RX_OVERRUN_LIMIT,
            DriverProto.CMD_HRF_OPERACAKE_SET_PORTS,
            DriverProto.CMD_HRF_OPERACAKE_SET_MODE,
            DriverProto.CMD_HRF_OPERACAKE_SET_RANGES,
            DriverProto.CMD_HRF_OPERACAKE_SET_DWELL,
            DriverProto.CMD_HRF_RESET,
            DriverProto.CMD_HRF_QUERY_INFO,
            DriverProto.CMD_HRF_QUERY_M0_STATE,
            DriverProto.CMD_HRF_SELFTEST,
            DriverProto.CMD_HRF_CLEAR_FREQ_EXPLICIT -> r is HackRfClient
            DriverProto.CMD_RTL_SET_GAIN,
            DriverProto.CMD_RTL_SET_GAIN_MODE,
            DriverProto.CMD_RTL_SET_AGC,
            DriverProto.CMD_RTL_SET_PPM,
            DriverProto.CMD_RTL_QUERY_INFO -> r is RTLTCPClient || r is RTLUSBClient
            DriverProto.CMD_RTL_SET_DIRECT_SAMPLING -> r is RTLTCPClient || r is RTLUSBClient
            DriverProto.CMD_CAT_SET_MODE,
            DriverProto.CMD_CAT_SET_CONTROL -> r is CatControlCapable
            DriverProto.CMD_CAT_SET_REPEATER ->
                r is CatRepeaterCapable && r.catRepeaterCapabilities() != 0
            else -> false
        }
        if (!supported) {
            return DriverProto.COMMAND_UNSUPPORTED to
                "opcode 0x${op.toString(16)} is unsupported by ${r.javaClass.simpleName}"
        }
        if (r is HackRfClient) {
            val requiredControl = when (op) {
                DriverProto.CMD_HRF_SET_BIAS_T_OPTS -> BoardControls.ANTENNA_POWER_PER_MODE
                DriverProto.CMD_HRF_SET_HW_SYNC -> BoardControls.HARDWARE_SYNC
                DriverProto.CMD_HRF_SET_UI_ENABLE -> BoardControls.BOARD_UI
                DriverProto.CMD_HRF_SET_LEDS -> BoardControls.PANEL_LEDS
                DriverProto.CMD_HRF_SET_NARROWBAND_FILTER -> BoardControls.NARROWBAND_FILTER
                DriverProto.CMD_HRF_SET_CLKOUT -> BoardControls.CLOCK_OUTPUT
                DriverProto.CMD_HRF_SET_CLKIN_CTRL,
                DriverProto.CMD_HRF_SET_P1_CTRL,
                DriverProto.CMD_HRF_SET_P2_CTRL -> BoardControls.CLOCK_INPUT_SELECT
                DriverProto.CMD_HRF_SET_TX_UNDERRUN_LIMIT,
                DriverProto.CMD_HRF_SET_RX_OVERRUN_LIMIT -> BoardControls.WATCHDOG_LIMITS
                DriverProto.CMD_HRF_OPERACAKE_SET_PORTS -> BoardControls.ANTENNA_SWITCH_PORTS
                DriverProto.CMD_HRF_OPERACAKE_SET_MODE -> BoardControls.ANTENNA_SWITCH_MODE
                DriverProto.CMD_HRF_OPERACAKE_SET_RANGES,
                DriverProto.CMD_HRF_OPERACAKE_SET_DWELL -> BoardControls.ANTENNA_SWITCH_TABLES
                DriverProto.CMD_HRF_RESET -> BoardControls.RESET
                DriverProto.CMD_HRF_QUERY_M0_STATE -> BoardControls.STREAM_COUNTERS
                DriverProto.CMD_HRF_SELFTEST -> BoardControls.SELF_TEST
                else -> 0
            }
            if (requiredControl != 0 &&
                !BoardControls.supports(r.supportedControls(), requiredControl)
            ) {
                return DriverProto.COMMAND_UNSUPPORTED to
                    "HackRF did not advertise board control 0x${requiredControl.toString(16)}"
            }
        }
        return null
    }

    private fun enqueueReadback(epoch: OpenEpochGate.Epoch?, write: () -> Unit) {
        synchronized(commandReplyLock) {
            val entry = OutEntry(0, null, 0, write, droppable = false, epoch = epoch)
            val held = commandReplies
            if (held != null && commandReplyEpoch === epoch) held.add(entry)
            else enqueue(entry)
        }
    }

    private fun sendCommandResult(op: Int, disposition: Int, detail: String = "") {
        synchronized(commandReplyLock) {
            enqueue(OutEntry(0, null, 0, {
                frames.writeCommandResult(op, disposition, detail)
            }, droppable = false, epoch = commandReplyEpoch))
            commandReplies?.forEach(::enqueue)
            commandReplies = null
            commandReplyEpoch = null
        }
    }

    /**
     * Repeater completion is deferred while the session reader remains free
     * for priority PTT OFF. V3 associates generic results FIFO by opcode, so
     * every repeater request -- including malformed and duplicate requests --
     * reserves a terminal slot before any asynchronous work can complete.
     */
    private fun handleCatRepeater(frame: Frame) {
        val epoch = openEpochs.publishedEpoch()
        val payload = ByteArray(frame.payload.remaining())
        frame.payload.duplicate().get(payload)
        val malformed = controlPayloadError(
            DriverProto.CMD_CAT_SET_REPEATER,
            ByteBuffer.wrap(payload),
        )
        val unsupported = if (malformed == null) {
            supportFailure(DriverProto.CMD_CAT_SET_REPEATER)
        } else {
            null
        }
        val config = if (malformed == null && unsupported == null) {
            CatRepeaterConfig.decode(payload)
        } else {
            null
        }
        val repeater = if (config != null) radio as CatRepeaterCapable else null
        val admitted = repeater != null && repeaterCommandInFlight.compareAndSet(false, true)
        val terminal = CompletableDeferred<Unit>()
        val predecessor = synchronized(repeaterTerminalLock) {
            val previous = repeaterTerminalTail
            repeaterTerminalTail = terminal
            previous
        }

        scope.launch {
            val lease = epoch?.let(openEpochs::acquirePublished)
            val (disposition, detail) = when {
                malformed != null -> DriverProto.COMMAND_MALFORMED to malformed
                unsupported != null -> unsupported
                lease == null -> DriverProto.COMMAND_NO_RADIO to "radio session was closed"
                !admitted -> DriverProto.COMMAND_REJECTED to
                    "another CAT repeater transaction is active"
                else -> {
                    val failure = try {
                        repeater!!.setCatRepeater(config!!)
                    } catch (e: Exception) {
                        e.message ?: e.javaClass.simpleName
                    }
                    if (failure == null) {
                        DriverProto.COMMAND_ACCEPTED to ""
                    } else {
                        DriverProto.COMMAND_REJECTED to failure
                    }
                }
            }
            try {
                predecessor?.await()
                enqueue(OutEntry(0, null, 0, {
                    frames.writeCommandResult(DriverProto.CMD_CAT_SET_REPEATER, disposition, detail)
                }, droppable = false, epoch = epoch))
            } finally {
                lease?.let(openEpochs::release)
                if (admitted) repeaterCommandInFlight.set(false)
                terminal.complete(Unit)
                synchronized(repeaterTerminalLock) {
                    if (repeaterTerminalTail === terminal) repeaterTerminalTail = null
                }
            }
        }
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
        if (frame.op == DriverProto.CMD_CAT_SET_REPEATER) {
            handleCatRepeater(frame)
            return
        }
        val terminal = needsCommandResult(frame.op)
        if (terminal) {
            controlPayloadError(frame.op, p)?.let { detail ->
                sendCommandResult(frame.op, DriverProto.COMMAND_MALFORMED, detail)
                return
            }
            supportFailure(frame.op)?.let { (disposition, detail) ->
                sendCommandResult(frame.op, disposition, detail)
                return
            }
            synchronized(commandReplyLock) {
                commandReplyEpoch = openEpochs.publishedEpoch()
                commandReplies = mutableListOf()
            }
        }
        try {
        when (frame.op) {
            DriverProto.CMD_HELLO -> {
                if (p.remaining() != 4) throw IOException("invalid CMD_HELLO length ${p.remaining()}")
                val version = p.int
                val features = DriverProto.FEAT_RX_STREAMS or
                    DriverProto.FEAT_SEQ_TAG or
                    DriverProto.FEAT_HPSDR_EXACT_PROFILE or
                    DriverProto.FEAT_RX_ADC_ROUTING or
                    DriverProto.FEAT_RF_PATH_CONTROL or
                    DriverProto.FEAT_ANTENNA_SWITCH or
                    DriverProto.FEAT_CLOCK_TRIGGER or
                    DriverProto.FEAT_BOARD_DIAGNOSTICS or
                    DriverProto.FEAT_COMMAND_RESULTS or
                    DriverProto.FEAT_RTL_GAIN_TABLE or
                    DriverProto.FEAT_CAT_REPEATER or
                    DriverProto.FEAT_CAT_PROFILE_GUARD or
                    // ashmem SharedMemory needs API 27; older devices simply
                    // never advertise the ring and stay on TCP frames.
                    (if (android.os.Build.VERSION.SDK_INT >= 27) DriverProto.FEAT_SHM_RING else 0)
                send { frames.writeHello(DriverProto.VERSION, features) }
                if (version != DriverProto.VERSION) {
                    Log.w(TAG, "protocol mismatch: app=$version host=${DriverProto.VERSION}")
                    close()
                }
            }
            DriverProto.CMD_AUTH -> {
                val token = p.getUtf()
                if (p.hasRemaining()) throw IOException("tail on CMD_AUTH")
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
                if (p.hasRemaining()) throw IOException("tail on CMD_OPEN")
                when (val begun = openEpochs.begin()) {
                    is OpenEpochGate.Begin.Started ->
                        scope.launch {
                            val attempt = openEpochs.acquireAttempt(begun.epoch)
                                ?: return@launch
                            try {
                                openDevice(begun.epoch, kind, host, port, flags)
                            } finally {
                                openEpochs.releaseAttempt(attempt)
                            }
                        }
                    is OpenEpochGate.Begin.SessionViolation -> {
                        Log.w(TAG, "second CMD_OPEN while an OPEN is pending — closing session")
                        // V2 has no request id on EV_OPEN_RESULT. A second
                        // in-flight OPEN cannot be answered without falsely
                        // correlating one result, so EOF is the only honest
                        // terminal for both requests.
                        openEpochs.awaitQuiescent(begun.retired)
                        discardShmBacklog()
                        val detached = detachRadio()
                        disconnectRadio(detached)
                        openEpochs.awaitAttempts(begun.retired)
                        disconnectRadio(detached)
                        close()
                    }
                    OpenEpochGate.Begin.Closed -> Unit
                }
            }
            DriverProto.CMD_CLOSE -> {
                if (p.hasRemaining()) throw IOException("payload on CMD_CLOSE")
                closeDevice()
            }

            DriverProto.CMD_SHM_ATTACH -> {
                if (p.remaining() != 1) throw IOException("invalid CMD_SHM_ATTACH length ${p.remaining()}")
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
                // Clear first: an exception or a false result must not leave
                // a previous successful tune authorizing the following PTT.
                txFrequencyReady = false
                val r = radio ?: throw IllegalStateException("no radio is open")
                if (r is CatControlCapable) {
                    // CAT repeater/split state is configured atomically by
                    // CMD_CAT_SET_REPEATER. The ordinary TX-frequency
                    // command is therefore a simplex barrier: it may confirm
                    // the current RX frequency, but must never rewrite VFO B,
                    // split, or the repeater state behind that contract.
                    val rxHz = r.frequencyHz()
                    if (rxHz <= 0L) {
                        throw IllegalStateException(
                            "CAT RX frequency is unknown; TX frequency was not confirmed",
                        )
                    }
                    if (hz != rxHz) {
                        throw IllegalStateException(
                            "CAT simplex TX frequency $hz does not match RX frequency $rxHz",
                        )
                    }
                } else {
                    val applied = (r as TransmitCapable).setTxFrequency(hz)
                    if (!applied) {
                        throw IllegalStateException(
                            "TX frequency was not confirmed without changing RX",
                        )
                    }
                }
                txFrequencyReady = true
            }
            DriverProto.CMD_SET_PTT -> p.getBool().let { on ->
                if (on && !txFrequencyReady) {
                    throw IllegalStateException("PTT blocked after an unconfirmed TX frequency")
                }
                if (!on) {
                    // Set intent before waiting for the driver's fair CAT
                    // bus lock. The worker finishes/drains its current frame,
                    // then the unkey is the next command on the wire.
                    (radio as? CatRepeaterCapable)?.requestCatRepeaterCancelForUnkey()
                }
                val tx = radio as TransmitCapable
                tx.setPtt(on)
                val actual = tx.isTransmitting()
                pttOn = actual
                // Cleared either way: the watchdog re-arms only when transmit
                // samples actually start flowing, so a mode that keys without
                // a stream of its own is never cut short by it.
                lastTxIqMs = com.isaklab.isdrdrivers.core.TxWatchdogPolicy.NOT_ARMED
                // The key-down instant anchors the no-stream and absolute
                // ceilings: without it a keyed radio whose client died before
                // the first sample had NO watchdog at all.
                if (actual) keyedAtMs = android.os.SystemClock.elapsedRealtime()
                sendTxState()
                if (actual != on) {
                    throw IllegalStateException("PTT was not confirmed")
                }
            }
            DriverProto.CMD_SET_TX_DRIVE -> p.int.let { level ->
                when (val r = radio) {
                    is CatControlCapable -> {
                        val applied = r.setCatControl(DriverProto.CATCTL_RF_POWER, level)
                        enqueueReadback(openEpochs.publishedEpoch()) {
                            frames.writeCatControlResult(
                                DriverProto.CATCTL_RF_POWER,
                                level,
                                applied,
                                superseded = false,
                            )
                        }
                        if (!applied) {
                            throw IllegalStateException("CAT RF power was not confirmed")
                        }
                    }
                    is TxDriveCapable -> r.setTxDrive(level)
                    else -> throw IllegalStateException("TX drive is unsupported")
                }
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
                if (n !in 1..receiverCapacity) {
                    throw IllegalArgumentException(
                        "receiver count $n exceeds opened profile capacity $receiverCapacity",
                    )
                }
                ReceiverWireContract.countError(activeReceiver, rxStreamMask, n)?.let {
                    throw IllegalArgumentException(it)
                }
                if (diversityEnabled && n < 2) {
                    throw IllegalArgumentException(
                        "receiver count $n would remove the active diversity pair",
                    )
                }
                hl2?.setReceiverCount(n)
                g2?.setReceiverCount(n)
                receiverCount = n
            }
            DriverProto.CMD_SET_ACTIVE_RECEIVER -> p.int.let { i ->
                ReceiverWireContract.activeReceiverError(receiverCount, rxStreamMask, i)?.let {
                    throw IllegalArgumentException(it)
                }
                if (diversityEnabled && i != diversityReference) {
                    throw IllegalArgumentException(
                        "disable diversity before changing its reference receiver",
                    )
                }
                hl2?.setActiveReceiver(i)
                g2?.setActiveReceiver(i)
                activeReceiver = i
                if (!diversityEnabled) diversityReference = i
            }
            DriverProto.CMD_SET_FREQUENCY2 -> p.long.let { hz ->
                hl2?.setFrequency2(hz)
                g2?.setFrequency2(hz)
            }
            DriverProto.CMD_SET_RX_FREQUENCY -> {
                val idx = p.int
                val hz = p.long
                ReceiverWireContract.receiverIndexError(receiverCount, idx)?.let {
                    throw IllegalArgumentException(it)
                }
                hl2?.setRxFrequency(idx, hz)
                g2?.setRxFrequency(idx, hz)
            }
            DriverProto.CMD_SET_RX_STREAM_MASK -> p.int.let { mask ->
                ReceiverWireContract.streamMaskError(receiverCount, activeReceiver, mask)?.let {
                    throw IllegalArgumentException(it)
                }
                hl2?.setRxStreamMask(mask)
                g2?.setRxStreamMask(mask)
                rxStreamMask = mask
            }
            DriverProto.CMD_SET_DIVERSITY -> {
                val enabled = p.getBool()
                val reference = p.int
                val members = p.int
                if (reference != activeReceiver) {
                    throw IllegalArgumentException(
                        "diversity reference $reference is not active receiver $activeReceiver",
                    )
                }
                val allowed = (1 shl receiverCount) - 1
                if (members < 0 || members and allowed.inv() != 0) {
                    throw IllegalArgumentException(
                        "diversity mask 0x${members.toString(16)} exceeds 0x${allowed.toString(16)}",
                    )
                }
                if (members and (1 shl reference) != 0) {
                    throw IllegalArgumentException("diversity mask contains its reference receiver")
                }
                if (enabled) {
                    if (!diversitySupported) {
                        throw IllegalArgumentException("opened radio profile has no proven diversity route")
                    }
                    if (members == 0) {
                        throw IllegalArgumentException("enabled diversity requires a member receiver")
                    }
                    if (reference != 0 || members != 0b10) {
                        throw IllegalArgumentException(
                            "current HPSDR codecs support only reference=0/memberMask=0b10",
                        )
                    }
                } else if (members != 0) {
                    throw IllegalArgumentException("disabled diversity must use memberMask=0")
                }
                hl2?.setDiversity(enabled, reference, members)
                g2?.setDiversity(enabled, reference, members)
                diversityEnabled = enabled
                diversityReference = reference
                diversityMemberMask = if (enabled) members else 0
            }

            // HL2
            DriverProto.CMD_HL2_SET_LNA -> hl2?.setLnaGain(p.int)
            DriverProto.CMD_HL2_SET_VNA_MODE -> hl2?.setVnaMode(p.getBool())
            DriverProto.CMD_HL2_SET_TR_DISABLE -> hl2?.setTrDisable(p.getBool())
            DriverProto.CMD_HL2_SET_FILTER_OUTPUTS -> {
                val rx = p.int
                val tx = p.int
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
            DriverProto.CMD_HRF_SET_VGA -> {
                val db = p.int
                if (hackRf?.setVgaGain(db) != true) {
                    throw IllegalStateException("HackRF firmware refused VGA gain $db dB")
                }
            }
            DriverProto.CMD_HRF_SET_TXVGA -> {
                val db = p.int
                if (hackRf?.setTxVgaGain(db) != true) {
                    throw IllegalStateException("HackRF firmware refused TX VGA gain $db dB")
                }
            }
            DriverProto.CMD_HRF_SET_AMP -> hackRf?.setAmpEnable(p.getBool())
            DriverProto.CMD_HRF_START_RX -> {
                if (hackRf?.startRx() != true) {
                    throw IllegalStateException("HackRF RX could not start in the current state")
                }
            }
            DriverProto.CMD_HRF_SWEEP_START -> {
                val startMHz = p.int
                val stopMHz = p.int
                val rateHz = p.int
                val stepHz = p.int
                val epoch = openEpochs.publishedEpoch()
                    ?: throw IllegalStateException("HackRF session is not published")
                if (hackRf?.startSweep(
                        startMHz,
                        stopMHz,
                        rateHz,
                        stepHz,
                        sweepFor(epoch),
                    ) != true
                ) {
                    throw IllegalStateException("HackRF sweep could not start in the current state")
                }
            }
            DriverProto.CMD_HRF_SWEEP_STOP -> {
                if (hackRf?.stopSweep() != true) {
                    throw IllegalStateException("HackRF sweep could not stop cleanly")
                }
            }

            // HackRF, second block: the rest of what the board can be told.
            DriverProto.CMD_HRF_SET_FREQ_EXPLICIT -> {
                val ifHz = p.long
                val loHz = p.long
                val path = p.int
                if (hackRf?.setFreqExplicit(ifHz, loHz, path) != true) {
                    throw IllegalArgumentException("HackRF explicit tuning values were refused")
                }
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
                if (hackRf?.operacakeSetPorts(addr, portA, portB) != true) {
                    throw IllegalArgumentException("Opera Cake port selection was refused")
                }
            }
            DriverProto.CMD_HRF_OPERACAKE_SET_MODE -> {
                val addr = p.int
                if (hackRf?.operacakeSetMode(addr, p.int) != true) {
                    throw IllegalArgumentException("Opera Cake mode was refused")
                }
            }
            DriverProto.CMD_HRF_OPERACAKE_SET_RANGES -> {
                val n = p.int
                val ranges = ArrayList<Triple<Int, Int, Int>>(n)
                repeat(n) { ranges.add(Triple(p.int, p.int, p.int)) }
                if (hackRf?.operacakeSetFreqRanges(ranges) != true) {
                    throw IllegalArgumentException("Opera Cake frequency table was refused")
                }
            }
            DriverProto.CMD_HRF_OPERACAKE_SET_DWELL -> {
                val n = p.int
                val dwells = ArrayList<Pair<Int, Int>>(n)
                repeat(n) { dwells.add(Pair(p.int, p.int)) }
                if (hackRf?.operacakeSetDwellTimes(dwells) != true) {
                    throw IllegalArgumentException("Opera Cake dwell table was refused")
                }
            }
            DriverProto.CMD_HRF_RESET -> hackRf?.reset()
            DriverProto.CMD_HRF_QUERY_INFO -> sendHackRfInfo()
            DriverProto.CMD_HRF_QUERY_M0_STATE -> sendHackRfM0State()
            DriverProto.CMD_HRF_SELFTEST -> sendHackRfSelfTest()
            DriverProto.CMD_HRF_CLEAR_FREQ_EXPLICIT -> hackRf?.clearFreqExplicit()

            // RTL tuner
            DriverProto.CMD_RTL_SET_GAIN -> p.int.let { g ->
                rtlTcp?.setGain(g)
                rtlUsb?.setGain(g)
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
                rtlUsb?.setFrequencyCorrection(ppm)
            }

            DriverProto.CMD_RTL_QUERY_INFO -> sendRtlInfo()

            DriverProto.CMD_RTL_SET_DIRECT_SAMPLING -> p.int.let { mode ->
                rtlUsb?.setDirectSamplingMode(mode)
                rtlTcp?.setDirectSampling(mode)
            }
            // rtl_tcp has no bandwidth opcode; USB only.

            // CAT rig: operating mode by the dialect's own mode code (the
            // rig switches its demodulator and passband — there is no local
            // demodulation to configure for a spectrum-only radio).
            DriverProto.CMD_CAT_SET_MODE -> p.int.let { m ->
                val r = radio as CatControlCapable
                val applied = r.setCatMode(m)
                val actual = r.currentCatMode()
                enqueueReadback(openEpochs.publishedEpoch()) {
                    frames.writeCatModeResult(m, actual, applied)
                }
                if (!applied) throw IllegalStateException("CAT mode was not confirmed")
            }

            // CAT rig: one of the rig's OWN receive controls (CATCTL_* id,
            // value). Each dialect maps the shared id onto its wire and
            // answers false for ids its rig has no command for.
            DriverProto.CMD_CAT_SET_CONTROL -> {
                val id = p.int
                val value = p.int
                val applied = (radio as CatControlCapable).setCatControl(id, value)
                enqueueReadback(openEpochs.publishedEpoch()) {
                    frames.writeCatControlResult(id, value, applied, superseded = false)
                }
                if (!applied) throw IllegalStateException("CAT control was not confirmed")
            }

            else -> {
                Log.w(TAG, "unknown opcode 0x${frame.op.toString(16)} — closing")
                close()
                return
            }
        }
        } catch (e: BufferUnderflowException) {
            if (terminal) {
                sendCommandResult(frame.op, DriverProto.COMMAND_MALFORMED, "truncated payload")
                return
            }
            throw IOException("truncated payload for opcode 0x${frame.op.toString(16)}", e)
        } catch (e: IOException) {
            if (terminal) {
                sendCommandResult(frame.op, DriverProto.COMMAND_MALFORMED, e.message ?: "malformed payload")
                return
            }
            throw e
        } catch (e: Exception) {
            if (terminal) {
                sendCommandResult(frame.op, DriverProto.COMMAND_REJECTED, e.message ?: e.javaClass.simpleName)
                return
            }
            throw e
        }
        if (terminal) {
            sendCommandResult(frame.op, DriverProto.COMMAND_ACCEPTED)
        }
    }

    private suspend fun openDevice(
        epoch: OpenEpochGate.Epoch,
        kind: Int,
        host: String,
        port: Int,
        flags: Int,
    ) {
        // Validate identity before claim/client allocation. In particular,
        // flags=1 is the retired "some classic ANAN" alias and must not emit
        // control bytes to hardware selected by an ambiguous profile.
        val protocol1Profile = if (kind == DriverProto.DEV_HPSDR_P1) {
            Protocol1Profile.fromOpenFlags(flags)
        } else {
            null
        }
        if (kind == DriverProto.DEV_HPSDR_P1 && protocol1Profile == null) {
            failReplacementOpen(
                epoch,
                "Ambiguous/unknown Protocol-1 profile flags $flags; choose an exact chassis",
            )
            return
        }
        val catProfile = if (kind == DriverProto.DEV_CAT) {
            catOpenFlagsFailure(flags)?.let { detail ->
                failReplacementOpen(epoch, detail)
                return
            }
            DriverProto.catProfile(flags)
        } else {
            null
        }
        // Discovery is read-only while the old radio remains live and never
        // sends start/control frames to a mismatched candidate. CMD_OPEN is a
        // replacement, however: before either terminal is published below,
        // the old epoch is revoked, drained and disconnected.
        val verifiedProtocol1Board = if (protocol1Profile != null) {
            val targetHost = host.ifEmpty { Hl2Client.BROADCAST }
            val targetPort = if (port > 0) port else Hl2Protocol.PORT
            val verified = try {
                Protocol1Discovery.find(protocol1Profile, targetHost, targetPort)
            } catch (e: Exception) {
                Log.e(TAG, "Protocol-1 discovery preflight failed: ${e.message}")
                null
            }
            if (verified == null) {
                failReplacementOpen(
                    epoch,
                    "No matching ${protocol1Profile.displayName} board family answered discovery",
                )
                return
            }
            verified
        } else {
            null
        }
        if (!handoverOpen(epoch)) return
        // One radio, one session. Reconnection races left a ZOMBIE session
        // holding the same board — both threads fed it, with independent TX
        // sequences, and the zombie kept asserting its (possibly keyed) MOX.
        // The newest claim wins; the old owner is closed before this open
        // touches the hardware.
        if (!claimDevice(
                epoch,
                normalizedDeviceKey(
                    kind,
                    host,
                    port,
                    flags,
                    verifiedProtocol1Board?.address?.hostAddress,
                ),
            )
        ) return
        val onStatusEpoch = statusFor(epoch)
        val onDataEpoch = dataFor(epoch)
        val onDataRxEpoch = dataRxFor(epoch)
        var failureDetail: String? = null

        val ok = try {
            when (kind) {
                DriverProto.DEV_RTL_TCP -> {
                    val c = RTLTCPClient(host, port, onDataEpoch, onStatusEpoch)
                    installCandidate(epoch, c, radioName(kind, flags)) { rtlTcp = c } &&
                        connectCandidate(epoch, c)
                }
                DriverProto.DEV_RTL_USB -> {
                    val c = RTLUSBClient(context, onDataEpoch, onStatusEpoch)
                    installCandidate(epoch, c, radioName(kind, flags)) { rtlUsb = c } &&
                        connectCandidate(epoch, c)
                }
                DriverProto.DEV_HACKRF -> {
                    val c = HackRfClient(
                        context,
                        onDataEpoch,
                        onStatusEpoch,
                        hackRfGapsFor(epoch),
                    )
                    installCandidate(epoch, c, radioName(kind, flags)) { hackRf = c } &&
                        connectCandidate(epoch, c)
                }
                DriverProto.DEV_FLEX -> {
                    // Support withdrawn (libflexk is private and unbundled).
                    // It answers with a REFUSAL rather than falling through to
                    // the "unknown device" path: the app can still ask for a
                    // FLEX, and an operator who does deserves to be told the
                    // driver dropped it, not left watching a connect that
                    // never completes.
                    onStatusEpoch(false, "FlexRadio support is not available in this build")
                    false
                    // val c = com.isaklab.libflexk.FlexClient(onDataEpoch, onStatusEpoch, ...)
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
                DriverProto.DEV_HPSDR_P1 -> {
                    val profile = checkNotNull(protocol1Profile)
                    val c = Hl2Client(
                        host = host.ifEmpty { Hl2Client.BROADCAST },
                        onDataReceived = onDataEpoch,
                        onConnectionStatusChanged = onStatusEpoch,
                        onDataRx = onDataRxEpoch,
                        onTelemetry = hl2TelemetryFor(epoch),
                        port = if (port > 0) port else Hl2Protocol.PORT,
                        profile = profile,
                        verifiedBoard = checkNotNull(verifiedProtocol1Board),
                    )
                    installCandidate(epoch, c, radioName(kind, flags)) {
                        hl2 = c
                        receiverCapacity = profile.receiverCapacity
                        diversitySupported = profile.diversitySupported
                    } && connectCandidate(epoch, c)
                }
                DriverProto.DEV_G2 -> {
                    val c = G2Client(
                        host = host.ifEmpty { G2Client.BROADCAST },
                        onDataReceived = onDataEpoch,
                        onConnectionStatusChanged = onStatusEpoch,
                        onStatus = g2StatusFor(epoch),
                        onDataRx = onDataRxEpoch,
                    )
                    installCandidate(epoch, c, radioName(kind, flags)) {
                        g2 = c
                        receiverCapacity = 7
                        diversitySupported = true
                    } && connectCandidate(epoch, c)
                }
                DriverProto.DEV_CAT -> when (DriverProto.catDialect(flags)) {
                    DriverProto.CAT_DIALECT_CIV -> {
                        // Open payload contract (DriverProto.DEV_CAT): host
                        // empty or "usb" = the rig's USB-CDC serial port with
                        // port as the baud rate (0 = 115200); anything else =
                        // a TCP serial bridge with port as the TCP port
                        // (0 = 4532). The flags low byte is the CI-V bus
                        // address (0 = probe).
                        val transport = if (host.isEmpty() || host == "usb") {
                            val t = UsbCdcTransport(context, if (port > 0) port else 115200)
                            if (!t.open()) {
                                onStatusEpoch(false, "No USB serial adapter found")
                                null
                            } else {
                                t
                            }
                        } else {
                            TcpTransport(host, if (port > 0) port else 4532)
                        }
                        if (transport == null) {
                            false
                        } else {
                            val profile = checkNotNull(catProfile)
                            val c = CivClient(
                                transport,
                                flags and DriverProto.CAT_ADDRESS_MASK,
                                onDataEpoch,
                                onStatusEpoch,
                                requiredReportedCivAddress(profile),
                            )
                            installCandidate(epoch, c, radioName(kind, flags)) { cat = c } &&
                                connectCandidate(epoch, c)
                        }
                    }
                    DriverProto.CAT_DIALECT_KENWOOD -> {
                        // Kenwood KNS carries no credential fields in the
                        // open, so the host string may be user:password@host
                        // — split on the LAST '@' and the FIRST ':' so a
                        // password may contain either character. Host empty
                        // or "usb" = the rig's USB-CDC serial port with port
                        // as the baud rate (0 = 115200); anything else = the
                        // KNS TCP port (0 = 60000).
                        val at = host.lastIndexOf('@')
                        val target = if (at >= 0) host.substring(at + 1) else host
                        val credentials = if (at >= 0) {
                            val userinfo = host.substring(0, at)
                            val colon = userinfo.indexOf(':')
                            if (colon >= 0) {
                                Pair(userinfo.substring(0, colon), userinfo.substring(colon + 1))
                            } else {
                                Pair(userinfo, "")
                            }
                        } else {
                            null
                        }
                        val serial = target.isEmpty() || target == "usb"
                        val transport = if (serial) {
                            val t = UsbCdcTransport(context, if (port > 0) port else 115200)
                            if (!t.open()) {
                                onStatusEpoch(false, "No USB serial adapter found")
                                null
                            } else {
                                t
                            }
                        } else {
                            TcpTransport(target, if (port > 0) port else 60000)
                        }
                        if (transport == null) {
                            false
                        } else {
                            val profile = checkNotNull(catProfile)
                            val c = KenwoodClient(
                                transport,
                                if (serial) KenwoodClient.Link.SERIAL else KenwoodClient.Link.LAN,
                                credentials,
                                onDataEpoch,
                                onStatusEpoch,
                                requiredKenwoodModelId(profile),
                            )
                            installCandidate(epoch, c, radioName(kind, flags)) { kenwoodCat = c } &&
                                connectCandidate(epoch, c)
                        }
                    }
                    else -> {
                        onStatusEpoch(false, "unknown CAT dialect in the open flags")
                        false
                    }
                }
                DriverProto.DEV_ICOM_IQ -> {
                    // Native Icom IQ (IC-7610/IC-R8600) is served by the
                    // desktop host; the dedicated USB IQ port is out of scope
                    // for the phone driver. Answer with a REFUSAL rather than
                    // falling through to the "unknown device" path, so an
                    // operator who asks for it is told why instead of
                    // watching a connect that never completes.
                    onStatusEpoch(
                        false,
                        "Icom native IQ (IC-7610/IC-R8600) is served by the desktop host; " +
                            "phone USB is out of scope",
                    )
                    false
                }
                else -> false
            }
        } catch (e: Exception) {
            Log.e(TAG, "open kind=$kind failed: ${e.message}")
            failureDetail = e.message ?: e.javaClass.simpleName
            false
        }
        val opened = currentRadio()
        if (!ok || opened == null) {
            failOpen(epoch, opened, failureDetail)
            return
        }
        if (!openEpochs.isConnecting(epoch)) {
            // CLOSE may have detached and disconnected the candidate between
            // install() and connect(). Disconnect again after a late connect
            // result: RadioClient.disconnect is an idempotent contract, and a
            // revoked candidate must never remain physically active.
            disconnectRadio(detachRadio(opened) ?: opened)
            return
        }
        enqueueOpenSuccess(epoch, opened, radioName(kind, flags))
    }

    /**
     * Install one candidate while holding a short CONNECTING lease. CLOSE
     * revokes first and then waits for this local pointer/listener operation,
     * so a cancelled A can never overwrite or detach a successor B.
     */
    internal fun installCandidate(
        epoch: OpenEpochGate.Epoch,
        client: RadioClient,
        displayName: String,
        attachTypedClient: () -> Unit,
    ): Boolean {
        val installLease = openEpochs.acquireConnecting(epoch)
        if (installLease == null) {
            disconnectRadio(client)
            return false
        }
        var accepted = false
        var detached: RadioClient? = null
        try {
            synchronized(radioLock) {
                radioControlReady = false
                radio = client
                attachTypedClient()
                DriverServiceState.update { it.copy(radio = displayName) }
            }
            client.setStateListener {
                deliverPublished(epoch) {
                    announceSampleRate(client, epoch)
                    announceFrequency(client, epoch)
                }
            }
            accepted = openEpochs.isConnecting(epoch)
            if (!accepted) detached = detachRadio(client)
        } finally {
            openEpochs.release(installLease)
        }
        if (!accepted) disconnectRadio(detached ?: client)
        return accepted
    }

    /**
     * A connect implementation may allocate its socket/USB/thread only after
     * CLOSE's first cancellation request has returned. Always perform a final
     * idempotent teardown when the attempt returns into a revoked epoch.
     */
    internal suspend fun connectCandidate(
        epoch: OpenEpochGate.Epoch,
        client: RadioClient,
    ): Boolean {
        val connected = client.connect()
        if (!openEpochs.isConnecting(epoch)) {
            disconnectRadio(detachRadio(client) ?: client)
            return false
        }
        return connected
    }

    /** Linearise CMD_OPEN replacement before publishing either terminal. */
    private fun handoverOpen(epoch: OpenEpochGate.Epoch): Boolean {
        val previous = currentRadio()
        val handover = openEpochs.handover(epoch)
        if (!handover.accepted) return false
        openEpochs.awaitQuiescent(listOfNotNull(handover.retired))
        discardShmBacklog()
        disconnectRadio(previous?.let(::detachRadio))
        return openEpochs.isConnecting(epoch)
    }

    /** Fail one replacement only after the prior radio can no longer publish. */
    private fun failReplacementOpen(epoch: OpenEpochGate.Epoch, detail: String) {
        if (handoverOpen(epoch)) failOpen(epoch, null, detail)
    }

    /** Queue the sole positive OPEN terminal; the writer owns publication. */
    private fun enqueueOpenSuccess(
        epoch: OpenEpochGate.Epoch,
        opened: RadioClient,
        fallbackStatus: String,
    ) {
        enqueue(
            OutEntry(
                0,
                null,
                0,
                {
                    when (val start = openEpochs.startCommit(epoch)) {
                        is OpenEpochGate.CommitStart.Rejected -> {
                            val detached = detachRadio(opened)
                            DriverServiceState.update {
                                it.copy(radioStatus = start.detail, radioConnected = false)
                            }
                            openEpochs.completeTerminal(start.lease)
                            frames.writeStatus(false, start.detail)
                            frames.writeBool(DriverProto.EV_OPEN_RESULT, false)
                            disconnectRadio(detached)
                        }
                        OpenEpochGate.CommitStart.Ready -> {
                            // No lifecycle monitor is held across socket I/O.
                            // COMMITTING nevertheless gives this FIFO terminal
                            // precedence over a concurrent CMD_CLOSE.
                            frames.writeBool(DriverProto.EV_OPEN_RESULT, true)
                            val finish = openEpochs.finishCommit(epoch)
                            when (finish) {
                                is OpenEpochGate.CommitFinish.Published -> {
                                    val status = finish.status
                                        ?: OpenEpochGate.Status(true, fallbackStatus)
                                    val initial = try {
                                        synchronized(statusDeliveryLock) {
                                            if (!openEpochs.isPublished(epoch)) {
                                                null
                                            } else {
                                                radioControlReady = true
                                                DriverServiceState.update {
                                                    it.copy(
                                                        radioStatus = status.detail,
                                                        radioConnected = true,
                                                    )
                                                }
                                                // Capture only after
                                                // publication. State callbacks
                                                // after this point enqueue
                                                // behind the current FIFO item.
                                                captureInitialState(opened)
                                            }
                                        }
                                    } finally {
                                        // CLOSE may now detach/reset state,
                                        // but it cannot have completed before
                                        // all local publication effects above.
                                        openEpochs.release(finish.lease)
                                    }
                                    if (initial != null) {
                                        frames.writeStatus(true, status.detail)
                                        writeInitialState(initial)
                                    }
                                }
                                is OpenEpochGate.CommitFinish.Rejected -> {
                                    val detached = detachRadio(opened)
                                    DriverServiceState.update {
                                        it.copy(
                                            radioStatus = finish.detail,
                                            radioConnected = false,
                                        )
                                    }
                                    openEpochs.completeTerminal(finish.lease)
                                    frames.writeStatus(false, finish.detail)
                                    disconnectRadio(detached)
                                }
                                OpenEpochGate.CommitFinish.Revoked -> Unit
                            }
                        }
                        OpenEpochGate.CommitStart.Stale -> Unit
                    }
                },
                droppable = false,
            ),
        )
    }

    /** Resolve one candidate failure with one status and one negative result. */
    private fun failOpen(
        epoch: OpenEpochGate.Epoch,
        candidate: RadioClient?,
        detail: String?,
    ) {
        val failure = openEpochs.fail(epoch, detail, "Radio did not connect") ?: return
        val resolved = failure.detail
        val detached: RadioClient?
        try {
            detached = if (candidate != null) {
                detachRadio(candidate)
            } else {
                // Some refusals happen after this epoch won the board claim
                // but before a RadioClient exists. Release only this epoch's
                // claim; a read-only preflight has no candidate to teardown.
                releaseDeviceClaim(epoch)
                null
            }
            DriverServiceState.update {
                it.copy(radioStatus = resolved, radioConnected = false)
            }
            enqueue(
                OutEntry(
                    0,
                    null,
                    0,
                    {
                        if (openEpochs.isRunning()) {
                            frames.writeStatus(false, resolved)
                            frames.writeBool(DriverProto.EV_OPEN_RESULT, false)
                        }
                    },
                    droppable = false,
                ),
            )
        } finally {
            openEpochs.completeTerminal(failure.lease)
        }
        disconnectRadio(detached)
    }

    /** Initial hardware truth, emitted synchronously by the OPEN FIFO item. */
    private data class InitialOpenState(
        val sampleRate: Int,
        val frequency: Long,
        val rtlInfo: ByteBuffer?,
    )

    private fun captureInitialState(opened: RadioClient): InitialOpenState = InitialOpenState(
        opened.sampleRateHz(),
        opened.frequencyHz(),
        if (opened is RTLTCPClient || opened is RTLUSBClient) {
            encodeRtlInfo(rtlInfo(opened))
        } else {
            null
        },
    )

    private fun writeInitialState(initial: InitialOpenState) {
        if (initial.sampleRate > 0) {
            frames.writeI32(DriverProto.EV_SAMPLE_RATE, initial.sampleRate)
        }
        if (initial.frequency > 0) {
            frames.writeI64(DriverProto.EV_FREQUENCY, initial.frequency)
        }
        initial.rtlInfo?.let { frames.write(DriverProto.EV_RTL_INFO, it) }
    }

    /** Exact tuner/gain metadata; an unknown table is encoded as known=0,n=0. */
    private fun sendRtlInfo() {
        val current = radio ?: throw IllegalStateException("no RTL radio is open")
        val bb = encodeRtlInfo(rtlInfo(current))
        enqueueReadback(openEpochs.publishedEpoch()) { frames.write(DriverProto.EV_RTL_INFO, bb) }
    }

    private fun rtlInfo(client: RadioClient): RtlTunerInfo = when (client) {
        is RTLTCPClient -> client.tunerInfo()
        is RTLUSBClient -> client.tunerInfo()
        else -> throw IllegalStateException("no RTL radio is open")
    }

    /** EV_FREQUENCY with the frequency in force; zero (cannot say) is never announced. */
    private fun announceFrequency() {
        val current = radio ?: return
        announceFrequency(current)
    }

    private fun announceFrequency(
        current: RadioClient,
        epoch: OpenEpochGate.Epoch? = openEpochs.publishedEpoch(),
    ) {
        val hz = current.frequencyHz()
        if (hz > 0) {
            enqueueReadback(epoch) { frames.writeI64(DriverProto.EV_FREQUENCY, hz) }
        }
    }

    /** EV_SAMPLE_RATE with the rate in force; zero (cannot say) is never announced. */
    private fun announceSampleRate() {
        val current = radio ?: return
        announceSampleRate(current)
    }

    private fun announceSampleRate(
        current: RadioClient,
        epoch: OpenEpochGate.Epoch? = openEpochs.publishedEpoch(),
    ) {
        val hz = current.sampleRateHz()
        if (hz > 0) {
            enqueueReadback(epoch) { frames.writeI32(DriverProto.EV_SAMPLE_RATE, hz) }
        }
    }

    private fun radioName(kind: Int, flags: Int): String = when (kind) {
        DriverProto.DEV_RTL_TCP -> "RTL-SDR (rtl_tcp)"
        DriverProto.DEV_RTL_USB -> "RTL-SDR (USB)"
        DriverProto.DEV_HACKRF -> "HackRF"
        DriverProto.DEV_FLEX -> "FlexRadio"
        DriverProto.DEV_HPSDR_P1 ->
            Protocol1Profile.fromOpenFlags(flags)?.displayName ?: "Invalid Protocol-1 profile"
        DriverProto.DEV_G2 -> "ANAN-G2 (Saturn)"
        DriverProto.DEV_CAT ->
            if (DriverProto.catDialect(flags) == DriverProto.CAT_DIALECT_KENWOOD) "Kenwood"
            else "CAT rig"
        DriverProto.DEV_ICOM_IQ -> "Icom IQ"
        else -> "?"
    }

    /** Take ownership only while this OPEN is still the current candidate. */
    private fun claimDevice(epoch: OpenEpochGate.Epoch, key: String): Boolean {
        val claimLease = openEpochs.acquireConnecting(epoch) ?: return false
        try {
            var prev: DriverSession? = null
            synchronized(radioLock) {
                currentDeviceKey = key
                currentDeviceEpoch = epoch
                prev = deviceOwners.put(key, this)
            }
            // Once put() won, the previous owner must always be closed. A
            // cancel concurrent with this short claim waits for the lease, so
            // CLOSE cannot return and then observe a delayed map/device side
            // effect from its revoked OPEN coroutine.
            if (prev != null && prev !== this) {
                Log.w(TAG, "device $key claimed by a new session — closing the stale owner")
                runCatching { prev?.close() }
            }
            if (!openEpochs.isConnecting(epoch)) {
                releaseDeviceClaim(epoch)
                return false
            }
            return true
        } finally {
            openEpochs.release(claimLease)
        }
    }

    private fun releaseDeviceClaim(epoch: OpenEpochGate.Epoch) {
        synchronized(radioLock) {
            if (currentDeviceEpoch !== epoch) return
            currentDeviceKey?.let { deviceOwners.remove(it, this) }
            currentDeviceKey = null
            currentDeviceEpoch = null
        }
    }

    @Volatile private var currentDeviceKey: String? = null
    @Volatile private var currentDeviceEpoch: OpenEpochGate.Epoch? = null

    private fun currentRadio(): RadioClient? = synchronized(radioLock) { radio }

    /**
     * Detach session pointers only; the caller performs hardware teardown
     * after the relevant epoch has already been revoked.
     */
    private fun detachRadio(): RadioClient? = synchronized(radioLock) {
        detachCurrentRadioLocked()
    }

    private fun detachRadio(expected: RadioClient): RadioClient? = synchronized(radioLock) {
        if (radio !== expected) null else detachCurrentRadioLocked()
    }

    private fun detachCurrentRadioLocked(): RadioClient? {
        val current = radio
        currentDeviceKey?.let { deviceOwners.remove(it, this); currentDeviceKey = null }
        currentDeviceEpoch = null
        radio = null
        radioControlReady = false
        receiverCount = 1
        receiverCapacity = 1
        activeReceiver = 0
        rxStreamMask = 0
        diversitySupported = false
        diversityEnabled = false
        diversityReference = 0
        diversityMemberMask = 0
        pttOn = false
        lastTxIqMs = com.isaklab.isdrdrivers.core.TxWatchdogPolicy.NOT_ARMED
        keyedAtMs = 0L
        txFrequencyReady = true
        rtlTcp = null
        rtlUsb = null
        hackRf = null
        hl2 = null
        g2 = null
        cat = null
        kenwoodCat = null
        DriverServiceState.update {
            it.copy(radio = null, radioStatus = null, radioConnected = false)
        }
        return current
    }

    /** Hardware teardown after the epoch has already been revoked. */
    private fun disconnectRadio(client: RadioClient?) {
        if (client == null) return
        client.setStateListener(null)
        try {
            // Never leave the air keyed behind a closing session.
            (client as? CatRepeaterCapable)?.requestCatRepeaterCancelForUnkey()
            (client as? TransmitCapable)?.setPtt(false)
        } catch (_: Exception) {
        }
        try {
            client.disconnect()
        } catch (_: Exception) {
        }
    }

    private fun closeDevice() {
        val state = openEpochs.cancel()
        (currentRadio() as? CatRepeaterCapable)?.requestCatRepeaterCancelForUnkey()
        openEpochs.awaitQuiescent(state.retired)
        discardShmBacklog()
        val detached = detachRadio()
        // First pass requests cancellation of a connect already inside a
        // blocking driver. The attempt itself performs a final idempotent
        // teardown if it returns stale.
        disconnectRadio(detached)
        openEpochs.awaitAttempts(state.retired)
        disconnectRadio(detached)
        when {
            state.candidateTerminalInFlight -> Unit
            state.hadConnecting -> {
                // False callbacks while CONNECTING also carry progress text
                // ("Searching…", "Opening…"), so cancellation must not
                // misreport the last progress line as a physical failure.
                val detail = "Open cancelled"
                DriverServiceState.update {
                    it.copy(radioStatus = detail, radioConnected = false)
                }
                enqueue(
                    OutEntry(
                        0,
                        null,
                        0,
                        {
                            if (openEpochs.isRunning()) {
                                frames.writeStatus(false, detail)
                                frames.writeBool(DriverProto.EV_OPEN_RESULT, false)
                            }
                        },
                        droppable = false,
                    ),
                )
            }
            state.hadCommitting -> {
                val detail = state.candidateStatus
                    ?.takeUnless { it.connected }
                    ?.detail
                    ?: "Disconnected"
                DriverServiceState.update {
                    it.copy(radioStatus = detail, radioConnected = false)
                }
                enqueue(
                    OutEntry(
                        0,
                        null,
                        0,
                        {
                            if (openEpochs.isRunning()) {
                                frames.writeStatus(false, detail)
                            }
                        },
                        droppable = false,
                    ),
                )
            }
            state.publishedTerminalStatus != null -> Unit
            detached != null -> enqueue(
                OutEntry(
                    0,
                    null,
                    0,
                    {
                        if (openEpochs.isRunning()) {
                            frames.writeStatus(false, "Disconnected")
                        }
                    },
                    droppable = false,
                ),
            )
        }
    }
}
