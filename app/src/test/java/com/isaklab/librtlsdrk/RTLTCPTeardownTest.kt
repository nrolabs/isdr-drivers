/*
 * librtlsdrk - Kotlin driver for RTL-SDR receivers
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

package com.isaklab.librtlsdrk

import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * How an [RTLTCPClient] session must END when the server goes away.
 *
 * A remote rtl_tcp dropping the connection is routine — the dongle is
 * unplugged at the other end, the host sleeps, the Wi-Fi drops. What may not
 * happen is the receive loop reporting the loss and then simply returning:
 * that leaves the TCP socket and the spectrum worker thread alive until some
 * later connect happens to reuse the client, and it leaves the client
 * half-connected in a way no caller can see.
 *
 * The server here answers the rtl_tcp greeting and then closes, which is the
 * EOF path.
 */
class RTLTCPTeardownTest {

    private var server: ServerSocket? = null
    private var accepted: Socket? = null
    private var client: RTLTCPClient? = null
    private val statuses = Collections.synchronizedList(ArrayList<String>())

    @Before
    fun startServer() {
        val s = ServerSocket(0)
        server = s
        Thread {
            try {
                val c = s.accept()
                accepted = c
                // "RTL0" + tuner type + gain count, then hang up.
                c.getOutputStream().write(
                    byteArrayOf(
                        'R'.code.toByte(), 'T'.code.toByte(), 'L'.code.toByte(), '0'.code.toByte(),
                        0, 0, 0, 5,
                        0, 0, 0, 29,
                    ),
                )
                c.getOutputStream().flush()
                Thread.sleep(300)
                c.close()
            } catch (_: Exception) {
            }
        }.also { it.isDaemon = true }.start()
    }

    @After
    fun stopServer() {
        client?.disconnect()
        accepted?.close()
        server?.close()
    }

    private fun field(name: String): Any? =
        RTLTCPClient::class.java.getDeclaredField(name).apply { isAccessible = true }.get(client!!)

    @Test
    fun theServerHangingUpTearsTheWholeSessionDown() = runBlocking {
        val c = RTLTCPClient(
            host = "127.0.0.1",
            port = server!!.localPort,
            onDataReceived = { _, _ -> },
            onConnectionStatusChanged = { _, m -> statuses.add(m) },
        )
        client = c
        c.spectrumEnabled = false
        assertTrue("connect", c.connect())

        val rx = field("receiveThread") as Thread
        rx.join(5000)
        assertFalse("the receive loop is still running on a dead connection", rx.isAlive)
        Thread.sleep(200)

        assertTrue("the loss was never reported: $statuses", statuses.contains("Server closed"))
        assertFalse("still reporting connected with the loop gone",
            field("isConnected") as Boolean)
        val sock = field("socket") as Socket
        assertTrue("the TCP socket was left open", sock.isClosed)
        val worker = field("spectrumWorker")!!
        val workerThread = worker.javaClass.getDeclaredField("thread")
            .apply { isAccessible = true }.get(worker) as Thread?
        assertFalse(
            "the spectrum worker thread was left running",
            workerThread != null && workerThread.isAlive,
        )
    }
}
