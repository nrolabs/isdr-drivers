/*
 * isdr-drivers - GPL driver host for the iSDR app
 *
 * Copyright (C) 2026 Isak Ruas <isakruas@gmail.com>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 */
package com.isaklab.isdrdrivers

import android.content.ContextWrapper
import com.isaklab.isdrdrivers.core.RadioClient
import com.isaklab.isdrdrivers.core.TransmitCapable
import com.isaklab.isdrproto.DriverProto
import com.isaklab.isdrproto.Frames
import com.isaklab.isdrproto.getBool
import com.isaklab.isdrproto.getUtf
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DriverSessionOpenLifecycleTest {
    private class FakeRadio : RadioClient {
        val disconnectCount = AtomicInteger()
        @Volatile var currentFrequency = 0L
        @Volatile var currentSampleRate = 0
        private val disconnectedOnce = AtomicBoolean(false)
        val disconnected: Boolean get() = disconnectCount.get() != 0
        override suspend fun connect(): Boolean = true
        override fun disconnect() {
            if (disconnectedOnce.compareAndSet(false, true)) disconnectCount.incrementAndGet()
        }
        override fun setFrequency(hz: Long) = Unit
        override fun frequencyHz(): Long = currentFrequency
        override fun setSampleRate(hz: Int) = Unit
        override fun sampleRateHz(): Int = currentSampleRate
        override var spectrumEnabled: Boolean = false
    }

    private class LateAllocRadio : RadioClient {
        val connectEntered = CountDownLatch(1)
        val releaseConnect = CountDownLatch(1)
        val disconnectCount = AtomicInteger()
        val allocated = AtomicBoolean(false)

        override suspend fun connect(): Boolean {
            connectEntered.countDown()
            check(releaseConnect.await(2, TimeUnit.SECONDS))
            allocated.set(true)
            return true
        }

        override fun disconnect() {
            disconnectCount.incrementAndGet()
            allocated.set(false)
        }

        override fun setFrequency(hz: Long) = Unit
        override fun frequencyHz(): Long = 0L
        override fun setSampleRate(hz: Int) = Unit
        override fun sampleRateHz(): Int = 0
        override var spectrumEnabled: Boolean = false
    }

    private class FakeTxRadio : RadioClient, TransmitCapable {
        val pttCalls = AtomicInteger()
        override suspend fun connect(): Boolean = true
        override fun disconnect() = Unit
        override fun setFrequency(hz: Long) = Unit
        override fun setSampleRate(hz: Int) = Unit
        override var spectrumEnabled: Boolean = false
        override fun setTxFrequency(hz: Long): Boolean = true
        override fun setPtt(on: Boolean) {
            pttCalls.incrementAndGet()
        }
        override fun submitTxIq(iq: FloatArray) = Unit
        override fun isTransmitting(): Boolean = true
    }

    @Test
    fun `CMD_CLOSE during connecting emits only cancellation then original false`() {
        val sockets = sessionSockets()
        val radio = FakeRadio()
        val session = authenticatedSession(sockets.server)
        setField(session, "radio", radio)
        val gate = gate(session)
        gate.begin() as OpenEpochGate.Begin.Started
        session.start()

        try {
            sockets.client.soTimeout = 1_000
            val wire = wire(sockets.client)
            wire.write(DriverProto.CMD_CLOSE)

            val status = checkNotNull(wire.read())
            assertEquals(DriverProto.EV_STATUS, status.op)
            assertFalse(status.payload.getBool())
            assertEquals("Open cancelled", status.payload.getUtf())
            val result = checkNotNull(wire.read())
            assertEquals(DriverProto.EV_OPEN_RESULT, result.op)
            assertFalse(result.payload.getBool())
            assertEquals(0, result.payload.remaining())
            assertTrue(radio.disconnected)

            sockets.client.soTimeout = 100
            assertThrows(SocketTimeoutException::class.java) { wire.read() }
        } finally {
            session.close()
            sockets.client.close()
        }
    }

    @Test
    fun `CONNECTING progress false followed by CLOSE remains cancellation`() {
        val sockets = sessionSockets()
        val radio = FakeRadio()
        val session = authenticatedSession(sockets.server)
        setField(session, "radio", radio)
        val gate = gate(session)
        val epoch = (gate.begin() as OpenEpochGate.Begin.Started).epoch
        assertNull(gate.status(epoch, false, "Connecting…"))
        session.start()

        try {
            sockets.client.soTimeout = 1_000
            val wire = wire(sockets.client)
            wire.write(DriverProto.CMD_CLOSE)
            val status = checkNotNull(wire.read())
            assertEquals(DriverProto.EV_STATUS, status.op)
            assertFalse(status.payload.getBool())
            assertEquals("Open cancelled", status.payload.getUtf())
            val result = checkNotNull(wire.read())
            assertEquals(DriverProto.EV_OPEN_RESULT, result.op)
            assertFalse(result.payload.getBool())
        } finally {
            session.close()
            sockets.client.close()
        }
    }

    @Test
    fun `COMMITTING physical failure followed by CLOSE preserves its cause`() {
        val sockets = sessionSockets()
        val radio = FakeRadio()
        val session = authenticatedSession(sockets.server)
        setField(session, "radio", radio)
        val gate = gate(session)
        val epoch = (gate.begin() as OpenEpochGate.Begin.Started).epoch
        assertEquals(OpenEpochGate.CommitStart.Ready, gate.startCommit(epoch))
        assertNull(gate.status(epoch, false, "USB link lost"))
        session.start()

        try {
            sockets.client.soTimeout = 1_000
            val wire = wire(sockets.client)
            wire.write(DriverProto.CMD_CLOSE)
            val status = checkNotNull(wire.read())
            assertEquals(DriverProto.EV_STATUS, status.op)
            assertFalse(status.payload.getBool())
            assertEquals("USB link lost", status.payload.getUtf())
            sockets.client.soTimeout = 150
            assertThrows(SocketTimeoutException::class.java) { wire.read() }
        } finally {
            session.close()
            sockets.client.close()
        }
    }

    @Test
    fun `published failure does not consume pending replacement result`() {
        val sockets = sessionSockets()
        val session = authenticatedSession(sockets.server)
        val gate = gate(session)
        val radio = FakeRadio()
        val a = publish(gate, "A")
        setField(session, "radio", radio)
        gate.begin() as OpenEpochGate.Begin.Started // replacement B in preflight
        val aTerminal = checkNotNull(gate.status(a, false, "A link lost"))
        invokeDeliverStatus(session, a, false, "A link lost", aTerminal)
        session.start()

        try {
            sockets.client.soTimeout = 1_000
            val wire = wire(sockets.client)
            wire.write(DriverProto.CMD_CLOSE)

            val aStatus = checkNotNull(wire.read())
            assertEquals(DriverProto.EV_STATUS, aStatus.op)
            assertFalse(aStatus.payload.getBool())
            assertEquals("A link lost", aStatus.payload.getUtf())
            val bStatus = checkNotNull(wire.read())
            assertEquals(DriverProto.EV_STATUS, bStatus.op)
            assertFalse(bStatus.payload.getBool())
            assertEquals("Open cancelled", bStatus.payload.getUtf())
            val bResult = checkNotNull(wire.read())
            assertEquals(DriverProto.EV_OPEN_RESULT, bResult.op)
            assertFalse(bResult.payload.getBool())
            sockets.client.soTimeout = 150
            assertThrows(SocketTimeoutException::class.java) { wire.read() }
        } finally {
            session.close()
            sockets.client.close()
        }
    }

    @Test
    fun `second pending CMD_OPEN closes with EOF and no ambiguous result`() {
        val sockets = sessionSockets()
        val closed = CountDownLatch(1)
        val session = authenticatedSession(sockets.server) { closed.countDown() }
        gate(session).begin() as OpenEpochGate.Begin.Started
        session.start()

        try {
            sockets.client.soTimeout = 1_000
            val wire = wire(sockets.client)
            wire.writeOpen(DriverProto.DEV_RTL_TCP, "127.0.0.1", 1, 0)
            assertNull(wire.read())
            assertTrue(closed.await(1, TimeUnit.SECONDS))
        } finally {
            session.close()
            sockets.client.close()
        }
    }

    @Test(timeout = 5_000)
    fun `CLOSE waits for blocked install cleanup before a successor OPEN`() {
        val sockets = sessionSockets()
        val session = authenticatedSession(sockets.server)
        val gate = gate(session)
        val wire = wire(sockets.client)
        val firstEpoch = (gate.begin() as OpenEpochGate.Begin.Started).epoch
        val firstRadio = FakeRadio()
        val installed = AtomicBoolean(true)
        session.start()

        try {
            sockets.client.soTimeout = 1_000
            val installLock = radioLock(session)
            lateinit var installThread: Thread
            synchronized(installLock) {
                installThread = thread(name = "blocked-candidate-install") {
                    installed.set(
                        session.installCandidate(firstEpoch, firstRadio, "first") {},
                    )
                }
                awaitCondition { firstEpoch.activeCallbacks != 0 }
                wire.write(DriverProto.CMD_CLOSE)
                awaitCondition { !gate.isConnecting(firstEpoch) }

                // closeDevice has revoked the epoch but must still be waiting
                // for install(), which is blocked immediately before pointer
                // mutation on radioLock.
                sockets.client.soTimeout = 100
                assertThrows(SocketTimeoutException::class.java) { wire.read() }
            }
            installThread.join(1_000)
            assertFalse(installed.get())
            assertTrue(firstRadio.disconnected)

            sockets.client.soTimeout = 1_000
            val cancelled = checkNotNull(wire.read())
            assertEquals(DriverProto.EV_STATUS, cancelled.op)
            assertEquals("Open cancelled", cancelled.payload.run {
                assertFalse(getBool())
                getUtf()
            })
            val cancelledResult = checkNotNull(wire.read())
            assertEquals(DriverProto.EV_OPEN_RESULT, cancelledResult.op)
            assertFalse(cancelledResult.payload.getBool())

            val rtlListener = ServerSocket(0)
            val releaseRtl = CountDownLatch(1)
            val rtlThread = thread(name = "successor-rtl") {
                rtlListener.accept().use { rtl ->
                    DataOutputStream(BufferedOutputStream(rtl.getOutputStream())).apply {
                        writeBytes("RTL0")
                        writeInt(5)
                        writeInt(0)
                        flush()
                    }
                    releaseRtl.await(2, TimeUnit.SECONDS)
                }
            }
            try {
                wire.writeOpen(
                    DriverProto.DEV_RTL_TCP,
                    "127.0.0.1",
                    rtlListener.localPort,
                    0,
                )
                assertEquals(DriverProto.EV_OPEN_RESULT, checkNotNull(wire.read()).op)
                assertEquals(DriverProto.EV_STATUS, checkNotNull(wire.read()).op)
                assertEquals(DriverProto.EV_SAMPLE_RATE, checkNotNull(wire.read()).op)
                assertEquals(DriverProto.EV_RTL_INFO, checkNotNull(wire.read()).op)
                assertEquals(
                    normalizedDeviceKey(
                        DriverProto.DEV_RTL_TCP,
                        "127.0.0.1",
                        rtlListener.localPort,
                        0,
                    ),
                    stringField(session, "currentDeviceKey"),
                )
            } finally {
                releaseRtl.countDown()
                rtlListener.close()
                rtlThread.join(2_000)
            }
        } finally {
            session.close()
            sockets.client.close()
        }
    }

    @Test(timeout = 5_000)
    fun `CLOSE during connect waits and tears down resources allocated after cancellation`() {
        val sockets = sessionSockets()
        val session = authenticatedSession(sockets.server)
        val gate = gate(session)
        val epoch = (gate.begin() as OpenEpochGate.Begin.Started).epoch
        val radio = LateAllocRadio()
        setField(session, "radio", radio)
        val attempt = checkNotNull(gate.acquireAttempt(epoch))
        val connected = AtomicBoolean(true)
        val connectDone = CountDownLatch(1)
        val connectThread = thread(name = "late-alloc-connect") {
            try {
                connected.set(runBlocking { session.connectCandidate(epoch, radio) })
            } finally {
                gate.releaseAttempt(attempt)
                connectDone.countDown()
            }
        }
        val closeDone = CountDownLatch(1)

        try {
            assertTrue(radio.connectEntered.await(1, TimeUnit.SECONDS))
            val closeThread = thread(name = "close-during-connect") {
                invokeCloseDevice(session)
                closeDone.countDown()
            }
            awaitCondition { !gate.isConnecting(epoch) }
            assertTrue("first cancellation teardown was not requested", radio.disconnectCount.get() >= 1)
            assertFalse("CLOSE returned before connect reached terminal cleanup", closeDone.await(100, TimeUnit.MILLISECONDS))

            radio.releaseConnect.countDown()
            assertTrue(connectDone.await(1, TimeUnit.SECONDS))
            assertTrue(closeDone.await(1, TimeUnit.SECONDS))
            connectThread.join(1_000)
            closeThread.join(1_000)
            assertFalse(connected.get())
            assertFalse("late resource survived cancellation", radio.allocated.get())
            assertTrue("late allocation did not receive final teardown", radio.disconnectCount.get() >= 2)
        } finally {
            radio.releaseConnect.countDown()
            session.close()
            sockets.client.close()
        }
    }

    @Test
    fun `RTL open publishes result before status and initial state`() {
        val rtlListener = ServerSocket(0)
        val releaseRtl = CountDownLatch(1)
        val rtlDone = CountDownLatch(1)
        val rtlThread = thread(name = "fake-rtl") {
            rtlListener.accept().use { rtl ->
                val out = DataOutputStream(BufferedOutputStream(rtl.getOutputStream()))
                out.writeBytes("RTL0")
                out.writeInt(5)
                out.writeInt(0)
                out.write(ByteArray(32_768))
                out.flush()
                releaseRtl.await(2, TimeUnit.SECONDS)
            }
            rtlDone.countDown()
        }
        val sockets = sessionSockets()
        val session = authenticatedSession(sockets.server)
        session.start()

        try {
            sockets.client.soTimeout = 2_000
            val wire = wire(sockets.client)
            wire.writeOpen(DriverProto.DEV_RTL_TCP, "127.0.0.1", rtlListener.localPort, 0)

            val result = checkNotNull(wire.read())
            assertEquals(DriverProto.EV_OPEN_RESULT, result.op)
            assertTrue(result.payload.getBool())
            val status = checkNotNull(wire.read())
            assertEquals(DriverProto.EV_STATUS, status.op)
            assertTrue(status.payload.getBool())
            assertTrue(status.payload.getUtf().startsWith("Connected"))
            val sampleRate = checkNotNull(wire.read())
            assertEquals(DriverProto.EV_SAMPLE_RATE, sampleRate.op)
            assertEquals(2_048_000, sampleRate.payload.int)
            val rtlInfo = checkNotNull(wire.read())
            assertEquals(DriverProto.EV_RTL_INFO, rtlInfo.op)
        } finally {
            session.close()
            sockets.client.close()
            releaseRtl.countDown()
            rtlListener.close()
            rtlThread.join(2_000)
            assertTrue(rtlDone.await(1, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `successful connect without status callback publishes fallback status`() {
        val sockets = sessionSockets()
        val session = authenticatedSession(sockets.server)
        val epoch = (gate(session).begin() as OpenEpochGate.Begin.Started).epoch
        val radio = FakeRadio()
        setField(session, "radio", radio)
        session.start()

        try {
            enqueueOpenSuccess(session, epoch, radio, "Fallback radio")
            sockets.client.soTimeout = 1_000
            val wire = wire(sockets.client)

            val result = checkNotNull(wire.read())
            assertEquals(DriverProto.EV_OPEN_RESULT, result.op)
            assertTrue(result.payload.getBool())
            val status = checkNotNull(wire.read())
            assertEquals(DriverProto.EV_STATUS, status.op)
            assertTrue(status.payload.getBool())
            assertEquals("Fallback radio", status.payload.getUtf())
        } finally {
            session.close()
            sockets.client.close()
        }
    }

    @Test
    fun `initial state is captured after OPEN publication wins the writer`() {
        val sockets = sessionSockets()
        val session = authenticatedSession(sockets.server)
        val epoch = (gate(session).begin() as OpenEpochGate.Begin.Started).epoch
        val radio = FakeRadio().apply {
            currentSampleRate = 48_000
            currentFrequency = 7_100_000L
        }
        setField(session, "radio", radio)
        enqueueOpenSuccess(session, epoch, radio, "Mutable radio")

        // The success item is queued but the writer has not started. These
        // values are the hardware truth at the publication boundary.
        radio.currentSampleRate = 96_000
        radio.currentFrequency = 14_200_000L
        session.start()

        try {
            sockets.client.soTimeout = 1_000
            val wire = wire(sockets.client)
            assertEquals(DriverProto.EV_OPEN_RESULT, checkNotNull(wire.read()).op)
            assertEquals(DriverProto.EV_STATUS, checkNotNull(wire.read()).op)
            val sampleRate = checkNotNull(wire.read())
            assertEquals(DriverProto.EV_SAMPLE_RATE, sampleRate.op)
            assertEquals(96_000, sampleRate.payload.int)
            val frequency = checkNotNull(wire.read())
            assertEquals(DriverProto.EV_FREQUENCY, frequency.op)
            assertEquals(14_200_000L, frequency.payload.long)
        } finally {
            session.close()
            sockets.client.close()
        }
    }

    @Test(timeout = 5_000)
    fun `terminal false after publication wins cannot be overwritten by writer local state`() {
        val sockets = sessionSockets()
        val session = authenticatedSession(sockets.server)
        val gate = gate(session)
        val epoch = (gate.begin() as OpenEpochGate.Begin.Started).epoch
        val radio = FakeRadio().apply {
            currentSampleRate = 48_000
            currentFrequency = 7_100_000L
        }
        setField(session, "radio", radio)
        enqueueOpenSuccess(session, epoch, radio, "Connected")
        val falseDelivered = CountDownLatch(1)

        try {
            // Hold the local-delivery lock after finishCommit() publishes but
            // before the writer can set ready=true/capture state.
            synchronized(statusDeliveryLock(session)) {
                session.start()
                awaitCondition { epoch.phase == OpenEpochGate.Phase.PUBLISHED }
                val terminal = checkNotNull(gate.status(epoch, false, "RF link lost"))
                thread(name = "terminal-after-publish") {
                    invokeDeliverStatus(session, epoch, false, "RF link lost", terminal)
                    falseDelivered.countDown()
                }
            }

            assertTrue(falseDelivered.await(1, TimeUnit.SECONDS))
            sockets.client.soTimeout = 1_000
            val wire = wire(sockets.client)
            val result = checkNotNull(wire.read())
            assertEquals(DriverProto.EV_OPEN_RESULT, result.op)
            assertTrue(result.payload.getBool())
            val terminal = checkNotNull(wire.read())
            assertEquals(DriverProto.EV_STATUS, terminal.op)
            assertFalse(terminal.payload.getBool())
            assertEquals("RF link lost", terminal.payload.getUtf())
            assertFalse(booleanField(session, "radioControlReady"))
            sockets.client.soTimeout = 150
            assertThrows(SocketTimeoutException::class.java) { wire.read() }
        } finally {
            session.close()
            sockets.client.close()
        }
    }

    @Test(timeout = 5_000)
    fun `rejected commit owns terminal effects while CLOSE waits`() {
        val sockets = sessionSockets()
        val session = authenticatedSession(sockets.server)
        val gate = gate(session)
        val epoch = (gate.begin() as OpenEpochGate.Begin.Started).epoch
        val radio = FakeRadio()
        setField(session, "radio", radio)
        assertNull(gate.status(epoch, false, "USB link lost"))
        enqueueOpenSuccess(session, epoch, radio, "Connected")
        val closeDone = CountDownLatch(1)

        try {
            synchronized(radioLock(session)) {
                session.start()
                awaitCondition {
                    epoch.phase == OpenEpochGate.Phase.TERMINATING &&
                        epoch.activeCallbacks != 0
                }
                thread(name = "close-after-commit-rejected") {
                    invokeCloseDevice(session)
                    closeDone.countDown()
                }
                assertFalse(closeDone.await(100, TimeUnit.MILLISECONDS))
            }

            assertTrue(closeDone.await(1, TimeUnit.SECONDS))
            sockets.client.soTimeout = 1_000
            val wire = wire(sockets.client)
            val status = checkNotNull(wire.read())
            assertEquals(DriverProto.EV_STATUS, status.op)
            assertFalse(status.payload.getBool())
            assertEquals("USB link lost", status.payload.getUtf())
            val result = checkNotNull(wire.read())
            assertEquals(DriverProto.EV_OPEN_RESULT, result.op)
            assertFalse(result.payload.getBool())
            assertFalse(booleanField(session, "radioControlReady"))
            assertNull(radioField(session))
            sockets.client.soTimeout = 150
            assertThrows(SocketTimeoutException::class.java) { wire.read() }
        } finally {
            session.close()
            sockets.client.close()
        }
    }

    @Test(timeout = 5_000)
    fun `failed OPEN owns exact terminal effects while CLOSE waits`() {
        val sockets = sessionSockets()
        val session = authenticatedSession(sockets.server)
        val gate = gate(session)
        val epoch = (gate.begin() as OpenEpochGate.Begin.Started).epoch
        val radio = FakeRadio()
        setField(session, "radio", radio)
        val failureDone = CountDownLatch(1)
        val closeDone = CountDownLatch(1)
        session.start()

        try {
            synchronized(radioLock(session)) {
                thread(name = "blocked-fail-open") {
                    invokeFailOpen(session, epoch, radio, "USB permission denied")
                    failureDone.countDown()
                }
                awaitCondition {
                    epoch.phase == OpenEpochGate.Phase.TERMINATING &&
                        epoch.activeCallbacks != 0
                }
                thread(name = "close-during-fail-open") {
                    invokeCloseDevice(session)
                    closeDone.countDown()
                }
                assertFalse(closeDone.await(100, TimeUnit.MILLISECONDS))
            }

            assertTrue(failureDone.await(1, TimeUnit.SECONDS))
            assertTrue(closeDone.await(1, TimeUnit.SECONDS))
            sockets.client.soTimeout = 1_000
            val wire = wire(sockets.client)
            val status = checkNotNull(wire.read())
            assertEquals(DriverProto.EV_STATUS, status.op)
            assertFalse(status.payload.getBool())
            assertEquals("USB permission denied", status.payload.getUtf())
            val result = checkNotNull(wire.read())
            assertEquals(DriverProto.EV_OPEN_RESULT, result.op)
            assertFalse(result.payload.getBool())
            assertFalse(booleanField(session, "radioControlReady"))
            assertNull(radioField(session))
            sockets.client.soTimeout = 150
            assertThrows(SocketTimeoutException::class.java) { wire.read() }
        } finally {
            session.close()
            sockets.client.close()
        }
    }

    @Test
    fun `stale true status cannot restore local state after terminal false`() {
        val sockets = sessionSockets()
        val session = authenticatedSession(sockets.server)
        val gate = gate(session)
        val epoch = publish(gate, "Connected")
        val staleTrue = checkNotNull(gate.status(epoch, true, "late true"))
        val terminalFalse = checkNotNull(gate.status(epoch, false, "link lost"))

        try {
            // Execute the two already-admitted callbacks in the hostile order:
            // false first, then the older true that had paused after leasing.
            invokeDeliverStatus(session, epoch, false, "link lost", terminalFalse)
            invokeDeliverStatus(session, epoch, true, "late true", staleTrue)
            assertFalse(booleanField(session, "radioControlReady"))
        } finally {
            session.close()
            sockets.client.close()
        }
    }

    @Test
    fun `specific published link failure is not replaced by CLOSE`() {
        val sockets = sessionSockets()
        val session = authenticatedSession(sockets.server)
        val radio = FakeRadio()
        val epoch = publish(gate(session), "Connected")
        setField(session, "radio", radio)
        session.start()

        try {
            sockets.client.soTimeout = 1_000
            callback2<Boolean, String>(session, "statusFor", epoch)(false, "RF link lost")
            val wire = wire(sockets.client)
            wire.write(DriverProto.CMD_CLOSE)

            val status = checkNotNull(wire.read())
            assertEquals(DriverProto.EV_STATUS, status.op)
            assertFalse(status.payload.getBool())
            assertEquals("RF link lost", status.payload.getUtf())
            awaitCondition { radio.disconnectCount.get() == 1 }
            sockets.client.soTimeout = 150
            assertThrows(SocketTimeoutException::class.java) { wire.read() }
            assertEquals(1, radio.disconnectCount.get())
        } finally {
            session.close()
            sockets.client.close()
        }
    }

    @Test
    fun `watchdog state from replaced radio cannot unkey successor`() {
        val sockets = sessionSockets()
        val session = authenticatedSession(sockets.server)
        val gate = gate(session)
        val a = FakeTxRadio()
        publish(gate, "A")
        setField(session, "radio", a)
        setField(session, "pttOn", true)
        setField(session, "keyedAtMs", 1L)
        setField(session, "lastTxIqMs", 1L)

        val bEpoch = (gate.begin() as OpenEpochGate.Begin.Started).epoch
        assertTrue(gate.handover(bEpoch).accepted)
        assertEquals(a, invokeDetachRadio(session))
        val b = FakeTxRadio()
        assertTrue(session.installCandidate(bEpoch, b, "B") {})
        gate.status(bEpoch, true, "B")
        assertEquals(OpenEpochGate.CommitStart.Ready, gate.startCommit(bEpoch))
        val published = gate.finishCommit(bEpoch) as OpenEpochGate.CommitFinish.Published
        gate.release(published.lease)

        try {
            assertFalse(invokeTxWatchdogTick(session, 1_000_000L))
            assertEquals(0, b.pttCalls.get())
        } finally {
            session.close()
            sockets.client.close()
        }
    }

    @Test
    fun `failed replacement retires prior radio before exact OPEN failure`() {
        val sockets = sessionSockets()
        val session = authenticatedSession(sockets.server)
        val prior = FakeRadio()
        publish(gate(session), "A")
        setField(session, "radio", prior)
        session.start()

        try {
            sockets.client.soTimeout = 1_000
            val wire = wire(sockets.client)
            wire.writeOpen(DriverProto.DEV_HPSDR_P1, "", 0, 1)

            val status = checkNotNull(wire.read())
            assertEquals(DriverProto.EV_STATUS, status.op)
            assertFalse(status.payload.getBool())
            assertTrue(status.payload.getUtf().contains("Ambiguous/unknown Protocol-1 profile"))
            val result = checkNotNull(wire.read())
            assertEquals(DriverProto.EV_OPEN_RESULT, result.op)
            assertFalse(result.payload.getBool())
            assertEquals(1, prior.disconnectCount.get())
            sockets.client.soTimeout = 150
            assertThrows(SocketTimeoutException::class.java) { wire.read() }
        } finally {
            session.close()
            sockets.client.close()
        }
    }

    @Test
    fun `physical endpoint aliases collapse to one ownership key`() {
        val civ = DriverProto.catOpenFlags(
            DriverProto.CAT_DIALECT_CIV,
            0x94,
            DriverProto.CAT_PROFILE_IC7300,
        )
        val kenwood = DriverProto.catOpenFlags(
            DriverProto.CAT_DIALECT_KENWOOD,
            0,
            DriverProto.CAT_PROFILE_TS890,
        )
        assertEquals(
            normalizedDeviceKey(DriverProto.DEV_CAT, "", 0, civ),
            normalizedDeviceKey(DriverProto.DEV_CAT, "usb", 115_200, civ),
        )
        assertEquals(
            normalizedDeviceKey(DriverProto.DEV_CAT, "localhost", 0, civ),
            normalizedDeviceKey(DriverProto.DEV_CAT, "127.0.0.1", 4_532, civ),
        )
        assertEquals(
            normalizedDeviceKey(
                DriverProto.DEV_CAT,
                "operator:secret@localhost",
                0,
                kenwood,
            ),
            normalizedDeviceKey(DriverProto.DEV_CAT, "127.0.0.1", 60_000, kenwood),
        )
        assertEquals(
            normalizedDeviceKey(
                DriverProto.DEV_HPSDR_P1,
                "",
                0,
                0,
                verifiedHost = "192.0.2.10",
            ),
            normalizedDeviceKey(
                DriverProto.DEV_HPSDR_P1,
                "192.0.2.10",
                1_024,
                0,
                verifiedHost = "192.0.2.10",
            ),
        )
        assertEquals(
            normalizedDeviceKey(DriverProto.DEV_G2, "", 0, 0),
            normalizedDeviceKey(DriverProto.DEV_G2, "255.255.255.255", 99, 0),
        )
        assertEquals(
            normalizedDeviceKey(DriverProto.DEV_HACKRF, "ignored", 7, 0),
            normalizedDeviceKey(DriverProto.DEV_HACKRF, "", 0, 0),
        )
        assertEquals(
            normalizedDeviceKey(DriverProto.DEV_RTL_TCP, "localhost", 1_234, 0),
            normalizedDeviceKey(DriverProto.DEV_RTL_TCP, "127.0.0.1", 1_234, 0),
        )
    }

    @Test
    fun `post-claim failure without client releases device ownership`() {
        val sockets = sessionSockets()
        val session = authenticatedSession(sockets.server)
        val host = "unsupported-${System.nanoTime()}"
        val key = "${DriverProto.DEV_FLEX}/$host:0"
        session.start()

        try {
            sockets.client.soTimeout = 1_000
            val wire = wire(sockets.client)
            wire.writeOpen(DriverProto.DEV_FLEX, host, 0, 0)

            val status = checkNotNull(wire.read())
            assertEquals(DriverProto.EV_STATUS, status.op)
            assertFalse(status.payload.getBool())
            assertTrue(status.payload.getUtf().contains("not available"))
            val result = checkNotNull(wire.read())
            assertEquals(DriverProto.EV_OPEN_RESULT, result.op)
            assertFalse(result.payload.getBool())
            assertNull(deviceOwners()[key])
        } finally {
            session.close()
            sockets.client.close()
        }
    }

    @Test(timeout = 3_000)
    fun `cancelled winning claim still closes previous owner before removing itself`() {
        val previousSockets = sessionSockets()
        val previousCloseEntered = CountDownLatch(1)
        val releasePreviousClose = CountDownLatch(1)
        val previous = authenticatedSession(previousSockets.server) {
            previousCloseEntered.countDown()
            releasePreviousClose.await(1, TimeUnit.SECONDS)
        }
        val key = "claim-race-${System.nanoTime()}"
        val previousEpoch = (gate(previous).begin() as OpenEpochGate.Begin.Started).epoch
        assertTrue(invokeClaim(previous, previousEpoch, key))

        val candidateSockets = sessionSockets()
        val candidate = authenticatedSession(candidateSockets.server)
        val candidateGate = gate(candidate)
        val candidateEpoch = (candidateGate.begin() as OpenEpochGate.Begin.Started).epoch
        var claimed: Boolean? = null
        val claimThread = thread {
            claimed = invokeClaim(candidate, candidateEpoch, key)
        }
        val closeDone = CountDownLatch(1)

        try {
            assertTrue(previousCloseEntered.await(1, TimeUnit.SECONDS))
            // The candidate won put(), then was cancelled while it was
            // completing mandatory teardown of the displaced owner.
            val closeThread = thread {
                invokeCloseDevice(candidate)
                closeDone.countDown()
            }
            assertFalse("CLOSE returned before the in-flight claim", closeDone.await(50, TimeUnit.MILLISECONDS))
            releasePreviousClose.countDown()
            claimThread.join(1_000)
            closeThread.join(1_000)

            assertEquals(false, claimed)
            assertTrue(closeDone.await(1, TimeUnit.SECONDS))
            assertNull(deviceOwners()[key])
        } finally {
            releasePreviousClose.countDown()
            previous.close()
            candidate.close()
            previousSockets.client.close()
            candidateSockets.client.close()
        }
    }

    @Test
    fun `queued callback frames are discarded after epoch handover`() {
        val sockets = sessionSockets()
        val session = authenticatedSession(sockets.server)
        val gate = gate(session)
        val a = publish(gate, "A")

        callback2<Boolean, String>(session, "statusFor", a)(true, "still A")
        callback2<FloatArray, FloatArray>(session, "dataFor", a)(
            FloatArray(0),
            floatArrayOf(0.25f, -0.25f),
        )
        callback1<Long>(session, "hackRfGapsFor", a)(7L)

        val b = (gate.begin() as OpenEpochGate.Begin.Started).epoch
        assertTrue(gate.handover(b).accepted)
        session.start()

        try {
            sockets.client.soTimeout = 150
            assertThrows(SocketTimeoutException::class.java) { wire(sockets.client).read() }
            val pool = DriverSession::class.java.getDeclaredField("bufPool").run {
                isAccessible = true
                get(session) as Collection<*>
            }
            assertTrue("stale IQ buffer was not recycled", pool.isNotEmpty())
        } finally {
            session.close()
            sockets.client.close()
        }
    }

    @Test
    fun `command result precedes synchronous state callback and frequency readback`() {
        val sockets = sessionSockets()
        val session = authenticatedSession(sockets.server)
        val gate = gate(session)
        val epoch = (gate.begin() as OpenEpochGate.Begin.Started).epoch
        val radio = object : RadioClient {
            var hz = 14_100_000L
            var listener: (() -> Unit)? = null
            override suspend fun connect() = true
            override fun disconnect() = Unit
            override fun setFrequency(hz: Long) {
                this.hz = hz
                listener?.invoke()
            }
            override fun frequencyHz() = hz
            override fun setSampleRate(hz: Int) = Unit
            override fun setStateListener(listener: (() -> Unit)?) { this.listener = listener }
            override var spectrumEnabled = false
        }
        assertTrue(session.installCandidate(epoch, radio, "test") {})
        gate.status(epoch, true, "Connected")
        assertEquals(OpenEpochGate.CommitStart.Ready, gate.startCommit(epoch))
        val publication = gate.finishCommit(epoch) as OpenEpochGate.CommitFinish.Published
        gate.release(publication.lease)
        setField(session, "radioControlReady", true)
        session.start()
        try {
            sockets.client.soTimeout = 1_000
            val wire = wire(sockets.client)
            wire.writeI64(DriverProto.CMD_SET_FREQUENCY, 14_250_000L)
            val result = checkNotNull(wire.read())
            assertEquals(DriverProto.EV_COMMAND_RESULT, result.op)
            assertEquals(DriverProto.CMD_SET_FREQUENCY, result.payload.get().toInt() and 255)
            assertEquals(DriverProto.COMMAND_ACCEPTED, result.payload.get().toInt() and 255)
            repeat(2) {
                val readback = checkNotNull(wire.read())
                assertEquals(DriverProto.EV_FREQUENCY, readback.op)
                assertEquals(14_250_000L, readback.payload.long)
            }
        } finally {
            session.close()
            sockets.client.close()
        }
    }

    @Test
    fun `late asynchronous query reply cannot reach replacement epoch`() {
        val sockets = sessionSockets()
        val session = authenticatedSession(sockets.server)
        val gate = gate(session)
        val a = publish(gate, "A")
        val b = (gate.begin() as OpenEpochGate.Begin.Started).epoch
        assertTrue(gate.handover(b).accepted)
        assertEquals(OpenEpochGate.CommitStart.Ready, gate.startCommit(b))
        val publication = gate.finishCommit(b) as OpenEpochGate.CommitFinish.Published
        gate.release(publication.lease)
        val leaked = AtomicBoolean(false)
        DriverSession::class.java.getDeclaredMethod(
            "enqueueReadback", OpenEpochGate.Epoch::class.java, Function0::class.java,
        ).run {
            isAccessible = true
            invoke(session, a, { leaked.set(true); Unit })
        }
        session.start()
        try {
            sockets.client.soTimeout = 150
            assertThrows(SocketTimeoutException::class.java) { wire(sockets.client).read() }
            assertFalse(leaked.get())
        } finally {
            session.close()
            sockets.client.close()
        }
    }

    private data class SessionSockets(val client: Socket, val server: Socket)

    private fun sessionSockets(): SessionSockets {
        val listener = ServerSocket(0)
        val client = Socket("127.0.0.1", listener.localPort)
        val server = listener.accept()
        listener.close()
        return SessionSockets(client, server)
    }

    private fun authenticatedSession(
        server: Socket,
        onClosed: (DriverSession) -> Unit = {},
    ): DriverSession = DriverSession(ContextWrapper(null), server, "test-token", onClosed).also {
        setField(it, "authenticated", true)
    }

    private fun gate(session: DriverSession): OpenEpochGate =
        DriverSession::class.java.getDeclaredField("openEpochs").run {
            isAccessible = true
            get(session) as OpenEpochGate
        }

    @Suppress("UNCHECKED_CAST")
    private fun <A> callback1(
        session: DriverSession,
        name: String,
        epoch: OpenEpochGate.Epoch,
    ): (A) -> Unit = DriverSession::class.java.getDeclaredMethod(
        name,
        OpenEpochGate.Epoch::class.java,
    ).run {
        isAccessible = true
        invoke(session, epoch) as (A) -> Unit
    }

    @Suppress("UNCHECKED_CAST")
    private fun <A, B> callback2(
        session: DriverSession,
        name: String,
        epoch: OpenEpochGate.Epoch,
    ): (A, B) -> Unit = DriverSession::class.java.getDeclaredMethod(
        name,
        OpenEpochGate.Epoch::class.java,
    ).run {
        isAccessible = true
        invoke(session, epoch) as (A, B) -> Unit
    }

    private fun invokeClaim(
        session: DriverSession,
        epoch: OpenEpochGate.Epoch,
        key: String,
    ): Boolean = DriverSession::class.java.getDeclaredMethod(
        "claimDevice",
        OpenEpochGate.Epoch::class.java,
        String::class.java,
    ).run {
        isAccessible = true
        invoke(session, epoch, key) as Boolean
    }

    private fun enqueueOpenSuccess(
        session: DriverSession,
        epoch: OpenEpochGate.Epoch,
        radio: RadioClient,
        fallbackStatus: String,
    ) {
        DriverSession::class.java.getDeclaredMethod(
            "enqueueOpenSuccess",
            OpenEpochGate.Epoch::class.java,
            RadioClient::class.java,
            String::class.java,
        ).run {
            isAccessible = true
            invoke(session, epoch, radio, fallbackStatus)
        }
    }

    private fun invokeDeliverStatus(
        session: DriverSession,
        epoch: OpenEpochGate.Epoch,
        connected: Boolean,
        status: String,
        lease: OpenEpochGate.Lease,
    ) {
        DriverSession::class.java.getDeclaredMethod(
            "deliverStatusLease",
            OpenEpochGate.Epoch::class.java,
            Boolean::class.javaPrimitiveType,
            String::class.java,
            OpenEpochGate.Lease::class.java,
        ).run {
            isAccessible = true
            invoke(session, epoch, connected, status, lease)
        }
    }

    private fun invokeCloseDevice(session: DriverSession) {
        DriverSession::class.java.getDeclaredMethod("closeDevice").run {
            isAccessible = true
            invoke(session)
        }
    }

    private fun invokeFailOpen(
        session: DriverSession,
        epoch: OpenEpochGate.Epoch,
        radio: RadioClient?,
        detail: String?,
    ) {
        DriverSession::class.java.getDeclaredMethod(
            "failOpen",
            OpenEpochGate.Epoch::class.java,
            RadioClient::class.java,
            String::class.java,
        ).run {
            isAccessible = true
            invoke(session, epoch, radio, detail)
        }
    }

    private fun invokeDetachRadio(session: DriverSession): RadioClient? =
        DriverSession::class.java.getDeclaredMethod("detachRadio").run {
            isAccessible = true
            invoke(session) as RadioClient?
        }

    private fun invokeTxWatchdogTick(session: DriverSession, now: Long): Boolean =
        DriverSession::class.java.getDeclaredMethod(
            "runTxWatchdogTick",
            Long::class.javaPrimitiveType,
        ).run {
            isAccessible = true
            invoke(session, now) as Boolean
        }

    private fun booleanField(session: DriverSession, name: String): Boolean =
        DriverSession::class.java.getDeclaredField(name).run {
            isAccessible = true
            getBoolean(session)
        }

    private fun stringField(session: DriverSession, name: String): String? =
        DriverSession::class.java.getDeclaredField(name).run {
            isAccessible = true
            get(session) as String?
        }

    private fun radioLock(session: DriverSession): Any =
        DriverSession::class.java.getDeclaredField("radioLock").run {
            isAccessible = true
            checkNotNull(get(session))
        }

    private fun statusDeliveryLock(session: DriverSession): Any =
        DriverSession::class.java.getDeclaredField("statusDeliveryLock").run {
            isAccessible = true
            checkNotNull(get(session))
        }

    private fun radioField(session: DriverSession): RadioClient? =
        DriverSession::class.java.getDeclaredField("radio").run {
            isAccessible = true
            get(session) as RadioClient?
        }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (!condition() && System.nanoTime() < deadline) Thread.yield()
        assertTrue(condition())
    }

    @Suppress("UNCHECKED_CAST")
    private fun deviceOwners(): ConcurrentHashMap<String, DriverSession> =
        DriverSession::class.java.getDeclaredField("deviceOwners").run {
            isAccessible = true
            get(null) as ConcurrentHashMap<String, DriverSession>
        }

    private fun wire(socket: Socket): Frames = Frames(
        DataInputStream(BufferedInputStream(socket.getInputStream())),
        DataOutputStream(BufferedOutputStream(socket.getOutputStream())),
    )

    private fun setField(target: Any, name: String, value: Any) {
        target.javaClass.getDeclaredField(name).apply {
            isAccessible = true
            when (value) {
                is Boolean -> setBoolean(target, value)
                else -> set(target, value)
            }
        }
    }

    private fun publish(gate: OpenEpochGate, detail: String): OpenEpochGate.Epoch {
        val epoch = (gate.begin() as OpenEpochGate.Begin.Started).epoch
        gate.status(epoch, true, detail)
        assertEquals(OpenEpochGate.CommitStart.Ready, gate.startCommit(epoch))
        val published = gate.finishCommit(epoch) as OpenEpochGate.CommitFinish.Published
        gate.release(published.lease)
        return epoch
    }
}
