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

import java.io.File
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Per-receiver stream arming on the REAL [Hl2Client], driven by the local
 * Hermes-Lite 2 emulator.
 *
 * A HL2 frame carries every receiver interleaved, so the second receiver's
 * samples and the active one's are decoded from the SAME frames and flushed
 * together: each `onDataRx` block covers the same span as the `onDataReceived`
 * block that follows it. That equality is the whole transport contract for
 * diversity and PureSignal, and it is what breaks if a receiver's accumulator
 * is allowed to keep samples across a disarm.
 *
 * The cycle exercised here is the one that broke it: arm receiver 1, let it
 * accumulate a partial block, clear the mask (the decoder stops keeping its
 * samples, but the partial block is still sitting there), then re-arm. If the
 * stale pairs survive the disarm they come out glued to the front of the first
 * block after the re-arm — one block LONGER than the active block it is
 * supposed to match, and every block after it is offset by that much for the
 * rest of the session.
 *
 * Skips (does not fail) when python3 or the emulator script is missing.
 */
class Hl2RxStreamMaskTest {

    private val port = 21124
    private var emulator: Process? = null
    private var client: Hl2Client? = null

    private sealed class Ev {
        class Active(val pairs: Int) : Ev()
        class Rx(val rx: Int, val pairs: Int, val trailingSilentPairs: Int) : Ev()
    }

    /**
     * Zero pairs at the END of a block. The emulator's receiver 1 carries a
     * continuous tone, so an exactly-zero I AND Q pair is padding, never
     * signal — and padding belongs at the FRONT: a receiver armed part-way
     * through a block owns the block's LAST samples, so putting them anywhere
     * but the end shifts the stream in time against the active block it is
     * paired with.
     */
    private fun trailingSilence(iq: FloatArray): Int {
        var n = 0
        var i = iq.size - 2
        while (i >= 0 && iq[i] == 0f && iq[i + 1] == 0f) { n++; i -= 2 }
        return n
    }

    private val events = Collections.synchronizedList(ArrayList<Ev>())

    private fun emulatorScript(): File? = listOf(
        "../../tools/hl2-emulator/hl2_emulator.py",
        "../tools/hl2-emulator/hl2_emulator.py",
        "/home/admin/isdr/tools/hl2-emulator/hl2_emulator.py",
    ).map { File(it) }.firstOrNull { it.isFile }

    @Before
    fun startEmulator() {
        val script = emulatorScript()
        assumeTrue("hl2_emulator.py not found — bench skipped", script != null)
        val proc = try {
            ProcessBuilder(
                "python3", script!!.absolutePath,
                "--port", port.toString(), "--diversity", "--quiet",
            ).redirectErrorStream(true).redirectOutput(File("/dev/null")).start()
        } catch (e: Exception) {
            null
        }
        assumeTrue("python3 unavailable — bench skipped", proc != null)
        emulator = proc
        Thread.sleep(600)
        assumeTrue("emulator died at startup", proc!!.isAlive)
    }

    @After
    fun stop() {
        client?.disconnect()
        Thread.sleep(300)
        emulator?.destroy()
        emulator?.waitFor()
    }

    private fun drain(ms: Long): List<Ev> {
        events.clear()
        Thread.sleep(ms)
        synchronized(events) { return ArrayList(events) }
    }

    /**
     * Every armed-stream block, paired with the active block of its own flush
     * (which flushRx emits right after it), must carry exactly that block's
     * span. Returns the mismatches, described.
     */
    private fun pairingFaults(evs: List<Ev>): List<String> {
        val faults = ArrayList<String>()
        for ((idx, e) in evs.withIndex()) {
            if (e !is Ev.Rx) continue
            val next = evs.drop(idx + 1).firstOrNull { it is Ev.Active } as Ev.Active? ?: continue
            if (next.pairs != e.pairs) {
                faults.add("rx${e.rx} block of ${e.pairs} pairs against an active block of ${next.pairs}")
            }
            if (e.trailingSilentPairs > 4) {
                faults.add(
                    "rx${e.rx} block padded at the END (${e.trailingSilentPairs} silent pairs): " +
                        "its samples are the tail of the span, not the head",
                )
            }
        }
        return faults
    }

    @Test
    fun disarmingAStreamDoesNotLeaveSamplesBehindForTheNextArming() = runBlocking {
        val c = Hl2Client(
            host = "127.0.0.1",
            onDataReceived = { _, iq -> events.add(Ev.Active(iq.size / 2)) },
            onConnectionStatusChanged = { _, _ -> },
            onDataRx = { rx, iq -> events.add(Ev.Rx(rx, iq.size / 2, trailingSilence(iq))) },
            port = port,
        )
        client = c
        c.spectrumEnabled = false
        assertTrue("connect to emulator", c.connect())
        c.setSampleRate(48_000)
        c.setReceiverCount(2)
        Thread.sleep(400)

        // Armed continuously: the baseline the contract describes.
        c.setRxStreamMask(0b10)
        Thread.sleep(400)
        var evs = drain(1200)
        assertTrue("active blocks flow (${evs.count { it is Ev.Active }})",
            evs.count { it is Ev.Active } >= 5)
        assertTrue("armed stream flows (${evs.count { it is Ev.Rx }})",
            evs.count { it is Ev.Rx } >= 5)
        assertEquals("armed stream must pair with the active flush",
            emptyList<String>(), pairingFaults(evs))

        // Disarm mid-block, stay off long enough for several flushes, re-arm.
        // The gap between the clear and the re-arm is deliberately NOT a whole
        // number of blocks — a leftover only exists when the disarm lands
        // inside one.
        // A leftover is a ONE-BLOCK signature: the first flush after the
        // re-arm is long by whatever was left, and from the flush after that
        // the lengths match again (both accumulators were just zeroed) even
        // though the stream is now permanently offset in TIME by that much.
        // So the recording has to be running ACROSS the re-arm — collecting
        // after it settles is exactly how the defect slips through.
        repeat(6) { round ->
            c.setRxStreamMask(0)
            Thread.sleep(120 + round * 7L)
            events.clear()
            c.setRxStreamMask(0b10)
            Thread.sleep(900)
            evs = synchronized(events) { ArrayList(events) }
            val rx = evs.count { it is Ev.Rx }
            assertTrue("round $round: stream did not resume ($rx blocks)", rx >= 3)
            assertEquals(
                "round $round: the re-armed stream carried samples from before the disarm",
                emptyList<String>(), pairingFaults(evs),
            )
        }
    }
}
