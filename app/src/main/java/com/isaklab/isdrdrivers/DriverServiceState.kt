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

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Live service state for the status screen. The service and its sessions
 * run on background threads while [DriverActivity] observes from the main
 * thread, so updates are marshalled through the main handler and the
 * snapshot is replaced atomically (never mutated).
 */
object DriverServiceState {

    data class Snapshot(
        val serviceRunning: Boolean = false,
        /** True while the driver socket is bound to the LAN (remote mode). */
        val lanExposed: Boolean = false,
        val sessions: Int = 0,
        /** Human-readable open radio ("Hermes-Lite 2 / ANAN"), null when idle. */
        val radio: String? = null,
        /** Last driver status line ("Connected · fw …", "Discovering…"). */
        val radioStatus: String? = null,
        val radioConnected: Boolean = false,
    )

    @Volatile
    var snapshot = Snapshot()
        private set

    private val listeners = CopyOnWriteArraySet<(Snapshot) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun addListener(listener: (Snapshot) -> Unit) {
        listeners.add(listener)
        main.post { listener(snapshot) }
    }

    fun removeListener(listener: (Snapshot) -> Unit) {
        listeners.remove(listener)
    }

    fun update(transform: (Snapshot) -> Snapshot) {
        main.post {
            snapshot = transform(snapshot)
            listeners.forEach { it(snapshot) }
        }
    }
}
