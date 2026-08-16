package com.isaklab.isdrdrivers

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

/**
 * Keeps the CPU and the Wi-Fi radio awake for as long as a radio session is
 * live. A foreground service alone keeps the PROCESS alive; it does not stop
 * the kernel from idling the CPU between UDP packets nor the Wi-Fi driver
 * from entering power save with the screen off — both of which turn a
 * Hermes-Lite (packet every 0.65 ms, host watchdog) or any streaming link
 * into a string of drop-outs and disconnects the moment the screen sleeps.
 *
 * Reference-counted: acquire per session, release per session; the locks
 * are held while at least one is live and dropped when the last one goes.
 */
class RadioLocks(context: Context, tag: String) {
    private val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val wake: PowerManager.WakeLock =
        pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$tag:radio").apply { setReferenceCounted(false) }
    private val wifi: WifiManager.WifiLock = run {
        // Low-latency mode (API 29+) also disables Wi-Fi power save and asks
        // for the fastest scan/roam policy; high-perf is the older equivalent.
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wm.createWifiLock(mode, "$tag:radio").apply { setReferenceCounted(false) }
    }
    private var holders = 0
    private val handler = Handler(Looper.getMainLooper())

    // The wake lock is taken with a bounded timeout and renewed while held:
    // a session that ends without a release (process killed mid-stream)
    // then costs at most one renewal period of battery, not the night.
    private val renew = object : Runnable {
        override fun run() {
            synchronized(this@RadioLocks) {
                if (holders > 0) {
                    wake.acquire(WAKE_RENEW_MS * 2)
                    handler.postDelayed(this, WAKE_RENEW_MS)
                }
            }
        }
    }

    @Synchronized
    fun acquire() {
        holders++
        if (holders == 1) {
            wake.acquire(WAKE_RENEW_MS * 2)
            handler.removeCallbacks(renew)
            handler.postDelayed(renew, WAKE_RENEW_MS)
            if (!wifi.isHeld) wifi.acquire()
        }
    }

    @Synchronized
    fun release() {
        if (holders == 0) return
        holders--
        if (holders == 0) releaseAll()
    }

    @Synchronized
    fun releaseAll() {
        holders = 0
        handler.removeCallbacks(renew)
        if (wake.isHeld) wake.release()
        if (wifi.isHeld) wifi.release()
    }

    private companion object {
        const val WAKE_RENEW_MS = 10L * 60L * 1000L
    }
}
