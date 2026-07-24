/*
 * libflexk - Kotlin driver for FlexRadio 6000-series radios (SmartSDR API)
 *
 * Network transport and session orchestration. Wire formats live in the
 * Android-free [FlexProtocol].
 *
 * Copyright (C) 2026 Isak Ruas <isakruas@gmail.com>. All rights reserved.
 * Dual-licensed: GPLv2+ only as distributed within the iSDR Drivers
 * application; all other uses require a separate license from the copyright
 * holder. See LICENSE at the root of this module.
 */
package com.isaklab.libflexk

import android.util.Log
import com.isaklab.isdrdrivers.core.FFTProcessor
import com.isaklab.isdrdrivers.core.SpectrumWorker
import com.isaklab.isdrdrivers.core.FloatRing
import com.isaklab.isdrdrivers.core.SeqTracker
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * FlexRadio 6000 client. Control plane: TCP text commands (`C<seq>|…`);
 * data plane: VITA-49 over UDP (we bind [FlexProtocol.DATA_PORT] and tell
 * the radio via `client udpport`).
 *
 * Host contract matches the other clients: interleaved float IQ in [-1,1]
 * plus a power spectrum, both via [onDataReceived]; transmit samples come in
 * via [submitTxIq] (interleaved 48 kSps floats in [-1,1]) and go out as a
 * paced DAX TX audio VITA stream while PTT is keyed.
 */
