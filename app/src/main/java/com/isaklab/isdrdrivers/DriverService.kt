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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.isaklab.isdrproto.DriverProto
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import kotlin.concurrent.thread

/**
 * Foreground service hosting the hardware drivers. Listens on the loopback
 * TCP port [DriverProto.PORT]; each accepted connection becomes an independent
 * [DriverSession] that may open ONE radio. The service stops itself when the
 * last session closes.
 */
class DriverService : Service() {

    companion object {
        private const val TAG = "DriverService"
        private const val CHANNEL_ID = "proxy"
        private const val NOTIFICATION_ID = 1
        const val EXTRA_LAN = "lan"
        const val EXTRA_TOKEN = "token"
        /** Explicit stop: tears sessions/server down even while a client
         *  keeps the service BOUND (plain stopService is inert then). */
        const val ACTION_STOP = "com.isaklab.isdrdrivers.STOP"
        private const val MAX_SESSIONS = 4
        private const val AUTH_TIMEOUT_MS = 5000
    }

    private var server: ServerSocket? = null
    private val sessions = Collections.synchronizedSet(mutableSetOf<DriverSession>())
    @Volatile private var stopping = false
    // LAN exposure is OPT-IN: the app passes EXTRA_LAN=true (remote-station
    // mode) and a shared token the app also holds. Loopback binds need no
    // token — nothing but this device can reach 127.0.0.1.
    @Volatile private var lanEnabled = false
    @Volatile private var accessToken: String? = null

    // A real binder so the iSDR app can bind with BIND_IMPORTANT: that ties
    // this (background) driver process's scheduling importance to the
    // foreground app, keeping it off the OEM throttle cpuset (/abnormal on
    // Samsung) that starved IQ delivery and robotised the audio.
    //
    // It also carries the ONE transaction of the shared-memory IQ ring
    // handshake (SHM_TRANSACT_GET_RING): the app proves ownership of its
    // authenticated TCP session with the CMD_AUTH token and receives the
    // ashmem fd + ring geometry. Data still flows per the wire contract —
    // the binder never moves IQ, only the fd, once.
    private val binder = object : android.os.Binder() {
        override fun onTransact(
            code: Int, data: android.os.Parcel, reply: android.os.Parcel?, flags: Int,
        ): Boolean {
            if (code != DriverProto.SHM_TRANSACT_GET_RING) {
                return super.onTransact(code, data, reply, flags)
            }
            if (reply == null) return true
            val token = try { data.readString() } catch (_: Exception) { null }
            val session = token?.let { t ->
                synchronized(sessions) { sessions.firstOrNull { it.ownsToken(t) } }
            }
            val ring = if (Build.VERSION.SDK_INT >= 27) session?.acquireShm() else null
            if (ring == null) {
                reply.writeInt(0)
            } else {
                val (shm, nSlots, slotBytes) = ring
                reply.writeInt(1)
                reply.writeInt(nSlots)
                reply.writeInt(slotBytes)
                shm.writeToParcel(reply, 0)
            }
            return true
        }
    }
    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Manual stop from the status screen. The iSDR app holds a
            // BINDING (shm handshake binder), so stopService alone never
            // reached onDestroy and the button looked dead: tear the data
            // plane down explicitly, drop the notification, and let the
            // process die when the client unbinds. If iSDR is still in use
            // it will simply restart the host — documented behaviour.
            stopping = true
            try {
                server?.close()
            } catch (_: Exception) {
            }
            server = null
            synchronized(sessions) { sessions.toList() }.forEach { it.close() }
            sessions.clear()
            stopForeground(STOP_FOREGROUND_REMOVE)
            DriverServiceState.update { DriverServiceState.Snapshot() }
            stopSelf()
            return START_NOT_STICKY
        }
        stopping = false
        // A later start with LAN mode re-binds; localhost mode never opens the
        // network. The token gates every LAN session (see DriverSession).
        val wantLan = intent?.getBooleanExtra(EXTRA_LAN, false) == true
        val token = intent?.getStringExtra(EXTRA_TOKEN)
        // The token gates EVERY session, loopback included: any other local
        // app can reach 127.0.0.1:PORT and would otherwise open the radio and
        // key TX. Re-arm the server whenever the mode or the secret changes.
        if (wantLan != lanEnabled || token != accessToken) {
            lanEnabled = wantLan
            accessToken = token
            restartServer()
        }
        startInForeground()
        startServer()
        DriverServiceState.update {
            it.copy(serviceRunning = true, lanExposed = lanEnabled)
        }
        return START_STICKY
    }

    private fun restartServer() {
        try {
            server?.close()
        } catch (_: Exception) {
        }
        server = null
        // Revoke live sessions: closing only the ServerSocket left already-
        // authenticated sockets running under the OLD token (an ejected
        // remote client kept keying the radio). Tear them down (audit H1).
        synchronized(sessions) { sessions.toList() }.forEach { it.close() }
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, DriverActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification =
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION") Notification.Builder(this)
            })
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startServer() {
        if (server != null) return
        // Loopback unless LAN mode is on; in LAN mode bind all interfaces so a
        // remote client (over WireGuard, or directly on a trusted LAN) can
        // reach the driver. LAN sessions must present the shared token.
        val bindAddr = if (lanEnabled) null else InetAddress.getLoopbackAddress()
        val token = accessToken   // required for every session now
        val srv = try {
            ServerSocket(DriverProto.PORT, 4, bindAddr)
        } catch (e: Exception) {
            // Port already bound: another instance of this service is alive.
            Log.w(TAG, "port ${DriverProto.PORT} busy: ${e.message}")
            return
        }
        server = srv
        thread(name = "drivers-accept") {
            try {
                while (!stopping) {
                    val socket = srv.accept()
                    // Cap concurrent sessions: any local app can dial the
                    // loopback port; without a cap it opens N idle sockets and
                    // pins N threads forever (audit H2).
                    if (sessions.size >= MAX_SESSIONS) {
                        Log.w(TAG, "session cap reached — dropping connection")
                        try { socket.close() } catch (_: Exception) {}
                        continue
                    }
                    socket.tcpNoDelay = true
                    socket.sendBufferSize = 8 * 1024 * 1024
                    // Idle-connection timeout: a peer that never sends CMD_AUTH
                    // must not block a session thread indefinitely. DriverSession
                    // clears it once authenticated.
                    socket.soTimeout = AUTH_TIMEOUT_MS
                    Log.i(TAG, "session accepted")
                    val session = DriverSession(applicationContext, socket, token) { done ->
                        sessions.remove(done)
                        DriverServiceState.update { it.copy(sessions = sessions.size) }
                        if (sessions.isEmpty() && !stopping) {
                            Log.i(TAG, "last session closed, stopping")
                            stopSelf()
                        }
                    }
                    sessions.add(session)
                    DriverServiceState.update { it.copy(sessions = sessions.size) }
                    session.start()
                }
            } catch (e: Exception) {
                if (!stopping) Log.e(TAG, "accept loop ended: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        stopping = true
        try {
            server?.close()
        } catch (_: Exception) {
        }
        server = null
        synchronized(sessions) {
            sessions.toList()
        }.forEach { it.close() }
        sessions.clear()
        DriverServiceState.update { DriverServiceState.Snapshot() }
        super.onDestroy()
    }
}
