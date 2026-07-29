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
package com.isaklab.isdrdrivers.core

/**
 * What every radio this host can open is able to do.
 *
 * The five radio libraries had already converged on exactly these members,
 * each having arrived at them separately; what was missing was saying so.
 * Without the declaration the host could only reach them through one nullable
 * field per radio, so every command common to all radios fanned out into a
 * list that had to grow by one line per radio — and a sixth radio meant
 * editing every one of those lists, with nothing to catch the one that was
 * forgotten.
 *
 * Capability is expressed by IMPLEMENTING the optional interfaces below, not
 * by a flag. A radio that cannot transmit does not implement [TransmitCapable]
 * and the host cannot call it by mistake; the compiler enforces what a
 * boolean could only describe.
 *
 * ## Adding a radio
 *
 * 1. Implement [RadioClient] in the new library, plus whichever optional
 *    interfaces its hardware genuinely supports.
 * 2. Add its kind to `DriverProto.DEV_*` and open it in the host's
 *    `openDevice`.
 *
 * Nothing else. Every command in the common set reaches it through this
 * contract the moment it is constructed.
 */
interface RadioClient {

    /** Open the hardware or connection. False when it could not be reached. */
    suspend fun connect(): Boolean

    /** Release everything and stop all streaming threads. Idempotent. */
    fun disconnect()

    /** Tune the receiver, in Hz. */
    fun setFrequency(hz: Long)

    /**
     * Set the sampling rate, in Hz. A radio that cannot honour the exact value
     * is expected to apply the nearest rate it can rather than refuse.
     */
    fun setSampleRate(hz: Int)

    /**
     * When false, blocks are delivered with an empty spectrum and the FFT is
     * skipped, because nothing on screen is consuming it. IQ delivery for
     * audio must be unaffected.
     */
    var spectrumEnabled: Boolean
}

/** Radios that can transmit. */
interface TransmitCapable {

    /**
     * Tune the transmitter, in Hz. Separate from [RadioClient.setFrequency]
     * because the host deliberately offsets the RECEIVER from the operator's
     * frequency to keep the wanted signal away from the converter's zero bin,
     * while the transmitted carrier has to land exactly on it.
     */
    fun setTxFrequency(hz: Long)

    /** Key or unkey the transmitter. */
    fun setPtt(on: Boolean)

    /** Queue interleaved 48 kS/s float IQ for transmission. */
    fun submitTxIq(iq: FloatArray)

    fun isTransmitting(): Boolean
}

/** Radios whose transmit level and power amplifier are host-controlled. */
interface TxDriveCapable {
    /** Transmit drive on a 0..255 scale; each radio maps it to its own range. */
    fun setTxDrive(level: Int)
    fun setPaEnabled(on: Boolean)
}

/**
 * Radios that can feed DC up the antenna cable for a mast amplifier or an
 * active antenna — the bias tee.
 *
 * One concept, one name. Two radios here already had it under two different
 * names with two different opcodes, while the app's capability table and its
 * settings screen had always treated it as a single feature.
 */
interface AntennaPowerCapable {
    fun setAntennaPower(on: Boolean)
}

/**
 * Radios with an adjustable analogue anti-alias filter ahead of the
 * converter. Zero means "follow the sample rate automatically", which is what
 * both radios that have this already meant by it.
 */
interface AnalogFilterCapable {
    fun setAnalogFilterHz(hz: Int)
}

/**
 * Radios with a gain stage AHEAD of the mixer, in dB.
 *
 * This is the stage that sets the noise figure, which is why it is worth
 * naming separately from any baseband gain: turning up gain after the mixer
 * raises the noise floor with the signal and never recovers a weak one.
 */
interface LnaGainCapable {
    fun setLnaGain(db: Int)
}
