package com.isaklab.isdrdrivers.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Decimating front end for the panadapter: the difference between a zoom that
 * magnifies pixels and a zoom that magnifies the spectrum.
 *
 * The transform has a fixed number of bins, so its resolution is the span
 * divided by that number: at 8 MS/s each bin is 10 kHz wide and two CW signals
 * a kilohertz apart are one blob. Slicing the finished bins cannot recover
 * what the transform never separated — the only way to resolve them is to
 * narrow the span BEFORE the transform. This mixes the wanted centre to zero,
 * low-passes to the new Nyquist and keeps one pair in [decimation], which
 * hands the transform a stream whose whole width is the zoomed span.
 *
 * Only what the transform consumes is filtered: filling an N-bin transform at
 * decimation D needs N*D input pairs, not a second's worth. That is what keeps
 * a deep zoom on a fast stream affordable on a phone.
 *
 * At 1x this is bypassed entirely and the input is handed on untouched, so the
 * unzoomed display is exactly what it was before this existed.
 */
class SpectrumZoom {

    var decimation: Int = 1
        private set
    private var offsetHz = 0.0
    private var rateHz = 0.0

    /** Low-pass at the decimated Nyquist; empty at 1x. */
    private var taps = FloatArray(0)

    /** The tail of the previous block, so the filter has history across
     *  frames instead of ringing at every block edge. */
    private var carry = FloatArray(0)
    private var carryLen = 0

    // Mixer phasor, advanced per input pair.
    private var oscR = 1.0
    private var oscI = 0.0
    private var stepR = 1.0
    private var stepI = 0.0

    private var scratch = FloatArray(0)
    private var out = FloatArray(0)
    private var outLen = 0

    /** Span the transform will see, given the stream rate. */
    fun spanHz(rateHz: Double): Double = rateHz / decimation

    /**
     * Point the window: [decimation] >= 1, [offsetHz] from the stream centre.
     * Rebuilds the filter only when the factor actually changes, so dragging a
     * zoom control does not restart the filter every frame.
     */
    fun set(decimation: Int, offsetHz: Double, rateHz: Double) {
        val d = decimation.coerceIn(1, MAX_DECIMATION)
        if (d != this.decimation) {
            this.decimation = d
            taps = if (d == 1) FloatArray(0) else designTaps(d)
            carry = FloatArray(2 * taps.size.coerceAtLeast(1))
            carryLen = 0
        }
        if (offsetHz != this.offsetHz || rateHz != this.rateHz) {
            this.offsetHz = offsetHz
            this.rateHz = rateHz
            // Mixing DOWN by the offset brings that frequency to zero, which
            // is where the transform's centre bin is.
            val w = if (rateHz > 0.0) -2.0 * PI * offsetHz / rateHz else 0.0
            stepR = cos(w)
            stepI = sin(w)
            oscR = 1.0
            oscI = 0.0
        }
    }

    /** How many input pairs are needed to produce [pairs] output pairs. */
    fun inputPairsFor(pairs: Int): Int = pairs * decimation

    /** Drop the filter history and the mixer phase, for a new stream. */
    fun reset() {
        carryLen = 0
        oscR = 1.0
        oscI = 0.0
    }

    /** Number of interleaved floats [process] produced. */
    var outputFloats = 0
        private set

    /**
     * Filter and decimate interleaved IQ. At 1x the input is returned as it
     * came; [outputFloats] says how much of the result is valid.
     */
    fun process(iq: FloatArray, floats: Int = iq.size): FloatArray {
        if (decimation == 1) {
            outputFloats = floats
            return iq
        }
        val nTaps = taps.size
        val pairs = floats / 2
        val need = 2 * (nTaps - 1 + pairs)
        if (scratch.size < need) scratch = FloatArray(need)
        System.arraycopy(carry, 0, scratch, 0, carryLen)
        // Mix first: the low-pass is centred on zero, so the wanted part of
        // the spectrum has to be there before it is applied.
        var cr = oscR
        var ci = oscI
        var w = carryLen
        for (p in 0 until pairs) {
            val i = iq[2 * p].toDouble()
            val q = iq[2 * p + 1].toDouble()
            scratch[w++] = (i * cr - q * ci).toFloat()
            scratch[w++] = (i * ci + q * cr).toFloat()
            val nr = cr * stepR - ci * stepI
            val ni = cr * stepI + ci * stepR
            // The phasor is a repeated multiplication, so its magnitude
            // drifts; pull it back before the drift is a gain error.
            val m = 1.5 - 0.5 * (nr * nr + ni * ni)
            cr = nr * m
            ci = ni * m
        }
        oscR = cr
        oscI = ci

        val avail = w / 2
        val nOut = if (avail >= nTaps) (avail - nTaps) / decimation + 1 else 0
        if (out.size < 2 * nOut) out = FloatArray(2 * nOut)
        for (n in 0 until nOut) {
            val base = n * decimation
            var si = 0f
            var sq = 0f
            for (t in 0 until nTaps) {
                val tap = taps[t]
                si += tap * scratch[2 * (base + t)]
                sq += tap * scratch[2 * (base + t) + 1]
            }
            out[2 * n] = si
            out[2 * n + 1] = sq
        }
        // Keep the tail the next block needs to continue without an edge.
        val consumed = nOut * decimation
        val keep = avail - consumed
        if (carry.size < 2 * keep) carry = FloatArray(2 * keep)
        System.arraycopy(scratch, 2 * consumed, carry, 0, 2 * keep)
        carryLen = 2 * keep
        outLen = 2 * nOut
        outputFloats = outLen
        return out
    }

    companion object {
        /** Taps per polyphase branch. Eight is enough for the stopband to sit
         *  below the display's floor: what leaks past it is an alias, and an
         *  alias on a panadapter is a signal drawn where nothing is
         *  transmitting. */
        private const val TAPS_PER_PHASE = 8

        /** Largest decimation offered. Beyond this the window is narrower than
         *  most radios' own narrowband plan, which is the better tool. */
        const val MAX_DECIMATION = 64

        /** Windowed-sinc low-pass at the decimated Nyquist, normalised to
         *  unity gain at DC so zooming does not change how loud the display
         *  reads. */
        private fun designTaps(decimation: Int): FloatArray {
            val n = (decimation * TAPS_PER_PHASE) or 1
            val fc = 0.5 / decimation
            val t = DoubleArray(n)
            for (i in 0 until n) {
                val x = i - (n - 1) / 2.0
                val sinc =
                    if (kotlin.math.abs(x) < 1e-12) 2.0 * fc
                    else sin(2.0 * PI * fc * x) / (PI * x)
                // Blackman: the stopband has to be far below the display
                // floor, or an out-of-span signal is drawn inside the span.
                val a = 2.0 * PI * i / (n - 1)
                t[i] = sinc * (0.42 - 0.5 * cos(a) + 0.08 * cos(2 * a))
            }
            val sum = t.sum()
            return FloatArray(n) { (if (sum != 0.0) t[it] / sum else t[it]).toFloat() }
        }
    }
}
