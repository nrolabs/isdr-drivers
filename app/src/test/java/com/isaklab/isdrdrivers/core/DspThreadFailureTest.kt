/*
 * isdr-drivers - hardware drivers for the iSDR application
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

package com.isaklab.isdrdrivers.core

import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catch-all in [DspThread] keeps an unhandled throwable on a loop thread
 * from killing the Android process. That is only half a fix: a loop that dies
 * quietly, with the driver's `isConnected`/`running` flags still set, leaves
 * the host showing a live radio that will never deliver another sample. The
 * [DspThread.start] `onFailure` hook is what turns the silent death into a
 * retired session, so this file pins BOTH halves:
 *
 *  - behavioural: the hook runs, gets the same throwable, and a hook that
 *    throws in turn cannot take the process down either;
 *  - structural: every loop the drivers start supplies the hook. A loop
 *    started without one is exactly the zombie above, and no runtime test can
 *    see the one somebody forgets to write.
 */
class DspThreadFailureTest {

    @Test
    fun failureHookReceivesTheThrowableThatKilledTheLoop() {
        val seen = Collections.synchronizedList(ArrayList<Throwable>())
        val done = CountDownLatch(1)
        val boom = IllegalStateException("loop died")
        val t = DspThread.start(
            "test-loop",
            DspThread.PRIORITY_DELIVERY,
            onFailure = { seen.add(it); done.countDown() },
        ) { throw boom }

        assertTrue("failure hook never ran", done.await(3, TimeUnit.SECONDS))
        t.join(1000)
        assertEquals(listOf<Throwable>(boom), seen)
    }

    /**
     * A throwing hook is the last line of the chain: there is nobody above it
     * to tell, so it must be logged and dropped rather than rethrown onto the
     * default handler (which on Android is process death).
     */
    @Test
    fun aFailingFailureHookStillLetsTheThreadUnwind() {
        val escaped = Collections.synchronizedList(ArrayList<Throwable>())
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, t -> escaped.add(t) }
        try {
            val t = DspThread.start(
                "test-loop-bad-hook",
                DspThread.PRIORITY_DELIVERY,
                onFailure = { throw RuntimeException("handler blew up too") },
            ) { throw IllegalStateException("loop died") }
            t.join(3000)
            assertTrue("the loop thread never finished", !t.isAlive)
            assertTrue("a throwable escaped to the default handler: $escaped", escaped.isEmpty())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    /**
     * Structural gate over the driver sources: every `DspThread.start(` and
     * `DspThread.create(` must name `onFailure`.
     *
     * This is a source scan, not an execution: it proves the call sites are
     * wired, not that each driver's own failure path is correct (the client
     * tests do that). It exists because the failure mode it guards — a new
     * loop added without a hook — is invisible until a radio dies in the
     * field, and by then the symptom is a frozen display with no error.
     */
    @Test
    fun everyDriverLoopSuppliesAFailureHook() {
        val roots = listOf(File("src/main/java"), File("app/src/main/java"))
        val src = roots.firstOrNull { it.isDirectory }
        assertTrue("driver sources not found from ${File(".").absolutePath}", src != null)

        val offenders = ArrayList<String>()
        src!!.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            val text = f.readText()
            Regex("DspThread\\.(start|create)\\(([^{]*)\\{").findAll(text).forEach { m ->
                if (!m.groupValues[2].contains("onFailure")) {
                    val line = text.substring(0, m.range.first).count { it == '\n' } + 1
                    offenders.add("${f.path}:$line")
                }
            }
        }
        assertEquals(
            "loop threads started without an onFailure hook (a death there is a " +
                "silent zombie loop: thread gone, connection still reported live)",
            emptyList<String>(),
            offenders,
        )
    }
}
