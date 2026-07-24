package com.isaklab.libhl2sdrk

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phone-mode (fonia) validation of the **HL2** RX frame codec: synthesise
 * board->host RX frames carrying a modulated signal and decode them to I/Q
 * exactly as the driver does ([Hl2Protocol.parseRxFrame]), asserting the
 * recovered stream reproduces the injected waveform sample-for-sample and
 * keeps its spectral content. Demodulation of that I/Q into audio is
 * validated on the app side (its DSP owns the demodulators).
 */
class Hl2FoniaTest {

    private val rate = 48_000
    private val fs = rate.toDouble()
    private val frames = 420          // ~1.1 s at 126 samples/frame (1 RX)

    private fun s24be(v: Int): ByteArray {
        val c = v.coerceIn(-(1 shl 23) + 1, (1 shl 23) - 1)
        return byteArrayOf(((c shr 16) and 0xFF).toByte(), ((c shr 8) and 0xFF).toByte(), (c and 0xFF).toByte())
    }

    /** Build one RX frame whose samples come from [iqAt] (global sample index). */
    private fun buildFrame(seq: Long, startIdx: Int, iqAt: (Int) -> Pair<Double, Double>): ByteArray {
        val buf = ByteArray(Hl2Protocol.FRAME)
        buf[0] = 0xEF.toByte(); buf[1] = 0xFE.toByte(); buf[2] = 0x01; buf[3] = 0x06
        Hl2Protocol.putBE32(buf, 4, seq)
        val amp = (0.5 * (1 shl 23)).toInt()
        var idx = startIdx
        for ((si, base) in intArrayOf(8, 520).withIndex()) {
            buf[base] = 0x7F; buf[base + 1] = 0x7F; buf[base + 2] = 0x7F
            buf[base + 3] = (if (si == 0) (1 shl 3) else (2 shl 3)).toByte()
            var p = base + 8
            repeat(Hl2Protocol.SAMPLES_PER_SUBFRAME) {
                val (i, q) = iqAt(idx++)
                // hardware wire order: imaginary word first (hl2.cxx)
                System.arraycopy(s24be((amp * q).toInt()), 0, buf, p, 3)
                System.arraycopy(s24be((amp * i).toInt()), 0, buf, p + 3, 3)
                buf[p + 6] = 0; buf[p + 7] = 0
                p += 8
            }
        }
        return buf
    }

    /** Decode a stream of frames built from [iqAt] into interleaved I/Q. */
    private fun receive(iqAt: (Int) -> Pair<Double, Double>): FloatArray {
        val out = ArrayList<Float>(frames * 260)
        var idx = 0
        for (seq in 0 until frames.toLong()) {
            val frame = buildFrame(seq, idx, iqAt)
            assertTrue(Hl2Protocol.isRxFrame(frame, frame.size))
            Hl2Protocol.parseRxFrame(frame, 1) { _, i, q -> out.add(i); out.add(q) }
            idx += 2 * Hl2Protocol.SAMPLES_PER_SUBFRAME
        }
        return out.toFloatArray()
    }

    /** Goertzel magnitude of frequency [f] in a real-valued series. */
    private fun tone(x: FloatArray, f: Double): Double {
        val w = 2 * PI * f / fs; val cw = cos(w); val sw = sin(w)
        var s1 = 0.0; var s2 = 0.0
        for (v in x) { val s0 = v + 2 * cw * s1 - s2; s2 = s1; s1 = s0 }
        return hypot(s1 - cw * s2, sw * s2) / x.size
    }

    /** Codec loop: the decoded stream must equal the injected waveform. */
    private fun assertCodecLoop(iqAt: (Int) -> Pair<Double, Double>) {
        val iq = receive(iqAt)
        assertTrue(iq.size >= frames * 2 * 2 * Hl2Protocol.SAMPLES_PER_SUBFRAME)
        // 24-bit quantisation at 0.5 full scale: generous 1e-4 tolerance.
        for (k in 0 until iq.size / 2) {
            val (i, q) = iqAt(k)
            assertTrue(
                "sample $k drifted (i=${iq[2 * k]} expected ${0.5 * i})",
                abs(iq[2 * k] - 0.5 * i) < 1e-4 && abs(iq[2 * k + 1] - 0.5 * q) < 1e-4,
            )
        }
    }

    @Test fun hl2_usb_tone_survives_codec() {
        val f = 1200.0; val w = 2 * PI * f / fs
        assertCodecLoop { k -> cos(w * k) to sin(w * k) }
        // And the spectral line is where it should be on the I branch.
        val iq = receive { k -> cos(w * k) to sin(w * k) }
        val i = FloatArray(iq.size / 2) { iq[2 * it] }
        assertTrue(tone(i, f) > 4.0 * tone(i, f + 900))
    }

    @Test fun hl2_am_envelope_survives_codec() {
        val fa = 1000.0; val m = 0.5; val wa = 2 * PI * fa / fs
        assertCodecLoop { k -> (1.0 + m * cos(wa * k)) / (1.0 + m) to 0.0 }
    }

    @Test fun hl2_fm_phase_survives_codec() {
        val fa = 1000.0; val beta = 2.0; val wa = 2 * PI * fa / fs
        assertCodecLoop { k -> val ph = beta * sin(wa * k); cos(ph) to sin(ph) }
    }
}
