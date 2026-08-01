/*
 * libhl2sdrk - Kotlin driver for the Hermes-Lite 2 SDR transceiver
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

package com.isaklab.libhl2sdrk

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Session lifecycle of the REAL [Hl2Client] against a socket standing in for
 * the board: it never answers, it only records what the host puts on the wire,
 * tagged with the SOURCE PORT — which is what separates one session from the
 * next, since each connect opens a fresh socket.
 *
 * What is under test is the teardown contract:
 *
 *  - a disconnect that lands mid-over must unkey ON THE WIRE, on the clean
 *    path, while the socket is still open (the gateware TX watchdog is the
 *    last resort, not the mechanism);
 *  - the EP2 frame counter must never go backwards, including over the unkey
 *    frames the teardown adds after the pacer's last one;
 *  - a teardown owed by an older session must not reach the next one: no
 *    stop frame from the new socket, and no MOX drop while the operator is
 *    holding the new session keyed.
 *
 * The last one is a race by nature; it is exercised repeatedly with jitter
 * around the reconnect, and every iteration asserts the invariant.
 */
class Hl2SessionLifecycleTest {

    /** One packet as the board saw it: source port identifies the session. */
    private class Wire(val port: Int, val data: ByteArray)

    private lateinit var boardSocket: DatagramSocket
    private val wire = Collections.synchronizedList(ArrayList<Wire>())
    private var reader: Thread? = null
    private var client: Hl2Client? = null

