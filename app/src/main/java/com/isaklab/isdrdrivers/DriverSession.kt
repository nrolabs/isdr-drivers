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
import com.isaklab.isdrproto.Frame
import com.isaklab.isdrproto.Frames
import com.isaklab.isdrproto.DriverProto
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
            if (buf != null && android.os.Build.VERSION.SDK_INT >= 27) {
                android.os.SharedMemory.unmap(buf)
            }
            shm?.close()
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
        thread(name = "driver-out") { writerLoop() }
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
                // Before auth, cap the payload hard (a token frame is tiny) so
                // an unauthenticated peer can't force a 64 MB allocation
                // (audit H3). After auth the full limit applies.
                val frame = frames.read(if (authenticated) Int.MAX_VALUE else 4096) ?: break
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

    private fun onData(fft: FloatArray, iq: FloatArray) {
        val tag = flushSeq
        flushSeq = (flushSeq + 1) and 0x7fffffff
        if (publishShm(DriverProto.EV_DATA, fft.size, fft, iq, tag)) return
        val len = 12 + (fft.size + iq.size) * 4
        val b = obtainBuf(len)
        val bb = java.nio.ByteBuffer.wrap(b)
        bb.putInt(fft.size)
        bb.asFloatBuffer().put(fft)
        bb.position(bb.position() + fft.size * 4)
        bb.putInt(iq.size)
        bb.asFloatBuffer().put(iq)
        bb.position(bb.position() + iq.size * 4)
        bb.putInt(tag)
        enqueue(OutEntry(DriverProto.EV_DATA, b, len, null, droppable = true))
    }

    private fun onDataRx(rx: Int, iq: FloatArray) {
        val tag = flushSeq                       // seq of the flush's EV_DATA
        if (publishShm(DriverProto.EV_DATA_RX, rx, EMPTY_FLOATS, iq, tag)) return
        val len = 12 + iq.size * 4
        val b = obtainBuf(len)
        val bb = java.nio.ByteBuffer.wrap(b)
        bb.putInt(rx)
        bb.putInt(iq.size)
        bb.asFloatBuffer().put(iq)
        bb.position(bb.position() + iq.size * 4)
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

    private fun sendTelemetry(t: RadioTelemetry) =
        enqueue(OutEntry(0, null, 0, { frames.writeTelemetry(t) }, droppable = true))

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

            DriverProto.CMD_SET_FREQUENCY -> p.long.let { hz ->
                // flex?.setFrequency(hz)
                rtlTcp?.setFrequency(hz)
                rtlUsb?.sendCommand(RTLCommand.SetFrequency(hz))
                hackRf?.setFrequency(hz)
                hl2?.setFrequency(hz)
                g2?.setFrequency(hz)
            }
            DriverProto.CMD_SET_SAMPLE_RATE -> p.int.let { hz ->
                // flex?.setSampleRate(hz)
                rtlTcp?.setSampleRate(hz)
                rtlUsb?.sendCommand(RTLCommand.SetSampleRate(hz.toLong()))
                hackRf?.setSampleRate(hz)
                hl2?.setSampleRate(hz)
                g2?.setSampleRate(hz)
            }
            DriverProto.CMD_SET_SPECTRUM_INTEREST -> p.getBool().let { on ->
                rtlTcp?.spectrumEnabled = on
                rtlUsb?.spectrumEnabled = on
                hackRf?.spectrumEnabled = on
                hl2?.spectrumEnabled = on
                g2?.spectrumEnabled = on
            }

            // TX
            DriverProto.CMD_SET_TX_FREQUENCY -> p.long.let { hz ->
                hackRf?.setFrequency(hz)
                hl2?.setTxFrequency(hz)
                g2?.setTxFrequency(hz)
            }
            DriverProto.CMD_SET_PTT -> p.getBool().let { on ->
                // flex?.setPtt(on)
                hackRf?.setPtt(on)
                hl2?.setPtt(on)
                g2?.setPtt(on)
                sendTxState()
            }
            DriverProto.CMD_SET_TX_DRIVE -> p.int.let { level ->
                // flex?.setTxDrive(level)
                hl2?.setTxDrive(level)
                g2?.setTxDrive(level)
            }
            DriverProto.CMD_SET_PA_ENABLED -> p.getBool().let { on ->
                hl2?.setPaEnabled(on)
                g2?.setPaEnabled(on)
            }
            DriverProto.CMD_TX_IQ -> p.getFloats().let { iq ->
                // flex?.submitTxIq(iq)
                hackRf?.submitTxIq(iq)
                hl2?.submitTxIq(iq)
                g2?.submitTxIq(iq)
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
            DriverProto.CMD_HRF_SET_ANTENNA_POWER -> hackRf?.setAntennaPower(p.getBool())
            DriverProto.CMD_HRF_START_RX -> hackRf?.startRx()
            DriverProto.CMD_HRF_SWEEP_START -> {
                val startMHz = p.int
                val stopMHz = p.int
                val rateHz = p.int
                val stepHz = p.int
                hackRf?.startSweep(startMHz, stopMHz, rateHz, stepHz, ::onSweepBlock)
            }
            DriverProto.CMD_HRF_SWEEP_STOP -> hackRf?.stopSweep()

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
            DriverProto.CMD_RTL_SET_BIAS_TEE -> p.getBool().let { on ->
                rtlTcp?.setBiasTee(on)
                rtlUsb?.sendCommand(RTLCommand.SetBiasTee(on))
            }
            DriverProto.CMD_RTL_SET_DIRECT_SAMPLING -> p.int.let { mode ->
                rtlUsb?.setDirectSamplingMode(mode)
                rtlTcp?.setDirectSampling(mode)
            }
            // rtl_tcp has no bandwidth opcode; USB only.
            DriverProto.CMD_RTL_SET_TUNER_BANDWIDTH -> rtlUsb?.setTunerBandwidth(p.int)

            else -> Log.w(TAG, "unknown opcode 0x${frame.op.toString(16)}")
        }
    }

    private suspend fun openDevice(kind: Int, host: String, port: Int, flags: Int) {
        closeDevice()
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
                    c.connect()
                }
                DriverProto.DEV_RTL_USB -> {
                    val c = RTLUSBClient(context, ::onData, onStatusGen)
                    rtlUsb = c
                    c.connect()
                }
                DriverProto.DEV_HACKRF -> {
                    val c = HackRfClient(context, ::onData, onStatusGen)
                    hackRf = c
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

    private fun closeDevice() {
        // Invalidate the outgoing clients' callbacks FIRST: their disconnect
        // paths are asynchronous and must not speak for the next client.
        clientGen.incrementAndGet()
        val hadClient = rtlTcp != null || rtlUsb != null || hackRf != null ||
            hl2 != null || g2 != null
        try {
            hl2?.setPtt(false)
            g2?.setPtt(false)
            hackRf?.setPtt(false)
            // flex?.setPtt(false)
        } catch (_: Exception) {
        }
        try {
            rtlTcp?.disconnect()
            rtlUsb?.disconnect()
            hackRf?.disconnect()
            hl2?.disconnect()
            g2?.disconnect()
            // flex?.disconnect()
        } catch (_: Exception) {
        }
        rtlTcp = null
        rtlUsb = null
        hackRf = null
        // flex = null
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
