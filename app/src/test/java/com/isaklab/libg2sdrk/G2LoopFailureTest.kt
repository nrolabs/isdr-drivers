/*
 * libg2sdrk - Kotlin driver for openHPSDR Protocol-2 radios (Saturn / G2)
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
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, see <http://www.gnu.org/licenses/>.
 */

package com.isaklab.libg2sdrk

import java.net.DatagramSocket
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What must happen when a [G2Client] loop thread dies on a throwable it did
 * not expect.
 *
 * The socket going away under a transmit burst is the ordinary form of it: the
 * pacer is inside `send` when a teardown (or the OS) closes the socket, and the
 * `SocketException` that follows is a shutdown race, not a bug. Two outcomes
 * are unacceptable and this test rules out both:
 *
 *  - the throwable reaching the default handler, which on Android is process
 *    death for the whole host app;
 *  - the thread dying quietly with the connection state left standing — the
 *    pacer is the ONLY sender of TX IQ, so a session that keeps `mox` set
 *    after it dies shows the operator "on air" while nothing is on the air.
 *
 * No radio and no emulator are needed: Protocol-2 command traffic is UDP and
 * fire-and-forget, so the client will happily key up against a port with
 * nothing behind it, which is all this needs.
 */
class G2LoopFailureTest {

    private val portOffset = 24700
    private var client: G2Client? = null
    private val statuses = Collections.synchronizedList(ArrayList<String>())
    private val uncaught = Collections.synchronizedList(ArrayList<Throwable>())
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun installHandler() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, t -> uncaught.add(t) }
    }

    @After
    fun restore() {
        client?.disconnect()
        Thread.sleep(200)
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    }

    private fun field(name: String): Any? =
        G2Client::class.java.getDeclaredField(name).apply { isAccessible = true }.get(client!!)

    @Test
    fun socketClosedUnderAKeyedSessionRetiresItInsteadOfLeavingAZombie() = runBlocking {
        val c = G2Client(
            host = "127.0.0.1",
            onDataReceived = { _, _ -> },
            onConnectionStatusChanged = { _, m -> statuses.add(m) },
            portOffset = portOffset,
        )
        client = c
        c.spectrumEnabled = false
        assertTrue("connect", c.connect())

        val rx = field("receiveThread") as Thread
        val tx = field("txSenderThread") as Thread
        assertTrue("receive loop not running", rx.isAlive)
        assertTrue("transmit loop not running", tx.isAlive)

        // Key up and keep the pacer fed, so it is inside its send burst.
        c.setPtt(true)
        val iq = FloatArray(4800 * 2)
        var ph = 0.0
        for (i in iq.indices step 2) {
            iq[i] = (0.5 * Math.cos(ph)).toFloat()
            iq[i + 1] = (0.5 * Math.sin(ph)).toFloat()
            ph += 2 * Math.PI * 1000.0 / 48_000.0
        }
        val feeder = Thread {
            while (!Thread.currentThread().isInterrupted) {
                c.submitTxIq(iq)
                try { Thread.sleep(50) } catch (e: InterruptedException) { return@Thread }
            }
        }.also { it.isDaemon = true; it.start() }
        Thread.sleep(400)
        assertTrue("the session never keyed", c.isTransmitting())

        (field("socket") as DatagramSocket).close()

        rx.join(4000)
        tx.join(4000)
        feeder.interrupt()
        assertFalse("the receive loop outlived its socket", rx.isAlive)
        assertFalse("the transmit loop outlived its socket", tx.isAlive)
        assertTrue("a loop thread escaped with ${uncaught.firstOrNull()}", uncaught.isEmpty())

        // The whole point: the loops are gone, so the session must be gone too.
        assertFalse("running still set with both loops dead", field("running") as Boolean)
        assertFalse(
            "still reporting transmit with the only TX sender dead — the operator " +
                "is shown on the air with nothing going out",
            c.isTransmitting(),
        )
        assertTrue("the failure was never reported to the host: $statuses",
            statuses.count { it != "Connected" && it != "Discovering…" } > 0)
    }

    /**
     * The transmit pacer alone, killed by a fault the receive loop cannot see.
     *
     * The test above closes the socket, which breaks BOTH loops — so the
     * receive loop's own error handling is enough to retire the session and
     * the pacer's failure path is never really on trial. Here the fault is
     * injected into the pacer's private scratch buffer (any throwable would
     * do; this one is just easy to aim), so the receive loop keeps running
     * normally and only the pacer dies.
     *
     * That is the dangerous shape of the defect: the ONLY sender of TX IQ is
     * gone, nothing else notices, and `mox` stays set — the host shows the
     * operator transmitting while the radio hears nothing and eventually
     * trips its own watchdog.
     */
    @Test
    fun theTransmitPacerDyingAloneStillUnkeysAndRetiresTheSession() = runBlocking {
        val c = G2Client(
            host = "127.0.0.1",
            onDataReceived = { _, _ -> },
            onConnectionStatusChanged = { _, m -> statuses.add(m) },
            portOffset = portOffset + 40,
        )
        client = c
        c.spectrumEnabled = false
        assertTrue("connect", c.connect())
        val tx = field("txSenderThread") as Thread
        val rx = field("receiveThread") as Thread

        // Undersize the pacer's packet scratch: the next interpolated packet
        // runs off the end of it, on the pacer thread and nowhere else.
        G2Client::class.java.getDeclaredField("txPacketIq")
            .apply { isAccessible = true }
            .set(c, FloatArray(4))

        c.setPtt(true)
        val iq = FloatArray(4800 * 2) { 0.3f }
        val feeder = Thread {
            while (!Thread.currentThread().isInterrupted) {
                c.submitTxIq(iq)
                try { Thread.sleep(20) } catch (e: InterruptedException) { return@Thread }
            }
        }.also { it.isDaemon = true; it.start() }

        tx.join(4000)
        feeder.interrupt()
        assertFalse("the pacer survived a throwable it never handled", tx.isAlive)
        assertTrue("a loop thread escaped with ${uncaught.firstOrNull()}", uncaught.isEmpty())

        rx.join(4000)
        assertFalse("the receive loop was left running by a dead pacer", rx.isAlive)
        assertFalse("running still set after the pacer died", field("running") as Boolean)
        assertFalse(
            "still reporting transmit after the only TX sender died",
            c.isTransmitting(),
        )
        assertTrue("the pacer's death was never reported to the host: $statuses",
            statuses.count { it != "Connected" && it != "Discovering…" } > 0)
    }
}
