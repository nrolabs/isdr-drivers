/*
 * libflexk - Kotlin driver for FlexRadio 6000-series radios (SmartSDR API)
 *
 * Wire codec: VITA-49 packets (UDP data plane) and the TCP text command
 * plane, ported faithfully from the published FlexLib API:
 *   Copyright (c) FlexRadio Systems
 *   SmartSDR / FlexLib API v4.2.20 — https://www.flexradio.com/software/smartsdr-v4-2-20/
 *   (Vita sources, Discovery.cs, TcpCommandCommunication.cs, Radio.cs)
 *
 * Copyright (C) 2026 Isak Ruas <isakruas@gmail.com>. All rights reserved.
 * Dual-licensed: GPLv2+ only as distributed within the iSDR Drivers
 * application; all other uses require a separate license from the copyright
 * holder. See LICENSE at the root of this module.
 */
package com.isaklab.libflexk

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Android-free codec for the FlexRadio network protocol. Everything on the
 * data plane is big-endian VITA-49; the control plane is TCP text lines
 * (`C<seq>|command\n` out; `R`/`S`/`H`/`V`/`M` lines in).
 */
object FlexProtocol {

    /** Radio broadcasts VITA discovery packets to this UDP port. */
    const val DISCOVERY_PORT = 4992

    /** Default TCP command port (the discovery payload's `port` overrides). */
    const val COMMAND_PORT = 4992

    /** Local UDP port we bind for VITA streams (told via `client udpport`). */
    const val DATA_PORT = 4991

    /** FlexRadio OUI in the VITA class id (VitaFlex.cs). */
    const val FLEX_OUI = 0x1C2D

    // ---- VITA packet class codes (VitaFlex.cs) ----
    const val CLASS_DISCOVERY = 0xFFFF
    const val CLASS_METER = 0x8002
    const val CLASS_FFT = 0x8003
    const val CLASS_WATERFALL = 0x8004
    const val CLASS_OPUS = 0x8005
    const val CLASS_IF_NARROW = 0x03E3
    const val CLASS_IF_WIDE_24 = 0x02E3
    const val CLASS_IF_WIDE_48 = 0x02E4
    const val CLASS_IF_WIDE_96 = 0x02E5
    const val CLASS_IF_WIDE_192 = 0x02E6

    /** VITA packet type: extension data with stream id (discovery uses it). */
    const val PKT_EXT_DATA_STREAM = 3
    const val PKT_IF_DATA_STREAM = 1

    /** One parsed VITA preamble + payload window. */
    class VitaPacket(
        val pktType: Int,
        val streamId: Long,
        val oui: Int,
        val classCode: Int,
        val payloadOffset: Int,
        val payloadBytes: Int,
        val packetCount: Int,
    )

    /**
     * Parse a VITA-49 preamble (VitaPacketBase.ParsePreamble). Big-endian.
     * Returns null when the buffer is not a well-formed VITA packet.
     */
    fun parseVita(buf: ByteArray, len: Int): VitaPacket? {
        if (len < 4) return null
        val bb = ByteBuffer.wrap(buf, 0, len).order(ByteOrder.BIG_ENDIAN)
        val w0 = bb.int
        val pktType = (w0 ushr 28) and 0xF
        val hasClass = (w0 ushr 27) and 1 == 1
        val hasTrailer = (w0 ushr 26) and 1 == 1
        val tsi = (w0 ushr 22) and 0x3
        val tsf = (w0 ushr 20) and 0x3
        val packetCount = (w0 ushr 16) and 0xF
        val packetWords = w0 and 0xFFFF
        if (packetWords * 4 > len) return null
        var streamId = 0L
        if (pktType == PKT_IF_DATA_STREAM || pktType == PKT_EXT_DATA_STREAM) {
            if (bb.remaining() < 4) return null
            streamId = bb.int.toLong() and 0xFFFFFFFFL
        }
        var oui = 0; var classCode = 0
        if (hasClass) {
            if (bb.remaining() < 8) return null
            oui = bb.int and 0xFFFFFF
            bb.short                                  // information class code
            classCode = bb.short.toInt() and 0xFFFF
        }
        if (tsi != 0) { if (bb.remaining() < 4) return null; bb.int }
        if (tsf != 0) { if (bb.remaining() < 8) return null; bb.long }
        val headerBytes = bb.position()
        var payloadBytes = packetWords * 4 - headerBytes
        if (hasTrailer) payloadBytes -= 4
        if (payloadBytes < 0 || headerBytes + payloadBytes > len) return null
        return VitaPacket(pktType, streamId, oui, classCode, headerBytes, payloadBytes, packetCount)
    }

