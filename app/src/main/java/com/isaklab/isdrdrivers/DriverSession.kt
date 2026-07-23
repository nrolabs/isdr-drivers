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

    private var rtlTcp: RTLTCPClient? = null
    private var rtlUsb: RTLUSBClient? = null
    private var hackRf: HackRfClient? = null
    private var hl2: Hl2Client? = null
    private var g2: G2Client? = null

    fun start() {
        thread(name = "driver-session") { readLoop() }
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

    private fun onData(fft: FloatArray, iq: FloatArray) = send { frames.writeData(fft, iq) }
    private fun onDataRx(rx: Int, iq: FloatArray) = send { frames.writeDataRx(rx, iq) }

    private fun onStatus(connected: Boolean, status: String) {
        DriverServiceState.update {
            it.copy(radioStatus = status, radioConnected = connected)
        }
        send { frames.writeStatus(connected, status) }
    }

    private fun onHl2Telemetry(t: Hl2Protocol.Telemetry) = send {
        frames.writeTelemetry(
            RadioTelemetry(
                temperatureC = t.temperatureC, paCurrentA = t.paCurrentA,
                forwardPower = t.forwardPower.toDouble(),
                reversePower = t.reversePower.toDouble(),
                supplyVolts = t.supplyVolts,
                adcOverload = t.adcOverflow,
                keyPtt = t.keyPtt, keyDot = t.keyDot, keyDash = t.keyDash,
                hasTemperature = t.hasTemperature, hasCurrent = t.hasCurrent,
                hasFwdPower = t.hasFwdPower, hasRevPower = t.hasRevPower,
                hasSupplyVolts = t.hasSupplyVolts,
                hasAdcOverload = t.hasAdcOverflow,
                hasKeyInputs = true,
            )
        )
    }

    private fun onG2Status(st: G2Protocol.Status) = send {
        frames.writeTelemetry(
            RadioTelemetry(
                forwardPower = st.forwardPower.toDouble(),
                reversePower = st.reversePower.toDouble(),
                supplyVolts = st.supplyVolts,
                exciterPower = st.exciterPower.toDouble(),
                adcOverload = st.adcOverload,
                pllLocked = st.pllLocked,
                keyPtt = st.pttIn, keyDot = st.dot, keyDash = st.dash,
                hasFwdPower = true, hasRevPower = true, hasSupplyVolts = true,
                hasAdcOverload = true, hasPllLock = true,
                hasExciterPower = true, hasKeyInputs = true,
            )
        )
    }

    private fun onSweepBlock(lowerEdgeHz: Long, iq: FloatArray) =
        send { frames.writeSweepBlock(lowerEdgeHz, iq) }

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
                send { frames.writeHello(DriverProto.VERSION, DriverProto.FEAT_RX_STREAMS) }
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

            DriverProto.CMD_SET_FREQUENCY -> p.long.let { hz ->
                rtlTcp?.setFrequency(hz)
                rtlUsb?.sendCommand(RTLCommand.SetFrequency(hz))
                hackRf?.setFrequency(hz)
                hl2?.setFrequency(hz)
                g2?.setFrequency(hz)
            }
            DriverProto.CMD_SET_SAMPLE_RATE -> p.int.let { hz ->
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
                hackRf?.setPtt(on)
                hl2?.setPtt(on)
                g2?.setPtt(on)
                sendTxState()
            }
            DriverProto.CMD_SET_TX_DRIVE -> p.int.let { level ->
                hl2?.setTxDrive(level)
                g2?.setTxDrive(level)
            }
            DriverProto.CMD_SET_PA_ENABLED -> p.getBool().let { on ->
                hl2?.setPaEnabled(on)
                g2?.setPaEnabled(on)
            }
            DriverProto.CMD_TX_IQ -> p.getFloats().let { iq ->
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
            DriverProto.CMD_SET_RX_STREAM_MASK -> hl2?.setRxStreamMask(p.int)

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
        DriverServiceState.update { it.copy(radio = radioName(kind, flags)) }
        val ok = try {
            when (kind) {
                DriverProto.DEV_RTL_TCP -> {
                    val c = RTLTCPClient(host, port, ::onData, ::onStatus)
                    rtlTcp = c
                    c.connect()
                }
                DriverProto.DEV_RTL_USB -> {
                    val c = RTLUSBClient(context, ::onData, ::onStatus)
                    rtlUsb = c
                    c.connect()
                }
                DriverProto.DEV_HACKRF -> {
                    val c = HackRfClient(context, ::onData, ::onStatus)
                    hackRf = c
                    c.connect()
                }
                DriverProto.DEV_HL2 -> {
                    val c = Hl2Client(
                        host = host.ifEmpty { Hl2Client.BROADCAST },
                        onDataReceived = ::onData,
                        onConnectionStatusChanged = ::onStatus,
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
                        onConnectionStatusChanged = ::onStatus,
                        onStatus = ::onG2Status,
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
        DriverProto.DEV_HL2 ->
            if (flags and DriverProto.OPEN_FLAG_CLASSIC_BOARD != 0) "ANAN (Protocol 1)"
            else "Hermes-Lite 2"
        DriverProto.DEV_G2 -> "ANAN-G2 (Saturn)"
        else -> "?"
    }

    private fun closeDevice() {
        try {
            hl2?.setPtt(false)
            g2?.setPtt(false)
            hackRf?.setPtt(false)
        } catch (_: Exception) {
        }
        try {
            rtlTcp?.disconnect()
            rtlUsb?.disconnect()
            hackRf?.disconnect()
            hl2?.disconnect()
            g2?.disconnect()
        } catch (_: Exception) {
        }
        rtlTcp = null
        rtlUsb = null
        hackRf = null
        hl2 = null
        g2 = null
        DriverServiceState.update {
            it.copy(radio = null, radioStatus = null, radioConnected = false)
        }
    }
}
