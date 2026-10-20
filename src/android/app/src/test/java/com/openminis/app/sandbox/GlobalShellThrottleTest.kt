package com.openminis.app.sandbox

import com.openminis.app.ProductionSources
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-global-shell-throttle] + [T-android-shell-process-budget]
 * Agent commands are admitted while the app's live child-process count stays
 * within PROCESS_BUDGET; the rest queue without losing their own timeout, a
 * start failure re-queues, and a queued command's wait is announced.
 *
 * Ported from upstream `GlobalShellThrottleTest`. Three upstream cases are
 * NOT here, on purpose: they assert on `ExecutionCoordinator.isStartFailure`,
 * `ExecutionCoordinator.queueNote` and a ChatViewModel tail that concatenates
 * the note. Minis-Linux has no fresh/warm strategy pair and no queue note —
 * its failure to start throws from the shell layer instead of returning a
 * sentinel `CommandResult`, so there is nothing to classify and no note to
 * append. The gate's own contract (what this file covers) is unchanged.
 */
class GlobalShellThrottleTest {

    /** Models /proc: running commands add to [live]; [baseline] is anything else. */
    private val live = AtomicInteger(0)
    private val baseline = AtomicInteger(0)

    private fun TestScope.useFakes() {
        GlobalShellThrottle.clock = { testScheduler.currentTime }
        GlobalShellThrottle.processCounter = { baseline.get() + live.get() }
    }

    @After
    fun restore() {
        GlobalShellThrottle.clock = { System.nanoTime() / 1_000_000 }
        GlobalShellThrottle.processCounter = {
            GlobalShellThrottle.countDescendants(File("/proc"), android.os.Process.myPid())
        }
    }

    private fun assertIdle() {
        assertEquals("no admission may leak between tests", 0, GlobalShellThrottle.runningCount)
        assertEquals(0, GlobalShellThrottle.waitingCount)
    }

    @Test
    fun `a burst of 4-process commands stays within the budget, and all of them run`() = runTest {
        useFakes(); assertIdle()
        val peak = AtomicInteger(0)
        val results = (1..12).map { i ->
            async {
                GlobalShellThrottle.run("s$i") {
                    live.addAndGet(4)
                    peak.accumulateAndGet(live.get()) { a, b -> maxOf(a, b) }
                    delay(5_000)
                    live.addAndGet(-4)
                    i
                }
            }
        }.awaitAll()
        assertTrue("peak ${peak.get()}", peak.get() <= GlobalShellThrottle.PROCESS_BUDGET)
        assertEquals("24 / 4 = 6 at once", 24, peak.get())
        val ran = results.map { it as GlobalShellThrottle.Outcome.Ran }
        assertEquals((1..12).toList(), ran.map { it.value })
        assertEquals("the first six start at once", 6, ran.count { !it.queued })
        assertTrue(ran.filter { it.queued }.all { it.queuedMs > 0 })
        assertIdle()
    }

