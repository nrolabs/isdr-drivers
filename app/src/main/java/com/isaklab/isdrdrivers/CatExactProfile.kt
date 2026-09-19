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
import com.isaklab.libkenwoodk.KenwoodModels

/** Evidence an exact profile asks its native client to verify on the wire. */
internal sealed class CatProfileEvidence {
    /** CI-V address reported by 0x19/0x00; operators can configure this address. */
    data class CivAddress(val value: Int) : CatProfileEvidence()

    /** Kenwood's physical model ID returned by the mandatory ID query. */
    data class KenwoodModel(val id: Int, val model: String) : CatProfileEvidence()
}

/** Exact-profile evidence; generic compatibility deliberately claims none. */
internal fun expectedCatProfileEvidence(profile: Int): CatProfileEvidence? = when (profile) {
    DriverProto.CAT_PROFILE_GENERIC -> null
    DriverProto.CAT_PROFILE_IC7300,
    DriverProto.CAT_PROFILE_IC705,
    DriverProto.CAT_PROFILE_IC7610,
    DriverProto.CAT_PROFILE_IC9700,
    DriverProto.CAT_PROFILE_IC905,
    DriverProto.CAT_PROFILE_ICR8600,
    DriverProto.CAT_PROFILE_IC7851,
    -> CatProfileEvidence.CivAddress(checkNotNull(DriverProto.catProfileCivAddress(profile)))
    DriverProto.CAT_PROFILE_TS890 -> CatProfileEvidence.KenwoodModel(
        KenwoodModels.ID_TS890S,
        "TS-890S",
    )
    DriverProto.CAT_PROFILE_TS990 -> CatProfileEvidence.KenwoodModel(
        KenwoodModels.ID_TS990S,
        "TS-990S",
    )
    else -> null
}

/** Exact CI-V evidence delegated to CivClient; generic remains deliberately null. */
internal fun requiredReportedCivAddress(profile: Int): Int? =
    (expectedCatProfileEvidence(profile) as? CatProfileEvidence.CivAddress)?.value

/** Exact Kenwood evidence delegated to KenwoodClient; generic remains deliberately null. */
internal fun requiredKenwoodModelId(profile: Int): Int? =
    (expectedCatProfileEvidence(profile) as? CatProfileEvidence.KenwoodModel)?.id

internal fun catOpenFlagsFailure(flags: Int): String? =
    if (DriverProto.isValidCatOpenFlags(flags)) null
    else "Ambiguous/unknown CAT profile flags $flags"
