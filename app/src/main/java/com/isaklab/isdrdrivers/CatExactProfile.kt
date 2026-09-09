/*
 * isdr-drivers - GPL driver host for the iSDR app
 *
 * Copyright (C) 2026 Isak Ruas <isakruas@gmail.com>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 */
package com.isaklab.isdrdrivers

import com.isaklab.isdrproto.DriverProto
import com.isaklab.libcivk.CivProtocol
import com.isaklab.libcivk.CivTransport
import com.isaklab.libkenwoodk.KenwoodModels
import com.isaklab.libkenwoodk.KenwoodProtocol
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Physical identity reported by the radio itself during the CAT handshake. */
internal sealed class CatPhysicalIdentity {
    data class Civ(val transceiverId: Int) : CatPhysicalIdentity()
    data class Kenwood(val id: Int, val model: String?) : CatPhysicalIdentity()
}

/** The identity an exact wire profile is allowed to admit. Generic claims none. */
internal fun expectedCatPhysicalIdentity(profile: Int): CatPhysicalIdentity? = when (profile) {
    DriverProto.CAT_PROFILE_GENERIC -> null
    DriverProto.CAT_PROFILE_IC7300,
    DriverProto.CAT_PROFILE_IC705,
    DriverProto.CAT_PROFILE_IC7610,
    DriverProto.CAT_PROFILE_IC9700,
    DriverProto.CAT_PROFILE_IC905,
    DriverProto.CAT_PROFILE_ICR8600,
    DriverProto.CAT_PROFILE_IC7851,
    -> CatPhysicalIdentity.Civ(checkNotNull(DriverProto.catProfileCivAddress(profile)))
    DriverProto.CAT_PROFILE_TS890 -> CatPhysicalIdentity.Kenwood(
        KenwoodModels.ID_TS890S,
        "TS-890S",
    )
    DriverProto.CAT_PROFILE_TS990 -> CatPhysicalIdentity.Kenwood(
        KenwoodModels.ID_TS990S,
        "TS-990S",
    )
    else -> null
}

/**
 * Null means the profile can be admitted. A generic profile deliberately
 * remains compatible and never turns an observed product into an exact claim.
 */
internal fun catPhysicalIdentityFailure(
    profile: Int,
    actual: CatPhysicalIdentity?,
): String? {
    if (profile == DriverProto.CAT_PROFILE_GENERIC) return null
    val expected = expectedCatPhysicalIdentity(profile)
        ?: return "Unknown exact CAT profile $profile"
    if (actual == expected) return null
    return "CAT physical identity mismatch: expected ${expected.describe()}, " +
        (actual?.let { "radio reported ${it.describe()}" } ?: "radio did not report an identity")
}

internal fun catOpenFlagsFailure(flags: Int): String? =
    if (DriverProto.isValidCatOpenFlags(flags)) null
    else "Ambiguous/unknown CAT profile flags $flags"

private fun CatPhysicalIdentity.describe(): String = when (this) {
    is CatPhysicalIdentity.Civ -> "CI-V ID 0x%02X".format(transceiverId)
    is CatPhysicalIdentity.Kenwood -> model?.let { "$it (ID %03d)".format(id) }
        ?: "Kenwood ID %03d".format(id)
}

/**
 * Observes the configured CI-V rig's mandatory 0x19/0x00 identity answer.
 * A wrong or malformed answer aborts [readSome] before CivClient can consume
 * it and proceed to state/scope commands.
 */