class FlexClient(
    /** (power spectrum in dB, interleaved IQ i0,q0,…) */
    private val onDataReceived: (FloatArray, FloatArray) -> Unit,
    private val onConnectionStatusChanged: (Boolean, String) -> Unit,
    /** Fired (with the running total) whenever an RX stream gap is detected. */
    private val onRxGaps: ((Long) -> Unit)? = null,
) {
    companion object {
        private const val TAG = "FlexClient"
        private const val IQ_BLOCK = 4096              // floats per delivery
    }

    @Volatile private var running = false
    private var tcp: Socket? = null
    private var writer: BufferedWriter? = null
    private var udp: DatagramSocket? = null
    private val seq = AtomicInteger(1)
    private val replyHandlers = ConcurrentHashMap<Int, (Long, String) -> Unit>()

    @Volatile private var panStreamId: Long = 0
    @Volatile private var daxIqStreamId: Long = 0
    @Volatile private var daxTxStreamId: Long = 0
    @Volatile private var pttOn = false
    /** Where TX VITA goes: learned from the radio's own data-plane packets
     *  (its source socket), falling back to the command host:port. */
    @Volatile private var txDest: InetSocketAddress? = null
    private val txLock = Any()
    private val txQueue = FloatRing(FlexProtocol.TX_AUDIO_RATE * 4)
    private var txSeq = 0                          // 4-bit VITA counter
    @Volatile private var sliceIndex = -1
    @Volatile private var centerMHz = 7.1
    @Volatile private var daxRate = 48000

    @Volatile var spectrumEnabled = true
    private var fft: FFTProcessor? = null
    // Welch FFT off the VITA receive thread: an inline FFT stalls the socket
    // read and drops DAX IQ packets. The receive thread only submits on the
    // display cadence and delivers the last cached spectrum — audio never
    // waits on the FFT.
    private var spectrumWorker: SpectrumWorker? = null
    private var lastFftTimeMs = 0L
    private val fftIntervalMs = 80L
    private val iqOut = FloatArray(IQ_BLOCK)
    private var iqFill = 0

    // VITA-49 4-bit packet counter continuity on the DAX IQ stream. A gap is
    // concealed with zeros sized from the last payload; a late reordered
    // packet is dropped (its samples would land out of order).
    private val vitaSeq = SeqTracker(modulo = 16)
    private var lastPayloadFloats = 0

    /** RX discontinuity events (loss/reorder) since connect — telemetry. */
    val rxGapCount: Long get() = vitaSeq.gapEvents

    /** Listen for one discovery broadcast; null on timeout. */
    fun discover(timeoutMs: Int = 4000): FlexProtocol.DiscoveredRadio? {
        return try {
            DatagramSocket(null).use { s ->
                s.reuseAddress = true
                s.bind(InetSocketAddress(FlexProtocol.DISCOVERY_PORT))
                s.soTimeout = timeoutMs
                val buf = ByteArray(2048)
                val end = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < end) {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    FlexProtocol.parseDiscovery(buf, p.length)?.let { return it }
                }
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "discovery: ${e.message}")
            null
        }
    }

    fun connect(host: String, port: Int) {
        if (running) return
        running = true
        thread(name = "flex-session") {
            try {
                val s = Socket(host, if (port > 0) port else FlexProtocol.COMMAND_PORT)
                s.tcpNoDelay = true
                tcp = s
                writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
                udp = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(FlexProtocol.DATA_PORT))
                    // Ride through ~GC-length stalls at full DAX IQ rate.
                    receiveBufferSize = 2 * 1024 * 1024
                }
                fft = FFTProcessor().also { spectrumWorker = SpectrumWorker(it).apply { start() } }
                txDest = InetSocketAddress(
                    host, if (port > 0) port else FlexProtocol.COMMAND_PORT,
                )
                thread(name = "flex-vita") { vitaLoop() }
                thread(name = "flex-tx") { txLoop() }
                thread(name = "flex-keepalive") { keepaliveLoop() }
                onConnectionStatusChanged(true, "FLEX @ $host")
                // Handshake per Radio.cs: identify, tell our UDP port, subscribe,
                // then build panadapter -> slice -> dax-iq (the RX MVP chain).
                send("client program iSDR")
                send("client udpport ${FlexProtocol.DATA_PORT}")
                send("sub slice all"); send("sub pan all"); send("sub daxiq all")
                send("sub radio all"); send("sub tx all")
                send("display panafall create x=800 y=400") { code, msg ->
                    if (code == 0L) {
                        // Reply carries "0x<pan>,0x<waterfall>".
                        panStreamId = msg.split(',').firstOrNull()
                            ?.trim()?.removePrefix("0x")?.toLongOrNull(16) ?: 0
                        onPanReady()
                    }
                }
                readLoop(BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8)))
            } catch (e: Exception) {
                Log.e(TAG, "connect: ${e.message}")
            } finally {
                onConnectionStatusChanged(false, "Disconnected")
                closeQuietly()
            }
        }
    }

    private fun onPanReady() {
        val pan = "0x%X".format(panStreamId)
        send("display pan set $pan xpixels=1024 ypixels=512 fps=10")
        send("display pan set $pan center=${"%.6f".format(centerMHz)} bandwidth=0.192")
        send("display pan set $pan daxiq_channel=1")
        send("slice create pan=$pan freq=${"%.6f".format(centerMHz)} mode=USB") { code, msg ->
            if (code == 0L) sliceIndex = msg.trim().toIntOrNull() ?: 0
        }
        send("stream create type=dax_iq daxiq_channel=1") { code, msg ->
            if (code == 0L) {
                daxIqStreamId = msg.trim().removePrefix("0x").toLongOrNull(16) ?: 0
                send("stream set 0x%X daxiq_rate=%d".format(daxIqStreamId, daxRate))
            }
        }
        // TX chain: one DAX TX audio stream for the session; PTT stays
        // refused until the radio hands us its id (no dead-carrier keying).
        send("stream create type=dax_tx dax_channel=1") { code, msg ->
            if (code == 0L) {
                daxTxStreamId = msg.trim().removePrefix("0x").toLongOrNull(16) ?: 0
                Log.i(TAG, "dax_tx stream 0x%X".format(daxTxStreamId))
            } else {
                Log.w(TAG, "dax_tx create failed: $msg")
            }
        }
    }

    private fun readLoop(reader: BufferedReader) {
        while (running) {
            val raw = reader.readLine() ?: break
            when (val line = FlexProtocol.parseLine(raw.trim())) {
                is FlexProtocol.Line.Reply ->
                    replyHandlers.remove(line.seq)?.invoke(line.code, line.message)
                is FlexProtocol.Line.Version -> Log.i(TAG, "protocol ${line.version}")
                is FlexProtocol.Line.Handle -> Log.i(TAG, "handle %X".format(line.handle))
                is FlexProtocol.Line.Status -> handleStatus(line.text)
                else -> {}
            }
        }
    }

    private fun handleStatus(text: String) {
        // Minimal dispatcher: track our slice's index if it was created by
        // status rather than reply, and daxiq stream ids.
        if (text.startsWith("daxiq ") && daxIqStreamId == 0L) {
            text.split(' ').getOrNull(1)?.removePrefix("0x")?.toLongOrNull(16)
                ?.let { daxIqStreamId = it }
        }
    }

    private fun vitaLoop() {
        val buf = ByteArray(16384)
        val sock = udp ?: return
        while (running) {
            try {
                val p = DatagramPacket(buf, buf.size)
                sock.receive(p)
                val v = FlexProtocol.parseVita(buf, p.length) ?: continue
                // The radio's data plane answers from its VITA socket; TX
                // audio goes back to that same endpoint.
                (p.socketAddress as? InetSocketAddress)?.let { txDest = it }
                if (!FlexProtocol.isWideIq(v.classCode)) continue
                if (daxIqStreamId != 0L && v.streamId != daxIqStreamId) continue
                // VITA 4-bit packet counter: conceal holes, drop late dups.
                val missing = vitaSeq.advance(v.packetCount.toLong())
                if (missing == -1L) continue
                if (missing > 0 && lastPayloadFloats > 0) {
                    concealGap(missing * lastPayloadFloats)
                }
                deliverIq(buf, v.payloadOffset, v.payloadBytes)
            } catch (e: Exception) {
                if (running) Log.w(TAG, "vita: ${e.message}")
            }
        }
    }

    private val decodeScratch = FloatArray(IQ_BLOCK)

    /**
     * Insert [floats] zeros (capped at one delivery block) for a lost-packet
     * hole so the audio path gets a dropout of about the right length, and
     * restart the spectrum smoothing across the discontinuity.
     */
    private fun concealGap(floats: Long) {
        var n = floats.coerceAtMost(IQ_BLOCK.toLong()).toInt() and 1.inv()
        spectrumWorker?.resetSmoothing()
        onRxGaps?.invoke(vitaSeq.gapEvents)
        Log.w(TAG, "vita gap: $floats floats lost (events=${vitaSeq.gapEvents})")
        while (n > 0) {
            val take = minOf(n, iqOut.size - iqFill)
            java.util.Arrays.fill(iqOut, iqFill, iqFill + take, 0f)
            iqFill += take; n -= take
            if (iqFill >= iqOut.size) flushBlock()
        }
    }

    private fun flushBlock() {
        val block = iqOut.copyOf()
        iqFill = 0
        if (!spectrumEnabled) {
            onDataReceived(FloatArray(0), block)
            return
        }
        // The display renders ~10 fps: on that cadence hand a copy of the block
        // to the off-thread worker (submit copies internally); the FFT never
        // runs on this receive thread. Deliver the IQ NOW with the last cached
        // spectrum — audio must never wait on the FFT (spectrum may lag one
        // frame, imperceptible at 10 fps).
        val now = System.currentTimeMillis()
        if (now - lastFftTimeMs >= fftIntervalMs) {
            lastFftTimeMs = now
            spectrumWorker?.submit(block, block.size / 2)
        }
        onDataReceived(spectrumWorker?.latest ?: FloatArray(0), block)
    }

    private fun deliverIq(buf: ByteArray, offset: Int, bytes: Int) {
        var decoded = FlexProtocol.decodeIfDataWide(buf, offset, bytes, decodeScratch)
        if (decoded > 0) lastPayloadFloats = decoded
        var src = 0
        while (decoded > 0) {
            val take = minOf(decoded, iqOut.size - iqFill)
            System.arraycopy(decodeScratch, src, iqOut, iqFill, take)
            iqFill += take; src += take; decoded -= take
            if (iqFill >= iqOut.size) flushBlock()
        }
    }

    fun setFrequency(hz: Long) {
        centerMHz = hz / 1e6
        if (panStreamId != 0L) {
            send("display pan set 0x%X center=%.6f".format(panStreamId, centerMHz))
        }
        if (sliceIndex >= 0) send("slice tune $sliceIndex ${"%.6f".format(centerMHz)}")
    }

    fun setSampleRate(hz: Int) {
        daxRate = hz
        if (daxIqStreamId != 0L) {
            send("stream set 0x%X daxiq_rate=%d".format(daxIqStreamId, hz))
        }
    }

    fun setMode(mode: String) {
        if (sliceIndex >= 0) send("slice set $sliceIndex mode=${mode.uppercase()}")
    }

    /**
     * Key/unkey the radio. Key-up is refused until the DAX TX stream exists —
     * `xmit 1` without a modulation feed would put a dead carrier on the air.
     * Unkey stops the feed first, then clears any queued samples.
     */
    fun setPtt(on: Boolean) {
        if (on) {
            if (daxTxStreamId == 0L) {
                Log.w(TAG, "PTT refused: no DAX TX stream yet")
                return
            }
            pttOn = true                       // feed running before keying
            send("xmit 1")
        } else {
            send("xmit 0")
            pttOn = false
            synchronized(txLock) { txQueue.clear() }
        }
    }

    /** Queue interleaved transmit samples (`[-1,1]`, 48 kSps pairs). */
    fun submitTxIq(iq: FloatArray) {
        synchronized(txLock) { txQueue.write(iq) }   // ring drops oldest itself
    }

    /**
     * Paced DAX TX sender: while keyed, one VITA packet of
     * [FlexProtocol.TX_FRAMES_PER_PACKET] stereo frames every
     * frames/48000 s (underruns are zero-filled so the radio's stream never
     * sees a sequence hole); the 4-bit counter is continuous for the whole
     * session, so key/unkey cycles cause no gap at the radio.
     */
    private fun txLoop() {
        val floatsPerPacket = FlexProtocol.TX_FRAMES_PER_PACKET * 2
        val periodNs =
            FlexProtocol.TX_FRAMES_PER_PACKET * 1_000_000_000L / FlexProtocol.TX_AUDIO_RATE
        val samples = FloatArray(floatsPerPacket)
        val pkt = ByteArray(16 + floatsPerPacket * 4)
        var next = System.nanoTime()
        while (running) {
            if (!pttOn || daxTxStreamId == 0L) {
                next = System.nanoTime() + periodNs
                try { Thread.sleep(5) } catch (_: InterruptedException) {}
                continue
            }
            synchronized(txLock) {
                var i = 0
                val have = minOf(txQueue.size, floatsPerPacket)
                while (i < have) { samples[i] = txQueue.read(); i++ }
                java.util.Arrays.fill(samples, i, floatsPerPacket, 0f)
            }
            val len = FlexProtocol.encodeTxAudio(
                daxTxStreamId, txSeq, samples, floatsPerPacket, pkt,
            )
            txSeq = (txSeq + 1) and 0xF
            val dst = txDest
            val sock = udp
            if (dst != null && sock != null) {
                try {
                    sock.send(DatagramPacket(pkt, len, dst))
                } catch (e: Exception) {
                    if (running) Log.w(TAG, "tx send: ${e.message}")
                }
            }
            next += periodNs
            val wait = next - System.nanoTime()
            if (wait > 0) {
                try {
                    Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                } catch (_: InterruptedException) {}
            } else if (wait < -50_000_000) {
                next = System.nanoTime()       // fell badly behind; resync
            }
        }
    }

    /** Control-plane keepalive (Radio.cs pings ~1 Hz to hold the session). */
    private fun keepaliveLoop() {
        while (running) {
            try { Thread.sleep(1000) } catch (_: InterruptedException) {}
            if (running) send("ping")
        }
    }

    fun setTxDrive(level: Int) =
        send("transmit set rfpower=${(level * 100 / 255).coerceIn(0, 100)}")

    fun setSmoothingFactor(alpha: Float) { fft?.setSmoothingFactor(alpha) }

    @Synchronized
    private fun send(cmd: String, onReply: ((Long, String) -> Unit)? = null) {
        val n = seq.getAndIncrement()
        if (onReply != null) replyHandlers[n] = onReply
        try {
            writer?.apply { write(FlexProtocol.command(n, cmd)); write("\n"); flush() }
        } catch (e: Exception) {
            Log.w(TAG, "send: ${e.message}")
        }
    }

    fun disconnect() {
        running = false
        pttOn = false
        try { writer?.write(4) } catch (_: Exception) {}   // dying gasp 0x04
        closeQuietly()
    }

    private fun closeQuietly() {
        spectrumWorker?.stop()
        try { tcp?.close() } catch (_: Exception) {}
        try { udp?.close() } catch (_: Exception) {}
        tcp = null; udp = null; writer = null
    }
}
