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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What the REAL [Hl2Client] puts on the wire for the N2ADR IO board, read
 * back from a socket standing in for the radio: every I2C passthrough write
 * is decoded out of the control frames (register 0x3D one-shot), so the
 * assertions are about board-visible traffic, not internal flags.
 *
 * Two contracts are under test:
 *
 *  - the external-RX routing pins latch on the board, so leaving the IO
 *    board (switch off, or session ending) must put REG_RF_INPUTS = 0 on the
 *    wire — otherwise RX stays routed to J9 and the main antenna is dead;
 *  - the register reset (REG_CONTROL = 1) wipes all 256 registers, including
 *    the live antenna-tuner state of ATU firmware variants, so it belongs to
 *    the start of a session and must not be re-emitted on every toggle.
 */
class Hl2IoBoardWireTest {

    private class Wire(val port: Int, val data: ByteArray)

    /** One decoded I2C passthrough write as the board would see it. */
    private data class I2cWrite(val port: Int, val device: Int, val reg: Int, val value: Int)

    private lateinit var boardSocket: DatagramSocket
    private val wire = Collections.synchronizedList(ArrayList<Wire>())
    private var reader: Thread? = null
    private var client: Hl2Client? = null

    @Before
    fun startBoard() {
        boardSocket = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        reader = Thread {
            val buf = ByteArray(2048)
            while (!Thread.currentThread().isInterrupted) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    boardSocket.receive(p)
                    wire.add(Wire(p.port, p.data.copyOf(p.length)))
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

    /**
     * All I2C-bus-2 passthrough writes seen so far. A one-shot register write
     * takes over the first sub-frame: C0 holds the address (0x3D for bus 2)
     * shifted left past the MOX bit, C1..C4 hold the 32-bit passthrough word.
     */
    private fun i2cWrites(): List<I2cWrite> = synchronized(wire) { ArrayList(wire) }
        .filter { isControlFrame(it.data) && (it.data[11].toInt() and 0xFE) shr 1 == 0x3D }
        .map {
            val w = ((it.data[12].toInt() and 0xFF) shl 24) or
                ((it.data[13].toInt() and 0xFF) shl 16) or
                ((it.data[14].toInt() and 0xFF) shl 8) or (it.data[15].toInt() and 0xFF)
            I2cWrite(it.port, (w shr 16) and 0x7F, (w shr 8) and 0xFF, w and 0xFF)
        }

    private fun ioWrites(reg: Int) = i2cWrites().filter { it.device == IoBoard.ADDRESS && it.reg == reg }

    /** Source port of the live session: the port the newest packet came from. */
    private fun currentPort(): Int = synchronized(wire) { wire.last().port }

    private fun await(what: String, timeoutMs: Long = 4000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            assertTrue("timed out waiting for $what", System.currentTimeMillis() < deadline)
            Thread.sleep(5)
        }
    }

    /** Connect and wait until the session is actually emitting frames. */
    private fun connected(c: Hl2Client) {
        assertTrue(runBlocking { c.connect() })
        await("first control frame") { synchronized(wire) { wire.any { isControlFrame(it.data) } } }
    }

    // ---- tests --------------------------------------------------------------

    /**
     * Turning the IO board switch off while RX is routed to J9 must undo the
     * routing on the wire. Before the fix the disable only cleared the flag
     * and the mirror stopped pushing, leaving the board holding mode 1.
     */
    @Test
    fun disablingTheBoardRestoresTheInternalRxInput() {
        val c = newClient()
        connected(c)

        c.setIoBoard(enabled = true, rfInput = 1, opMode = 1)
        await("REG_RF_INPUTS = 1") { ioWrites(IoBoard.REG_RF_INPUTS).any { it.value == 1 } }

        c.setIoBoard(enabled = false, rfInput = 1, opMode = 1)
        await("REG_RF_INPUTS = 0") { ioWrites(IoBoard.REG_RF_INPUTS).any { it.value == 0 } }

        // And the neutral write is the LAST routing word the board saw.
        assertEquals(0, ioWrites(IoBoard.REG_RF_INPUTS).last().value)
    }

    /**
     * Same debt, paid by the teardown: the session ends with J9 selected, so
     * the neutral write has to go out while the socket is still open — the
     * control sender is already stopped by then and nothing else would flush
     * the queued register write.
     */
    @Test
    fun disconnectWhileRoutedToJ9RestoresTheInternalRxInput() {
        val c = newClient()
        connected(c)
        val port = currentPort()

        c.setIoBoard(enabled = true, rfInput = 2, opMode = 1)
        await("REG_RF_INPUTS = 2") { ioWrites(IoBoard.REG_RF_INPUTS).any { it.value == 2 } }

        c.disconnect()
        await("REG_RF_INPUTS = 0 from the closing session") {
            ioWrites(IoBoard.REG_RF_INPUTS).any { it.port == port && it.value == 0 }
        }
    }

    /**
     * With the board never routed away from the internal input there is
     * nothing latched to undo, so a disable must stay silent — the neutral
     * write is a repair, not a routine.
     */
    @Test
    fun disablingWithoutExternalInputWritesNothing() {
        val c = newClient()
        connected(c)

        c.setIoBoard(enabled = true, rfInput = 0, opMode = 1)
        // The first batch after the reset re-states every register, mode 0
        // included; wait it out so the count below is a stable baseline.
        await("first mirror batch") { ioWrites(IoBoard.REG_RF_INPUTS).isNotEmpty() }
        Thread.sleep(100)
        val before = ioWrites(IoBoard.REG_RF_INPUTS).size

        c.setIoBoard(enabled = false, rfInput = 0, opMode = 1)
        Thread.sleep(200)
        assertEquals(before, ioWrites(IoBoard.REG_RF_INPUTS).size)
    }

    /**
     * The reset is once per session. Toggling the switch during a tune cycle
     * used to re-emit REG_CONTROL = 1, which zeroes the antenna-tuner
     * register the ATU firmware variants drive as a state machine.
     */
    @Test
    fun theRegisterResetIsNotRepeatedOnEveryToggle() {
        val c = newClient()
        connected(c)

        c.setIoBoard(enabled = true, rfInput = 1, opMode = 1)
        await("first REG_CONTROL reset") { ioWrites(IoBoard.REG_CONTROL).isNotEmpty() }
        assertEquals(1, ioWrites(IoBoard.REG_CONTROL).count { it.value == 1 })

        repeat(3) {
            c.setIoBoard(enabled = false, rfInput = 1, opMode = 1)
            Thread.sleep(120)
            c.setIoBoard(enabled = true, rfInput = 1, opMode = 1)
            // Long enough for a throttled mirror batch (500 ms) to go out.
            Thread.sleep(700)
        }
        assertEquals(1, ioWrites(IoBoard.REG_CONTROL).count { it.value == 1 })
        // The mirror is still alive after the toggles: routing came back.
        assertEquals(1, ioWrites(IoBoard.REG_RF_INPUTS).last().value)
    }

    /**
     * A NEW session does get its own reset: the board's registers survive
     * between connections and may hold the previous session's frequencies.
     */
    @Test
    fun eachSessionResetsTheBoardOnce() {
        val c = newClient()
        connected(c)
        c.setIoBoard(enabled = true, rfInput = 0, opMode = 1)
        await("session 1 reset") { ioWrites(IoBoard.REG_CONTROL).count { it.value == 1 } == 1 }

        c.disconnect()
        await("session 1 gone") { !ioWritePendingSession() }
        connected(c)
        await("session 2 reset") { ioWrites(IoBoard.REG_CONTROL).count { it.value == 1 } == 2 }
    }

    /**
     * The once-per-session rule is scoped to the SESSION, not to the client:
     * a board that was switched off before the reconnect and switched on
     * again afterwards still gets its reset, because the registers it kept
     * are the previous connection's.
     */
    @Test
    fun enablingAfterAReconnectResetsTheBoardAgain() {
        val c = newClient()
        connected(c)
        c.setIoBoard(enabled = true, rfInput = 0, opMode = 1)
        await("session 1 reset") { ioWrites(IoBoard.REG_CONTROL).count { it.value == 1 } == 1 }
        c.setIoBoard(enabled = false, rfInput = 0, opMode = 1)

        c.disconnect()
        await("session 1 gone") { !ioWritePendingSession() }
        connected(c)
        // Disabled at connect time, so nothing is scheduled yet.
        Thread.sleep(200)
        assertEquals(1, ioWrites(IoBoard.REG_CONTROL).count { it.value == 1 })

        c.setIoBoard(enabled = true, rfInput = 0, opMode = 1)
        await("session 2 reset") { ioWrites(IoBoard.REG_CONTROL).count { it.value == 1 } == 2 }
    }

    /** True while the client still publishes a session. */
    private fun ioWritePendingSession(): Boolean {
        val f = Hl2Client::class.java.getDeclaredField("sessionRef").apply { isAccessible = true }
        return (f.get(client) as java.util.concurrent.atomic.AtomicReference<*>).get() != null
    }
}