internal class ExactCivIdentityTransport(
    private val delegate: CivTransport,
    private val profile: Int,
) : CivTransport {
    private val expected = expectedCatPhysicalIdentity(profile) as? CatPhysicalIdentity.Civ
        ?: throw IllegalArgumentException("profile $profile is not an exact CI-V profile")
    private val readDeframer = CivProtocol.Deframer()
    private val writeDeframer = CivProtocol.Deframer()
    private val awaitingIdentity = AtomicBoolean(false)

    @Volatile
    var physicalIdentity: CatPhysicalIdentity.Civ? = null
        private set

    @Volatile
    var identityFailure: String? = null
        private set

    override fun writeAll(bytes: ByteArray) {
        for (body in writeDeframer.push(bytes)) {
            val frame = CivProtocol.parseFrame(body)
            if (frame is CivProtocol.Frame.Message &&
                frame.to == expected.transceiverId &&
                frame.from == CivProtocol.CONTROLLER_ADDR &&
                frame.cmd == CivProtocol.CMD_READ_ID &&
                frame.data.firstOrNull()?.toInt()?.and(0xFF) == CivProtocol.SUB_ID
            ) {
                awaitingIdentity.set(true)
            }
        }
        delegate.writeAll(bytes)
    }

    override fun readSome(buf: ByteArray): Int {
        val count = delegate.readSome(buf)
        if (count <= 0) return count
        for (body in readDeframer.push(buf, count)) {
            val frame = CivProtocol.parseFrame(body) ?: continue
            if (!awaitingIdentity.get()) continue
            when (frame) {
                is CivProtocol.Frame.Message -> {
                    if (frame.from != expected.transceiverId) continue
                    if (frame.cmd != CivProtocol.CMD_READ_ID) continue
                    val id = frame.data.getOrNull(1)?.toInt()?.and(0xFF)
                    if (frame.data.firstOrNull()?.toInt()?.and(0xFF) != CivProtocol.SUB_ID ||
                        id == null
                    ) {
                        refuse("CAT physical identity mismatch: malformed CI-V ID answer")
                    }
                    awaitingIdentity.set(false)
                    val actual = CatPhysicalIdentity.Civ(checkNotNull(id))
                    physicalIdentity = actual
                    catPhysicalIdentityFailure(profile, actual)?.let(::refuse)
                }
                is CivProtocol.Frame.Ack -> if (frame.from == expected.transceiverId) {
                    refuse("CAT physical identity mismatch: CI-V rig did not return its ID")
                }
                is CivProtocol.Frame.Nak -> if (frame.from == expected.transceiverId) {
                    refuse("CAT physical identity mismatch: CI-V rig did not return its ID")
                }
            }
        }
        return count
    }

    override fun close() = delegate.close()

    private fun refuse(detail: String): Nothing {
        identityFailure = detail
        throw IOException(detail)
    }
}

/**
 * Observes the first physical `ID` answer. A model mismatch is raised before
 * KenwoodClient can consume it and send AI/scope/control commands.
 */
internal class ExactKenwoodIdentityTransport(
    private val delegate: CivTransport,
    private val profile: Int,
) : CivTransport {
    private val expected = expectedCatPhysicalIdentity(profile) as? CatPhysicalIdentity.Kenwood
        ?: throw IllegalArgumentException("profile $profile is not an exact Kenwood profile")
    private val readSplitter = KenwoodProtocol.Splitter()
    private val writeSplitter = KenwoodProtocol.Splitter()
    private val awaitingIdentity = AtomicBoolean(false)

    @Volatile
    var physicalIdentity: CatPhysicalIdentity.Kenwood? = null
        private set

    @Volatile
    var identityFailure: String? = null
        private set

    override fun writeAll(bytes: ByteArray) {
        for (segment in writeSplitter.push(bytes)) {
            if (segment is KenwoodProtocol.Segment.Cmd && segment.body == "ID") {
                awaitingIdentity.set(true)
            }
        }
        delegate.writeAll(bytes)
    }

    override fun readSome(buf: ByteArray): Int {
        val count = delegate.readSome(buf)
        if (count <= 0) return count
        for (segment in readSplitter.push(buf, count)) {
            if (!awaitingIdentity.get()) continue
            when (segment) {
                KenwoodProtocol.Segment.Error ->
                    refuse("CAT physical identity mismatch: Kenwood rig rejected ID query")
                is KenwoodProtocol.Segment.Cmd -> {
                    val id = KenwoodProtocol.parseId(segment.body) ?: continue
                    awaitingIdentity.set(false)
                    val actual = CatPhysicalIdentity.Kenwood(
                        id,
                        KenwoodModels.scopeModel(id)?.name,
                    )
                    physicalIdentity = actual
                    catPhysicalIdentityFailure(profile, actual)?.let(::refuse)
                }
            }
        }
        return count
    }

    override fun close() = delegate.close()

    private fun refuse(detail: String): Nothing {
        identityFailure = detail
        throw IOException(detail)
    }
}

/** Holds exact-profile callbacks until the physical identity is admitted. */
internal class ExactCatAdmissionGate(
    private val downstreamData: (FloatArray, FloatArray) -> Unit,
    private val downstreamStatus: (Boolean, String) -> Unit,
) {
    private val admitted = AtomicBoolean(false)
    private val lock = Any()
    private var connectionFailure: String? = null

    fun onData(spectrum: FloatArray, iq: FloatArray) {
        if (admitted.get()) downstreamData(spectrum, iq)
    }

    fun onStatus(up: Boolean, detail: String) {
        synchronized(lock) {
            if (admitted.get()) {
                downstreamStatus(up, detail)
            } else if (!up && connectionFailure == null) {
                connectionFailure = detail
            }
        }
    }

    fun finish(connected: Boolean, identityFailure: String?, modelName: String): Boolean =
        synchronized(lock) {
            val failure = identityFailure ?: connectionFailure
                ?: if (!connected) "CAT rig did not connect" else null
            if (failure != null) {
                downstreamStatus(false, failure)
                false
            } else {
                // Status precedes data visibility, so the admitted session is
                // never observable without its physical identity terminal.
                downstreamStatus(true, modelName)
                admitted.set(true)
                true
            }
        }
}
