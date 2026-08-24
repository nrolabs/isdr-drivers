package com.isaklab.libg2sdrk

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Directed-IP discovery must prove the exact product before OPEN mutates state. */
class G2DiscoveryIdentityTest {

    private class DiscoveryResponder(
        port: Int,
        private val boardId: Int,
    ) : AutoCloseable {
        private val socket = DatagramSocket(port, InetAddress.getByName("127.0.0.1")).apply {
            soTimeout = 200
        }
        private val thread = Thread({ loop() }, "g2-discovery-$boardId").apply {
            isDaemon = true
            start()
        }

        private fun loop() {
            val bytes = ByteArray(2048)
            while (!socket.isClosed) {
                try {
                    val request = DatagramPacket(bytes, bytes.size)
                    socket.receive(request)
                    if (request.length >= 5 && bytes[4].toInt() and 0xff == 0x02) {
                        val reply = ByteArray(60)
                        reply[4] = 0x02
                        byteArrayOf(0x00, 0x1c, 0xc0.toByte(), 0x12, 0x34, 0x56)
                            .copyInto(reply, 5)
                        reply[11] = boardId.toByte()
                        reply[12] = 39
                        reply[13] = 27
                        reply[20] = 7
                        socket.send(DatagramPacket(reply, reply.size, request.address, request.port))
                    }
                } catch (_: SocketTimeoutException) {
                    // Recheck close flag.
                } catch (_: SocketException) {
                    if (!socket.isClosed) throw AssertionError("discovery socket failed")
                }
            }
        }

        override fun close() {
            socket.close()
            thread.join(1_000)
        }
    }

    @Test
    fun directedIp_acceptsAnExactG2Product() = runBlocking {
        val offset = 25_300
        DiscoveryResponder(G2Protocol.GENERAL_PORT + offset, G2Protocol.DiscoveryBoardId.SATURN).use {
            val statuses = Collections.synchronizedList(ArrayList<String>())
            val client = G2Client(
                host = "127.0.0.1",
                onDataReceived = { _, _ -> },
                onConnectionStatusChanged = { _, message -> statuses.add(message) },
                portOffset = offset,
            )
            assertTrue(client.connect())
            assertTrue("Connected" in statuses)
            client.disconnect()
        }
    }

    @Test
    fun directedIp_rejectsHermesWithoutStartingTheSession() = runBlocking {
        val offset = 25_320
        DiscoveryResponder(G2Protocol.GENERAL_PORT + offset, G2Protocol.DiscoveryBoardId.HERMES).use {
            val statuses = Collections.synchronizedList(ArrayList<String>())
            val client = G2Client(
                host = "127.0.0.1",
                onDataReceived = { _, _ -> },
                onConnectionStatusChanged = { _, message -> statuses.add(message) },
                portOffset = offset,
            )
            assertFalse(client.connect())
            assertTrue(
                "unsupported identity was not surfaced: $statuses",
                statuses.any { it.contains("Unsupported") && it.contains("Hermes") },
            )
            assertFalse("a rejected Hermes was reported connected", "Connected" in statuses)
        }
    }
}
