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
 * plus a power spectrum, both via [onDataReceived]; TX is phase 2.
 */
class FlexClient(
    /** (power spectrum in dB, interleaved IQ i0,q0,…) */
    private val onDataReceived: (FloatArray, FloatArray) -> Unit,
    private val onConnectionStatusChanged: (Boolean, String) -> Unit,
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
    @Volatile private var sliceIndex = -1
    @Volatile private var centerMHz = 7.1
    @Volatile private var daxRate = 48000

    @Volatile var spectrumEnabled = true
    private var fft: FFTProcessor? = null
    private val iqOut = FloatArray(IQ_BLOCK)
    private var iqFill = 0

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
                    receiveBufferSize = 1 shl 20
                }
                fft = FFTProcessor()
                thread(name = "flex-vita") { vitaLoop() }
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
                if (!FlexProtocol.isWideIq(v.classCode)) continue
                if (daxIqStreamId != 0L && v.streamId != daxIqStreamId) continue
                deliverIq(buf, v.payloadOffset, v.payloadBytes)
            } catch (e: Exception) {
                if (running) Log.w(TAG, "vita: ${e.message}")
            }
        }
    }

    private val decodeScratch = FloatArray(IQ_BLOCK)

    private fun deliverIq(buf: ByteArray, offset: Int, bytes: Int) {
        var decoded = FlexProtocol.decodeIfDataWide(buf, offset, bytes, decodeScratch)
        var src = 0
        while (decoded > 0) {
            val take = minOf(decoded, iqOut.size - iqFill)
            System.arraycopy(decodeScratch, src, iqOut, iqFill, take)
            iqFill += take; src += take; decoded -= take
            if (iqFill >= iqOut.size) {
                val block = iqOut.copyOf()
                iqFill = 0
                val spectrum = if (spectrumEnabled) {
                    fft?.computePowerSpectrum(block) ?: FloatArray(0)
                } else FloatArray(0)
                onDataReceived(spectrum, block)
            }
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

    fun setPtt(on: Boolean) = send(if (on) "xmit 1" else "xmit 0")

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
        try { writer?.write(4) } catch (_: Exception) {}   // dying gasp 0x04
        closeQuietly()
    }

    private fun closeQuietly() {
        try { tcp?.close() } catch (_: Exception) {}
        try { udp?.close() } catch (_: Exception) {}
        tcp = null; udp = null; writer = null
    }
}
