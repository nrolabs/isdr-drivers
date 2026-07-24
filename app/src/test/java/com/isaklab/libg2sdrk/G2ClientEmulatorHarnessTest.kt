package com.isaklab.libg2sdrk

import java.io.File
import java.util.Collections
import kotlin.math.abs
import kotlin.math.atan2
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM bench harness: drives the REAL [G2Client] against the local Saturn
 * emulator (`tools/g2-emulator`, spec-complete P2) on high ports, and proves
 * the multi-DDC contract end to end:
 *
 *  - several DDCs streaming at once, each on its own source port;
 *  - per-DDC retune (the emulator's `--rf` carrier moves in/out of each
 *    DDC's passband as its NCO moves);
 *  - armed secondary streams delivered via onDataRx, PAIRED with the active
 *    stream's block (same flush, length-matched) — the diversity/PureSignal
 *    transport contract;
 *  - PureSignal: setPureSignal switches the reference DDC to the TX/DUC
 *    loopback; while keyed its onDataRx blocks carry the transmitted tone.
 *
 * Skips (does not fail) when python3 or the emulator script is missing.
 */
class G2ClientEmulatorHarnessTest {

    private val portOffset = 23100
    private var emulator: Process? = null

    private sealed class Ev {
        class Active(val iq: FloatArray) : Ev()
        class Rx(val rx: Int, val iq: FloatArray) : Ev()
    }

    private val events = Collections.synchronizedList(ArrayList<Ev>())
    private var client: G2Client? = null

    private fun emulatorScript(): File? = listOf(
        "../../tools/g2-emulator/g2_emulator.py",
        "../tools/g2-emulator/g2_emulator.py",
        "/home/admin/isdr/tools/g2-emulator/g2_emulator.py",
    ).map { File(it) }.firstOrNull { it.isFile }

    @Before
    fun startEmulator() {
        val script = emulatorScript()
        assumeTrue("g2_emulator.py not found — bench skipped", script != null)
        val proc = try {
            ProcessBuilder(
                "python3", script!!.absolutePath,
                "--port-offset", portOffset.toString(),
                "--rf", "7105000", "--quiet",
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
        Thread.sleep(200)
        emulator?.destroy()
        emulator?.waitFor()
    }

    /** Mean phase-derivative tone estimate over interleaved IQ, Hz. */
    private fun toneOf(blocks: List<FloatArray>, rateHz: Double): Double {
        var acc = 0.0
        var n = 0
        var pi = 0f
        var pq = 0f
        var havePrev = false
        for (b in blocks) {
            var k = 0
            while (k + 1 < b.size) {
                val i = b[k]; val q = b[k + 1]; k += 2
                if (havePrev && (pi != 0f || pq != 0f) && (i != 0f || q != 0f)) {
                    val re = i * pi + q * pq
                    val im = q * pi - i * pq
                    acc += atan2(im.toDouble(), re.toDouble()); n++
                }
                pi = i; pq = q; havePrev = true
            }
        }
        return if (n == 0) 0.0 else acc / n * rateHz / (2 * Math.PI)
    }

    private fun rms(blocks: List<FloatArray>): Double {
        var s = 0.0
        var n = 0
        for (b in blocks) { for (v in b) { s += v * v.toDouble(); n++ } }
        return if (n == 0) 0.0 else Math.sqrt(s / n)
    }

    private fun drainEvents(ms: Long): List<Ev> {
        events.clear()
        Thread.sleep(ms)
        synchronized(events) { return ArrayList(events) }
    }

    private fun rxBlocks(evs: List<Ev>, rx: Int): List<FloatArray> =
        evs.filterIsInstance<Ev.Rx>().filter { it.rx == rx }.map { it.iq }

    @Test
    fun multiDdcRetuneStreamMaskAndPureSignalPairing() = runBlocking {
        val c = G2Client(
            host = "127.0.0.1",
            onDataReceived = { _, iq -> events.add(Ev.Active(iq)) },
            onConnectionStatusChanged = { _, _ -> },
            onStatus = null,
            onDataRx = { rx, iq -> events.add(Ev.Rx(rx, iq)) },
            portOffset = portOffset,
        )
        client = c
        c.spectrumEnabled = false
        assertTrue("connect to emulator", c.connect())

        // --- 3 DDCs, distinct NCOs, secondary streams armed --------------
        c.setSampleRate(48_000)
        c.setReceiverCount(3)
        c.setRxFrequency(0, 7_100_000)     // carrier 7.105 MHz -> +5 kHz tone
        c.setRxFrequency(1, 7_103_000)     // -> +2 kHz tone
        c.setRxFrequency(2, 7_200_000)     // carrier out of band -> silence
        c.setRxStreamMask(0b110)
        Thread.sleep(400)                  // settle
        var evs = drainEvents(1200)
        val active = evs.filterIsInstance<Ev.Active>().map { it.iq }
        assertTrue("active DDC0 blocks flow (${active.size})", active.size >= 5)
        val rx1 = rxBlocks(evs, 1)
        val rx2 = rxBlocks(evs, 2)
        assertTrue("DDC1 armed stream flows (${rx1.size})", rx1.size >= 5)
        assertTrue("DDC2 armed stream flows (${rx2.size})", rx2.size >= 5)
        assertEquals("DDC0 tone", 5000.0, toneOf(active, 48_000.0), 150.0)
        assertEquals("DDC1 tone (own NCO)", 2000.0, toneOf(rx1, 48_000.0), 150.0)
        assertTrue("DDC2 out-of-band = silent (rms=${rms(rx2)})", rms(rx2) < 0.02)

        // --- per-DDC retune: DDC2's NCO moves onto the carrier -----------
        c.setRxFrequency(2, 7_104_000)     // -> +1 kHz tone
        Thread.sleep(300)
        evs = drainEvents(1000)
        val rx2b = rxBlocks(evs, 2)
        assertTrue("DDC2 blocks after retune", rx2b.size >= 5)
        assertEquals("DDC2 tone moved by its own NCO",
            1000.0, toneOf(rx2b, 48_000.0), 150.0)

        // --- flush pairing: every EV_DATA_RX length-matches the EV_DATA
        //     that follows it (diversity / PureSignal contract) -----------
        var paired = 0
        var broken = 0
        for ((idx, e) in evs.withIndex()) {
            if (e !is Ev.Rx) continue
            val next = evs.drop(idx + 1).firstOrNull { it is Ev.Active } as Ev.Active?
            if (next == null) continue
            if (next.iq.size == e.iq.size) paired++ else broken++
        }
        assertTrue("rx blocks pair with the active flush ($paired ok, $broken broken)",
            paired >= 10 && broken == 0)

        // --- PureSignal: reference DDC1 switches to the DUC loopback -----
        c.setRxStreamMask(0b010)           // stream the reference only
        c.setPureSignal(true)
        Thread.sleep(200)
        c.setPtt(true)
        // Feed a 3 kHz TX tone at 48 kSps from a keyer thread.
        val feeder = Thread {
            var ph = 0.0
            val w = 2 * Math.PI * 3000.0 / 48_000.0
            val block = FloatArray(4800 * 2)
            while (!Thread.currentThread().isInterrupted) {
                var o = 0
                while (o < block.size) {
                    block[o] = (0.8 * Math.cos(ph)).toFloat()
                    block[o + 1] = (0.8 * Math.sin(ph)).toFloat()
                    ph += w; o += 2
                }
                c.submitTxIq(block)
                try { Thread.sleep(100) } catch (e: InterruptedException) { return@Thread }
            }
        }.also { it.start() }
        Thread.sleep(600)                  // let MOX + loopback engage
        evs = drainEvents(1200)
        feeder.interrupt(); feeder.join()
        c.setPtt(false)
        val ref = rxBlocks(evs, 1)
        assertTrue("PS reference (DDC1) blocks while keyed (${ref.size})", ref.size >= 5)
        val psTone = toneOf(ref.drop(2), 48_000.0)
        assertTrue("PS reference carries the TX tone (est $psTone Hz)",
            abs(psTone - 3000.0) < 150.0)
        // Pairs still coherent under MOX: each reference block matched an
        // active flush (the app feeds pureSignal.feed(ref, active)).
        var psPairs = 0
        for ((idx, e) in evs.withIndex()) {
            if (e !is Ev.Rx || e.rx != 1) continue
            val next = evs.drop(idx + 1).firstOrNull { it is Ev.Active } as Ev.Active?
            if (next != null && next.iq.size == e.iq.size) psPairs++
        }
        assertTrue("PS pairs collected while keyed ($psPairs)", psPairs >= 5)
    }
}