    /**
     * Anything a driver loop thread lets escape lands here. On Android this
     * handler is the one that kills the PROCESS, so "the list stayed empty" is
     * literally the assertion "the host app survived".
     */
    private val uncaught = Collections.synchronizedList(ArrayList<Throwable>())
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun startBoard() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, t -> uncaught.add(t) }
        boardSocket = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        reader = Thread {
            val buf = ByteArray(2048)
            while (!Thread.currentThread().isInterrupted) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    boardSocket.receive(p)
                    wire.add(Wire(p.port, buf.copyOf(p.length)))
                } catch (_: Exception) {
                    return@Thread
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    @After
    fun stopBoard() {
        client?.disconnect()
        boardSocket.close()
        reader?.interrupt()
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    }

    private fun newClient(): Hl2Client = Hl2Client(
        host = "127.0.0.1",
        onDataReceived = { _, _ -> },
        onConnectionStatusChanged = { _, _ -> },
        port = boardSocket.localPort,
    ).also { client = it }

    // ---- wire helpers -------------------------------------------------------

    private fun isControlFrame(d: ByteArray) = d.size == Hl2Protocol.FRAME &&
        d[0] == 0xEF.toByte() && d[1] == 0xFE.toByte() && d[2] == 0x01.toByte()

    private fun isStop(d: ByteArray) = d.size == 64 &&
        d[0] == 0xEF.toByte() && d[1] == 0xFE.toByte() &&
        d[2] == 0x04.toByte() && d[3] == 0x00.toByte()

    /** MOX bit as the board decodes it: C0 bit 0 of both sub-frames. */
    private fun mox(d: ByteArray) = (d[11].toInt() and 1) == 1 && (d[523].toInt() and 1) == 1

    private fun seq(d: ByteArray): Long =
        ((d[4].toLong() and 0xFF) shl 24) or ((d[5].toLong() and 0xFF) shl 16) or
            ((d[6].toLong() and 0xFF) shl 8) or (d[7].toLong() and 0xFF)

    private fun snapshot(): List<Wire> = synchronized(wire) { ArrayList(wire) }

    private fun framesFrom(port: Int) = snapshot().filter { it.port == port && isControlFrame(it.data) }

    /** Source port of the live session: the port the newest packet came from. */
    private fun currentPort(): Int = snapshot().last().port

    /** Wait until [port] has produced at least [n] control frames. */
    private fun awaitFrames(port: Int, n: Int, timeoutMs: Long = 3000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (framesFrom(port).size < n) {
            assertTrue("no $n frames from port $port", System.currentTimeMillis() < deadline)
            Thread.sleep(5)
        }
    }

    // ---- tests --------------------------------------------------------------

    /**
     * Disconnecting mid-over is the clean path, and it is exactly the path
     * that has to put MOX=0 on the wire: the pacer stops before the socket
     * closes, so nothing else will ever send it.
     */
    @Test
    fun disconnectWhileKeyedUnkeysOnTheWire() {
        val c = newClient()
        runBlocking { assertTrue(c.connect()) }
        val port = currentPort()
        c.setPtt(true)
        awaitFrames(port, 40)
        assertTrue("board never saw MOX=1", framesFrom(port).any { mox(it.data) })

        c.disconnect()
        Thread.sleep(600)

        val frames = framesFrom(port)
        val lastKeyed = frames.indexOfLast { mox(it.data) }
        assertTrue("no unkey frame after the last keyed one", lastKeyed < frames.size - 1)
        assertTrue(
            "the frames after the last keyed one must all carry MOX=0",
            frames.drop(lastKeyed + 1).none { mox(it.data) },
        )
        // …and the unkey must precede the stop, not trail it.
        val packets = snapshot().filter { it.port == port }
        val stopAt = packets.indexOfLast { isStop(it.data) }
        val unkeyAt = packets.indexOfLast { isControlFrame(it.data) && !mox(it.data) }
        assertTrue("no stop frame sent", stopAt >= 0)
        assertTrue("unkey must go out before the stop", unkeyAt < stopAt)
    }

    /**
     * The EP2 counter is how the board detects host frame loss. The teardown
     * adds frames of its own after the pacer's last one, so numbering and
     * transmission have to stay in the same order — a frame that is numbered
     * first must also leave first.
     */
    @Test
    fun ep2SequenceNeverGoesBackwards() {
        val c = newClient()
        runBlocking { assertTrue(c.connect()) }
        val port = currentPort()
        c.setPtt(true)
        awaitFrames(port, 200)
        c.disconnect()
        Thread.sleep(600)

        var previous = -1L
        for (f in framesFrom(port)) {
            val s = seq(f.data)
            assertTrue("EP2 sequence went backwards: $previous then $s", s > previous)
            previous = s
        }
        // A session numbers its own frames from zero; nothing is inherited.
        assertEquals(0L, seq(framesFrom(port).first().data))
    }

    /**
     * A teardown still owed by the previous session must not reach the new
     * one: it may not stop its stream, and it may not drop the MOX the
     * operator is holding on it.
     */
    @Test
    fun staleTeardownCannotDisturbTheNextSession() {
        repeat(12) { round ->
            val c = newClient()
            runBlocking { assertTrue(c.connect()) }
            val oldPort = currentPort()
            c.setPtt(true)
            awaitFrames(oldPort, 20)

            // Reconnect while the teardown of the session above is still in
            // flight, with the jitter walking across the window.
            c.disconnect()
            Thread.sleep((round % 6).toLong())
            runBlocking { assertTrue(c.connect()) }
            val newPort = currentPort()
            assertTrue("reconnect reused the old socket", newPort != oldPort)

            c.setPtt(true)
            awaitFrames(newPort, 60)
            // Give the stale teardown every chance to land on the new session.
            Thread.sleep(200)
            awaitFrames(newPort, 120)

            val fresh = framesFrom(newPort)
            val keyedAt = fresh.indexOfFirst { mox(it.data) }
            assertTrue("round $round: new session never keyed", keyedAt >= 0)
            assertTrue(
                "round $round: MOX dropped on the new session while still keyed",
                fresh.drop(keyedAt).all { mox(it.data) },
            )
            // The connect handshake itself is stop, stop, start — what must
            // not appear is a stop AFTER the session began streaming.
            val packets = snapshot().filter { it.port == newPort }
            val streamingFrom = packets.indexOfFirst { isControlFrame(it.data) }
            assertFalse(
                "round $round: the new session was stopped by a stale teardown",
                packets.drop(streamingFrom).any { isStop(it.data) },
            )

            c.setPtt(false)
            c.disconnect()
            Thread.sleep(50)
        }
    }

    // ---- reflection into the live session ----------------------------------
    //
    // The session is deliberately private — it is the driver's own bookkeeping
    // and no caller may reach it. The failure being reproduced here, though, is
    // precisely "someone closed the socket under a running transmit burst",
    // which on the wire is indistinguishable from the link dying and cannot be
    // provoked from the public API without a real board.

    private fun liveSession(c: Hl2Client): Any {
        val f = Hl2Client::class.java.getDeclaredField("sessionRef").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val ref = f.get(c) as java.util.concurrent.atomic.AtomicReference<Any?>
        return requireNotNull(ref.get()) { "no live session" }
    }

    private fun sessionField(session: Any, name: String): Any? =
        session.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(session)

    /**
     * The socket closing under a transmit burst must not take the process
     * down. The transmit loop is inside its inner burst (up to 16 sends per
     * beat) when the socket goes away; the `SocketException` that follows is
     * an ordinary shutdown race, not a bug, and an unhandled one on any thread
     * is fatal to the whole app on Android.
     *
     * The contract asserted: no throwable reaches the default handler, both
     * loop threads finish, and the session is retired with a status — it does
     * not linger half-alive.
     */
    @Test
    fun socketClosedDuringTxBurstDoesNotKillTheProcess() {
        val statuses = Collections.synchronizedList(ArrayList<String>())
        val c = Hl2Client(
            host = "127.0.0.1",
            onDataReceived = { _, _ -> },
            onConnectionStatusChanged = { _, m -> statuses.add(m) },
            port = boardSocket.localPort,
        ).also { client = it }
        runBlocking { assertTrue(c.connect()) }
        val port = currentPort()
        c.setPtt(true)
        awaitFrames(port, 60)                       // bursts are definitely running

        val session = liveSession(c)
        val tx = sessionField(session, "txSenderThread") as Thread
        val rx = sessionField(session, "receiveThread") as Thread
        // Both handles are published: a teardown in this state can always find
        // the pacer to stop it.
        assertTrue("pacer handle not published", tx.isAlive)
        assertTrue("receive handle not published", rx.isAlive)

        (sessionField(session, "socket") as DatagramSocket).close()

        tx.join(3000)
        rx.join(3000)
        assertFalse("the transmit loop is still running on a dead socket", tx.isAlive)
        assertFalse("the receive loop is still running on a dead socket", rx.isAlive)
        assertTrue(
            "a loop thread escaped with ${uncaught.firstOrNull()}",
            uncaught.isEmpty(),
        )
        assertTrue("the session was never retired: $statuses", statuses.size > 1)
    }

    /**
     * The pacer must be stopped before the teardown puts its own frames on the
     * wire — otherwise its frames interleave with the unkey/stop ones and the
     * board sees EP2 numbering it cannot reconcile. Observable form: the stop
     * frame is the LAST thing this session's socket ever sends.
     */
    @Test
    fun pacerIsSilentAfterTheStopFrame() {
        val c = newClient()
        runBlocking { assertTrue(c.connect()) }
        val port = currentPort()
        c.setPtt(true)
        awaitFrames(port, 80)

        c.disconnect()
        Thread.sleep(800)

        val packets = snapshot().filter { it.port == port }
        val stopAt = packets.indexOfLast { isStop(it.data) }
        assertTrue("no stop frame sent", stopAt >= 0)
        assertEquals(
            "the pacer kept sending after the teardown's stop frame",
            emptyList<Int>(),
            packets.drop(stopAt + 1).map { it.data.size },
        )
    }
}
