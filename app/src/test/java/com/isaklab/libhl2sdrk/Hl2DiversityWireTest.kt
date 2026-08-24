package com.isaklab.libhl2sdrk

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Board-visible ordering and refusal semantics of classic P1 diversity. */
class Hl2DiversityWireTest {
    private lateinit var board: DatagramSocket
    private val wire = Collections.synchronizedList(ArrayList<ByteArray>())
    private var reader: Thread? = null
    private var client: Hl2Client? = null

    @Before
    fun startBoard() {
        board = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        reader = Thread {
            val buf = ByteArray(2048)
            while (!Thread.currentThread().isInterrupted) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    board.receive(packet)
                    wire.add(buf.copyOf(packet.length))
                } catch (_: Exception) {
                    return@Thread
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    @After
    fun stopBoard() {
        client?.disconnect()
        board.close()
        reader?.interrupt()
    }

    private fun newClient(profile: Protocol1Profile): Hl2Client = Hl2Client(
        host = "127.0.0.1",
        onDataReceived = { _, _ -> },
        onConnectionStatusChanged = { _, _ -> },
        port = board.localPort,
        profile = profile,
        verifiedBoard = VerifiedProtocol1Board(InetAddress.getByName("127.0.0.1"), profile),
    ).also { client = it }

    private fun isControl(frame: ByteArray): Boolean =
        frame.size == Hl2Protocol.FRAME && frame[0] == 0xEF.toByte() &&
            frame[1] == 0xFE.toByte() && frame[2] == 0x01.toByte()

    private fun addr(frame: ByteArray): Int = (frame[11].toInt() and 0xFE) ushr 1

    private fun be32(frame: ByteArray, offset: Int): Long =
        ((frame[offset].toLong() and 0xFF) shl 24) or
            ((frame[offset + 1].toLong() and 0xFF) shl 16) or
            ((frame[offset + 2].toLong() and 0xFF) shl 8) or
            (frame[offset + 3].toLong() and 0xFF)

    private fun snapshot(): List<ByteArray> = synchronized(wire) { ArrayList(wire) }

    private fun awaitControl() {
        val deadline = System.currentTimeMillis() + 2_000
        while (snapshot().none(::isControl)) {
            assertTrue("timed out waiting for P1 control traffic", System.currentTimeMillis() < deadline)
            Thread.sleep(5)
        }
    }

    @Test
    fun enableRoutesThenLocksNcosThenAdvertisesSyncAndDisableClearsFirst() = runBlocking {
        val c = newClient(Protocol1Profile.ANAN_100D)
        assertTrue(c.connect())
        awaitControl()
        c.setReceiverCount(2)
        c.setRxFrequency(0, 14_200_000)

        val beforeEnable = snapshot().size
        c.setDiversityMode(DiversityMode.RX1_RX2)
        Thread.sleep(40)
        val enabledFrames = snapshot().drop(beforeEnable).filter(::isControl)
        val routeAt = enabledFrames.indexOfFirst {
            addr(it) == 0x0E && (it[12].toInt() and 0xFF) == 0b0000_0100
        }
        assertTrue("typed RX1=ADC1/RX2=ADC2 route never reached addr0x0E", routeAt >= 0)
        val ncoAt = enabledFrames.indices.firstOrNull { index ->
            index > routeAt && addr(enabledFrames[index]) == 2 &&
                be32(enabledFrames[index], 12) == 14_200_000L &&
                be32(enabledFrames[index], 524) == 14_200_000L
        } ?: -1
        assertTrue("paired NCO bank did not follow route", ncoAt > routeAt)
        val syncAt = enabledFrames.indices.firstOrNull { index ->
            index > ncoAt && addr(enabledFrames[index]) == 0 &&
                enabledFrames[index][15].toInt() and 0x80 != 0
        } ?: -1
        assertTrue("sync bit was not advertised after route/NCO", syncAt > ncoAt)
        assertEquals(DiversityMode.RX1_RX2, c.diversityMode())
        assertEquals(RxAdc.ADC1, c.rxAdc(0))
        assertEquals(RxAdc.ADC2, c.rxAdc(1))

        val beforeDisable = snapshot().size
        c.setDiversityMode(DiversityMode.DISABLED)
        c.setRxAdc(1, RxAdc.ADC1)
        Thread.sleep(40)
        val disabledFrames = snapshot().drop(beforeDisable).filter(::isControl)
        val clearAt = disabledFrames.indexOfFirst {
            addr(it) == 0 && it[15].toInt() and 0x80 == 0
        }
        val neutralRouteAt = disabledFrames.indices.firstOrNull { index ->
            index > clearAt && addr(disabledFrames[index]) == 0x0E &&
                (disabledFrames[index][12].toInt() and 0xFF) == 0
        } ?: -1
        assertTrue("sync clear did not precede the route edit", clearAt >= 0 && neutralRouteAt > clearAt)
    }

    @Test
    fun unsupportedOrStoppedTransitionsLeaveConfirmedStateUntouched() = runBlocking {
        val c = newClient(Protocol1Profile.ANAN_10)
        assertTrue(c.connect())
        c.setReceiverCount(2)
        assertThrows(IllegalArgumentException::class.java) {
            c.setDiversityMode(DiversityMode.RX1_RX2)
        }
        assertEquals(DiversityMode.DISABLED, c.diversityMode())
        assertEquals(RxAdc.ADC1, c.rxAdc(1))
        assertThrows(IllegalArgumentException::class.java) { c.setRxAdc(1, RxAdc.ADC2) }
        assertEquals(RxAdc.ADC1, c.rxAdc(1))

        c.disconnect()
        val stopped = newClient(Protocol1Profile.ANAN_100D)
        assertTrue(stopped.connect())
        stopped.setReceiverCount(2)
        stopped.disconnect()
        assertThrows(IllegalStateException::class.java) {
            stopped.setDiversityMode(DiversityMode.RX1_RX2)
        }
        assertEquals(DiversityMode.DISABLED, stopped.diversityMode())
    }
}