    /** A radio found on the LAN (discovery payload key=value pairs). */
    class DiscoveredRadio(val fields: Map<String, String>) {
        val ip: String get() = fields["ip"] ?: ""
        val commandPort: Int get() = fields["port"]?.toIntOrNull() ?: COMMAND_PORT
        val model: String get() = fields["model"] ?: "FLEX"
        val serial: String get() = fields["serial"] ?: ""
        val nickname: String get() = fields["nickname"] ?: model
        val version: String get() = fields["version"] ?: ""
        val available: Boolean get() = (fields["status"] ?: "Available").equals("Available", true)
    }

    /**
     * Decode a discovery packet: VITA ext-data, Flex OUI, class 0xFFFF, then
     * an ASCII `key=value key=value…` payload (space-padded to word size).
     */
    fun parseDiscovery(buf: ByteArray, len: Int): DiscoveredRadio? {
        val v = parseVita(buf, len) ?: return null
        if (v.pktType != PKT_EXT_DATA_STREAM || v.oui != FLEX_OUI ||
            v.classCode != CLASS_DISCOVERY
        ) return null
        val text = String(buf, v.payloadOffset, v.payloadBytes, Charsets.UTF_8).trim { it <= ' ' }
        val map = HashMap<String, String>()
        for (pair in text.split(' ')) {
            val eq = pair.indexOf('=')
            if (eq > 0) map[pair.substring(0, eq)] = pair.substring(eq + 1)
        }
        if (map["ip"].isNullOrEmpty()) return null
        return DiscoveredRadio(map)
    }

    /** Format an outgoing command line: `C<seq>|<cmd>` (newline added by the writer). */
    fun command(seq: Int, cmd: String): String = "C$seq|$cmd"

    /** One parsed control-plane line. */
    sealed class Line {
        /** `R<seq>|<hex code>|<message…>` — reply to a command. */
        class Reply(val seq: Int, val code: Long, val message: String) : Line()

        /** `S<handle>|<subsystem and key=value words>` — async status. */
        class Status(val handle: Long, val text: String) : Line()

        /** `H<hex handle>` — our client handle. */
        class Handle(val handle: Long) : Line()

        /** `V<major.minor…>` — protocol version. */
        class Version(val version: String) : Line()

        /** `M…` — informational message. */
        class Message(val text: String) : Line()
    }

    /** Parse one incoming line by its first character (Radio.cs dispatch). */
    fun parseLine(line: String): Line? {
        if (line.isEmpty()) return null
        return when (line[0]) {
            'R' -> {
                val parts = line.substring(1).split('|')
                if (parts.size < 2) return null
                Line.Reply(
                    parts[0].toIntOrNull() ?: return null,
                    parts[1].toLongOrNull(16) ?: 0L,
                    if (parts.size > 2) parts[2] else "",
                )
            }
            'S' -> {
                val bar = line.indexOf('|')
                if (bar < 1) return null
                Line.Status(
                    line.substring(1, bar).toLongOrNull(16) ?: 0L,
                    line.substring(bar + 1),
                )
            }
            'H' -> Line.Handle(line.substring(1).trim().toLongOrNull(16) ?: return null)
            'V' -> Line.Version(line.substring(1).trim())
            'M' -> Line.Message(line.substring(1))
            else -> null
        }
    }

    /**
     * Decode a DAX-IQ WIDE payload: interleaved float32 BE I/Q pairs scaled
     * by 1/2^15 (VitaIFDataPacket.cs) into [out]. Returns float count.
     */
    fun decodeIfDataWide(buf: ByteArray, offset: Int, bytes: Int, out: FloatArray): Int {
        val n = minOf(bytes / 4, out.size)
        val bb = ByteBuffer.wrap(buf, offset, bytes).order(ByteOrder.BIG_ENDIAN)
        val scale = 1.0f / 32768.0f
        for (i in 0 until n) out[i] = bb.float * scale
        return n
    }

    /** True when the class code is one of the DAX-IQ WIDE rates. */
    fun isWideIq(classCode: Int): Boolean = classCode in CLASS_IF_WIDE_24..CLASS_IF_WIDE_192
}