    @Test
    fun `processes the app already runs count - a leftover daemon takes budget`() = runTest {
        useFakes(); assertIdle()
        baseline.set(10)
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)
        (1..4).map {
            async {
                GlobalShellThrottle.run("d$it") {
                    live.addAndGet(3)
                    peak.accumulateAndGet(active.incrementAndGet()) { a, b -> maxOf(a, b) }
                    delay(1_000); active.decrementAndGet(); live.addAndGet(-3)
                }
            }
        }.awaitAll()
        // 10 outside, then 3 per command: 13 + 1 + 4, 16 + 2 + 4, and at 19
        // the fourth one's 3 of unseen growth leaves 19 + 3 + 4 = 26 > 24.
        // Three at a time is the "leftover daemons slow commands down" case,
        // not the stall the next test covers.
        assertEquals(3, peak.get())
        baseline.set(0)
        assertIdle()
    }

    @Test
    fun `with nothing of ours running a command is always admitted, however many processes exist`() = runTest {
        useFakes(); assertIdle()
        baseline.set(40)
        val outcome = GlobalShellThrottle.run("alone") { 7 }
        assertEquals(GlobalShellThrottle.Outcome.Ran(7, 0L, false, 0), outcome)
        baseline.set(0)
        assertIdle()
    }

    @Test
    fun `a command that has not spawned yet is still charged, so a burst cannot overshoot`() = runTest {
        useFakes(); assertIdle()
        // Nothing ever shows up in /proc: without the reservation all ten
        // would be admitted at once.
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)
        (1..10).map {
            async {
                GlobalShellThrottle.run("r$it") {
                    peak.accumulateAndGet(active.incrementAndGet()) { a, b -> maxOf(a, b) }
                    delay(500); active.decrementAndGet()
                }
            }
        }.awaitAll()
        assertEquals("24 / 4 reserved each", 6, peak.get())
        assertIdle()
    }

    @Test
    fun `queue time does not come out of the command's timeout`() = runTest {
        useFakes(); assertIdle()
        val release = CompletableDeferred<Unit>()
        val holders = (1..6).map { launch { GlobalShellThrottle.run("hold$it") { live.addAndGet(4); release.await(); live.addAndGet(-4) } } }
        testScheduler.advanceTimeBy(100)
        assertEquals(6, GlobalShellThrottle.runningCount)
        // The late command's own run is timed inside block; queueing is not.
        val late = async { GlobalShellThrottle.run("late") { testScheduler.currentTime } }
        testScheduler.advanceTimeBy(30_000)
        release.complete(Unit)
        holders.forEach { it.join() }
        val ran = late.await() as GlobalShellThrottle.Outcome.Ran
        assertTrue(ran.queued)
        assertTrue("queued ~30s: ${ran.queuedMs}", ran.queuedMs >= 30_000)
        assertIdle()
    }

    @Test
    fun `the queue has a safety cap, and a command that hits it runs nothing`() = runTest {
        useFakes(); assertIdle()
        val release = CompletableDeferred<Unit>()
        val holders = (1..6).map { launch { GlobalShellThrottle.run("hold$it") { live.addAndGet(4); release.await(); live.addAndGet(-4) } } }
        testScheduler.advanceTimeBy(100)
        var ran = false
        val outcome = GlobalShellThrottle.run("late", maxQueueWaitMs = 5_000) { ran = true }
        assertTrue(outcome is GlobalShellThrottle.Outcome.QueueTimedOut)
        assertEquals(24, (outcome as GlobalShellThrottle.Outcome.QueueTimedOut).lastLiveCount)
        assertFalse("nothing executed", ran)
        release.complete(Unit)
        holders.forEach { it.join() }
        assertIdle()
    }

    @Test
    fun `a start failure re-queues and retries, and the retry is reported`() = runTest {
        useFakes(); assertIdle()
        val attempts = AtomicInteger(0)
        val outcome = GlobalShellThrottle.run("flaky", isStartFailure = { it < 0 }) {
            if (attempts.incrementAndGet() < 3) -1 else 0
        }
        val ran = outcome as GlobalShellThrottle.Outcome.Ran
        assertEquals(0, ran.value)
        assertEquals(2, ran.startRetries)
        assertTrue(ran.queued)
        assertTrue("backed off 1s + 2s: ${ran.queuedMs}", ran.queuedMs >= 3_000)
        assertIdle()
    }

    @Test
    fun `a start that never succeeds gives up within the cap`() = runTest {
        useFakes(); assertIdle()
        val outcome = GlobalShellThrottle.run("dead", maxQueueWaitMs = 20_000, isStartFailure = { true }) { Unit }
        assertTrue(outcome is GlobalShellThrottle.Outcome.QueueTimedOut)
        assertTrue(testScheduler.currentTime <= 20_000)
        assertIdle()
    }

    @Test
    fun `a failure after the command started is returned, never retried`() = runTest {
        useFakes(); assertIdle()
        val attempts = AtomicInteger(0)
        val outcome = GlobalShellThrottle.run("killed", isStartFailure = { false }) { attempts.incrementAndGet(); 137 }
        assertEquals(1, attempts.get())
        assertEquals(137, (outcome as GlobalShellThrottle.Outcome.Ran).value)
        assertIdle()
    }

    @Test
    fun `cancelling a queued command (Stop) leaks nothing`() = runTest {
        useFakes(); assertIdle()
        val release = CompletableDeferred<Unit>()
        val holders = (1..6).map { launch { GlobalShellThrottle.run("hold$it") { live.addAndGet(4); release.await(); live.addAndGet(-4) } } }
        testScheduler.advanceTimeBy(100)
        val queued = launch { GlobalShellThrottle.run("queued") { error("must not run") } }
        testScheduler.advanceTimeBy(1_000)
        assertEquals(1, GlobalShellThrottle.waitingCount)
        queued.cancel()
        queued.join()
        release.complete(Unit)
        holders.forEach { it.join() }
        assertIdle()
        assertEquals(GlobalShellThrottle.Outcome.Ran(1, 0L, false, 0), GlobalShellThrottle.run("after") { 1 })
    }

    @Test
    fun `a command that throws still releases its admission`() = runTest {
        useFakes(); assertIdle()
        runCatching { GlobalShellThrottle.run("boom") { error("boom") } }
        assertIdle()
    }

    @Test
    fun `the wait is announced once, with the live count, and only when it queues`() = runTest {
        useFakes(); assertIdle()
        val release = CompletableDeferred<Unit>()
        val holders = (1..6).map { launch { GlobalShellThrottle.run("hold$it") { live.addAndGet(4); release.await(); live.addAndGet(-4) } } }
        testScheduler.advanceTimeBy(100)
        val seen = mutableListOf<Int>()
        val late = async { GlobalShellThrottle.run("late", onWaiting = { seen.add(it) }) { 1 } }
        testScheduler.advanceTimeBy(5_000)
        assertEquals("one notice per command, at the first queue", listOf(24), seen)
        release.complete(Unit)
        holders.forEach { it.join() }
        late.await()
        assertIdle()
        // An admitted-at-once command never announces.
        val quiet = mutableListOf<Int>()
        GlobalShellThrottle.run("quiet", onWaiting = { quiet.add(it) }) { 1 }
        assertTrue(quiet.isEmpty())
    }

    @Test
    fun `descendants of the app are counted from proc, and nothing else`() {
        val proc = kotlin.io.path.createTempDirectory("proc").toFile()
        fun p(pid: Int, ppid: Int, comm: String = "x") =
            File(proc, "$pid").apply { mkdirs(); File(this, "stat").writeText("$pid ($comm) S $ppid 1 1 0 -1\n") }
        p(100, 1)                       // the app
        p(200, 100, "libproot.so")      // a command...
        p(201, 200, "sh")
        p(202, 201, "sleep (1) x")      // comm with spaces and parens
        p(300, 100, "libproot.so")      // a leftover daemon
        p(400, 1, "other app")          // not ours
        p(401, 400)
        File(proc, "self").mkdirs()     // non-numeric entries are skipped
        File(proc, "500").mkdirs()      // unreadable stat: skipped
        assertEquals(4, GlobalShellThrottle.countDescendants(proc, 100))
        proc.deleteRecursively()
    }

    @Test
    fun `the parent pid is read after the last parenthesis`() {
        assertEquals(7, GlobalShellThrottle.parseParentPid("12 (a) b) c) S 7 12 12"))
        assertNull(GlobalShellThrottle.parseParentPid("garbage"))
    }

    @Test
    fun `an invalid root pid counts nothing instead of the whole device`() {
        val proc = kotlin.io.path.createTempDirectory("proc").toFile()
        File(proc, "1").apply { mkdirs(); File(this, "stat").writeText("1 (init) S 0 1 1\n") }
        File(proc, "2").apply { mkdirs(); File(this, "stat").writeText("2 (app) S 1 1 1\n") }
        // Process.myPid() is 0 under a stub android.jar; walking pid 0 reaches
        // every top-level process and reported 154, which queued all commands.
        assertEquals(0, GlobalShellThrottle.countDescendants(proc, 0))
        assertEquals(0, GlobalShellThrottle.countDescendants(proc, -1))
        assertEquals(2, GlobalShellThrottle.countDescendants(proc, 1))
        proc.deleteRecursively()
    }

    @Test
    fun `the log tag carries the program and the length, never the arguments`() {
        assertEquals("git(30)", GlobalShellThrottle.commandTag("git commit -m 'secret token x'"))
        assertEquals("shell", GlobalShellThrottle.commandTag("   "))
        assertFalse(GlobalShellThrottle.commandTag("curl -H 'Authorization: Bearer abc'").contains("abc"))
    }

    // ── the gate is wired into the one path every command takes ──────────

    @Test
    fun `every command is admitted through the process budget, light ones too`() {
        val src = ProductionSources.read("sandbox/SandboxResourceGate.kt")
        val body = src.substringAfter("suspend fun <T> withCommandLock(")
            .substringBefore("private suspend fun <T> withProcessBudget(")
        // Light commands used to `return block()` straight away — that IS the
        // gap: they never met any app-wide budget.
        assertFalse(
            "light commands must not bypass the process gate",
            body.contains("if (!heavy) return block()"),
        )
        assertTrue("light path goes through the budget", body.contains("if (!heavy) return budgeted()"))
        assertTrue(
            "the heavy path wraps its block in the same budget",
            body.contains("block = { budgeted() }"),
        )
        // Innermost by construction: the body handed to the budget IS block().
        val budget = src.substringAfter("private suspend fun <T> withProcessBudget(")
            .substringBefore("private suspend fun <T> withAptLock(")
        assertTrue(budget.contains("GlobalShellThrottle.run("))
        assertTrue("a command that never got room must not run", budget.contains("throw IllegalStateException("))
        assertTrue(
            "the budget must not be re-derived from the command text",
            budget.contains("GlobalShellThrottle.commandTag(command)") &&
                !budget.contains("PROCESS_BUDGET ="),
        )
    }
}
