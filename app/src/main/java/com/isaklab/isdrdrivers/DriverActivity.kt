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

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Status screen for the driver host: shows whether the service is running,
 * how many client sessions are attached and which radio is open, with a
 * manual start/stop for troubleshooting. Normal users never need to open it —
 * the iSDR app starts the service on demand — but when something is wrong
 * this screen must answer "is it alive?" at a glance.
 */
class DriverActivity : AppCompatActivity() {

    private val stateListener: (DriverServiceState.Snapshot) -> Unit = { render(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_driver_host)

        findViewById<TextView>(R.id.tvVersion).text =
            getString(R.string.fmt_version, BuildConfig.VERSION_NAME)

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            requestNoBatteryThrottle()
            ContextCompat.startForegroundService(this, Intent(this, DriverService::class.java))
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            // Explicit stop action: plain stopService is inert while the
            // iSDR app keeps the service bound (shm binder) — the button
            // looked dead exactly when a client was attached.
            ContextCompat.startForegroundService(
                this,
                Intent(this, DriverService::class.java).setAction(DriverService.ACTION_STOP),
            )
        }
        findViewById<Button>(R.id.btnSource).setOnClickListener {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.source_url)))
            )
        }
    }

    override fun onStart() {
        super.onStart()
        DriverServiceState.addListener(stateListener)
        requestNoBatteryThrottle()
    }

    /**
     * Ask to be exempt from battery optimization so the OEM power manager
     * (Samsung's /abnormal cpuset, Doze) does not throttle the real-time IQ
     * delivery onto the little cores — the cause of "robotic" audio. Shown
     * once; the system remembers the choice.
     */
    private fun requestNoBatteryThrottle() {
        try {
            val pm = getSystemService(android.os.PowerManager::class.java)
            if (pm?.isIgnoringBatteryOptimizations(packageName) == true) return
            @android.annotation.SuppressLint("BatteryLife")
            val intent = Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName"),
            )
            startActivity(intent)
        } catch (_: Exception) {
        }
    }

    override fun onStop() {
        super.onStop()
        DriverServiceState.removeListener(stateListener)
    }

    private fun render(s: DriverServiceState.Snapshot) {
        findViewById<android.view.View>(R.id.dotService).setBackgroundResource(
            if (s.serviceRunning) R.drawable.bg_dot_running else R.drawable.bg_dot_stopped,
        )
        findViewById<TextView>(R.id.tvServiceState).setText(
            if (s.serviceRunning) R.string.status_service_running
            else R.string.status_service_stopped,
        )
        findViewById<TextView>(R.id.tvSessions).text =
            resources.getQuantityString(R.plurals.fmt_sessions, s.sessions, s.sessions)
        findViewById<TextView>(R.id.tvRadio).text =
            getString(R.string.fmt_radio, s.radio ?: getString(R.string.status_no_radio))
        val statusRow = findViewById<TextView>(R.id.tvRadioStatus)
        if (s.radioStatus.isNullOrEmpty()) {
            statusRow.visibility = android.view.View.GONE
        } else {
            statusRow.visibility = android.view.View.VISIBLE
            statusRow.text = s.radioStatus
        }
        findViewById<Button>(R.id.btnStart).isEnabled = !s.serviceRunning
        findViewById<Button>(R.id.btnStop).isEnabled = s.serviceRunning
    }
}
