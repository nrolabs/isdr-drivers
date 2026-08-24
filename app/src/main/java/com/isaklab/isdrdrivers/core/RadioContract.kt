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
     * The frequency the receiver is programmed to right now, in Hz — the
     * truth-over-request rule of [sampleRateHz]: what the hardware is on,
     * including a power-up default no session has touched. Zero means the
     * driver cannot say, and the host then announces nothing rather than a
     * guess (EV_FREQUENCY).
     */
    fun frequencyHz(): Long = 0

    /**
     * The rate the hardware is actually running, in Hz — not necessarily the
     * one that was asked for. The host announces it as EV_SAMPLE_RATE at
     * open and after every rate command, so the app scales its spectrum and
     * audio decimation by the truth. Zero = cannot say, nothing announced.
     */
    fun sampleRateHz(): Int = 0

    /**
     * Set the sampling rate, in Hz. A radio that cannot honour the exact value
     * must refuse (normally by throwing); substituting a nearby rate is never
     * an acceptable terminal success.
     */
    fun setSampleRate(hz: Int)

    /**
     * Drivers that apply frequency / rate commands ASYNCHRONOUSLY (a USB
     * command queue) call [listener] once the hardware state actually
     * changed, so the host can announce the truth then — announcing right
     * after the queue accepted the request reports the OLD state and the
     * app scales its display by a rate the radio is no longer running.
     * Synchronous drivers may ignore it (the host announces after the call).
     */
    fun setStateListener(listener: (() -> Unit)?) {}

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
     * Program and confirm the transmit frequency without changing the receive
     * frequency. False means the adapter cannot provide that invariant or the
     * hardware did not confirm it; callers must then reject the command.
     */
    fun setTxFrequency(hz: Long): Boolean

    /** Key or unkey the transmitter. */
    fun setPtt(on: Boolean)

    /** Queue interleaved 48 kS/s float IQ for transmission. */
    fun submitTxIq(iq: FloatArray)

    fun isTransmitting(): Boolean
}

/** A CAT rig whose mode and receive controls have confirmed write results. */
interface CatControlCapable {
    /** Apply [mode] in the shared CAT mode-code space. */
    fun setCatMode(mode: Int): Boolean

    /** Current mode in the shared CAT mode-code space, or -1 when unknown. */
    fun currentCatMode(): Int

    /** Apply one shared CATCTL_* receive control and confirm it. */
    fun setCatControl(id: Int, value: Int): Boolean
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
 * Radios whose transmit buffer timing is host-controlled.
 *
 * [latencyMs] is how much TX audio the radio buffers before RF starts — the
 * slack that absorbs network jitter on the host→board stream. [hangMs] keeps
 * the transmitter keyed after the audio stops so short gaps do not bounce the
 * T/R relay. Each radio validates its own register range and refuses values it
 * cannot represent exactly.
 */
interface TxTimingCapable {
    fun setTxTiming(latencyMs: Int, hangMs: Int)
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
