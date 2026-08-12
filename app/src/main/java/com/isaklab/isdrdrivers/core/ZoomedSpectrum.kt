package com.isaklab.isdrdrivers.core

/**
 * The panadapter window and the transform as one thing, so every radio narrows
 * its span the same way instead of each growing its own version of this.
 *
 * At 1x the block reaches the transform exactly as it arrived: the zoomed path
 * exists only when the operator asked for one.
 */
class ZoomedSpectrum(val fft: FFTProcessor) {

    private val zoom = SpectrumZoom()
    private var accum = FloatArray(0)
    private var accumLen = 0

    val decimation: Int get() = zoom.decimation

    /** Point the window; returns the decimation actually in force. */
    fun setZoom(decimation: Int, offsetHz: Double, rateHz: Double): Int {
        val was = zoom.decimation
        zoom.set(decimation, offsetHz, rateHz)
        if (zoom.decimation != was) {
            // A different span is a different picture: smoothing across the
            // change would drag the old span's shape into the new one.
            fft.resetSmoothing()
            zoom.reset()
            accumLen = 0
        }
        return zoom.decimation
    }

    /**
     * A power spectrum for this block, or null when a zoomed window has not
     * collected a whole transform yet — a partial one padded with whatever the
     * buffer held would be a picture of nothing.
     */
    fun compute(iq: FloatArray, pairs: Int): FloatArray? {
        if (zoom.decimation == 1) {
            accumLen = 0
            return fft.computePowerSpectrum(iq, pairs)
        }
        val bins = fft.bins
        val produced = zoom.process(iq, 2 * pairs)
        val n = zoom.outputFloats
        if (accum.size < accumLen + n) {
            accum = accum.copyOf((accumLen + n).coerceAtLeast(4 * bins))
        }
        System.arraycopy(produced, 0, accum, accumLen, n)
        accumLen += n
        if (accumLen / 2 < bins) return null
        // A deep zoom at a low sample rate fills slowly; without a ceiling the
        // backlog would grow into a display showing what the band looked like
        // seconds ago.
        val cap = 2 * bins * MAX_ACCUM_TRANSFORMS
        var from = 0
        if (accumLen > cap) from = accumLen - cap
        val spectrum =
            fft.computePowerSpectrum(accum.copyOfRange(from, accumLen), (accumLen - from) / 2)
        accumLen = 0
        return spectrum
    }

    private companion object {
        const val MAX_ACCUM_TRANSFORMS = 4
    }
}
