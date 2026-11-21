package com.openminis.app.sandbox.jobs

import com.openminis.app.tools.JobTools
import com.openminis.app.tools.ToolErrorCode
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Pure-JVM tests for [JobRegistry] (and the [JobTools] mapping) with fake
 * spawner/processes: a real thread plays the guest process, a latch decides
 * when it may exit, and [JobRegistry.SpawnedProcess.pollExitCode] exercises
 * the lazy-reconciliation path.
 */
class JobRegistryTest {

    private lateinit var tmp: File

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("minis-jobs-test").toFile()
        JobRegistry.resetForTest()
    }

    @After
    fun tearDown() {
        JobRegistry.resetForTest()
        tmp.deleteRecursively()
    }

    // ------------------------------------------------------------- helpers

    /**
     * A fake detached process: a daemon thread waits on a latch (when
     * [hold]), then "exits" with [exitCode] — or 137 when it was killed.
     *
     * Test knobs for the host-side control plane ([T-job-control-bypass-mutex])
     * and the shell-lost reconcile ([T-job-shell-lost-reconcile]): see the
     * @Volatile vars below — all default to the original latch behaviour so
     * the older cases are untouched.
     */
    private class FakeProcess(
        override val stdoutFile: File,
        private val exitCode: Int,
        private val hold: Boolean,
        override val outputGuestPath: String? = null,
        override val pid: Int = -1,
    ) : JobRegistry.SpawnedProcess {
        private val release = CountDownLatch(1)
        private val finished = CountDownLatch(1)

        @Volatile private var killed = false
        @Volatile private var done = false
        @Volatile private var code = exitCode

        /** Non-null: await() returns it AT ONCE while the job is still "running" (shell-lost sentinel). */
        @Volatile var awaitResult: Int? = null

        /**
         * [T-job-watcher-nonblocking] Non-null: awaitSuspend() returns this
         * instead of bridging to the blocking [await] — lets a test prove the
         * registry watcher rides the suspending path.
         */
        @Volatile var overrideAwaitSuspend: (suspend () -> Int)? = null

        /** Host tree probe verdict; null = probe unavailable (the default). */
        @Volatile var hostAlive: Boolean? = null

        /** Forced kill verdict; null = the original latch-driven behaviour. */
        @Volatile var killResult: Boolean? = null

        /** Host-side signal verdict; null = host signalling unsupported (falls back to the guest path). */
        @Volatile var hostSignalResult: Boolean? = null

        /** Numbers the host-side signal path received, in order. */
        val hostSignals = java.util.Collections.synchronizedList(mutableListOf<Int>())

        /** stdin seams for the host-side input path (regular-file stand-ins). */
        override var stdinHostFile: File? = null
        override var stdinGuestPath: String? = null

        init {
            Thread({
                if (hold) release.await()
                if (killed) code = 137
                done = true
                finished.countDown()
            }, "fake-job").apply { isDaemon = true }.start()
        }

        override fun await(): Int {
            // [T-job-shell-lost-reconcile] Poll awaitResult instead of a
            // one-shot check: the watcher may call await BEFORE the test arms
            // the knob, and a one-shot check races the arm. The latch wait is
            // SLICED (200ms) and the loop re-checks awaitResult FIRST on every
            // wake — a sidecar landing mid-wait must not preempt the
            // shell-lost verdict (the reconcile adopts the real exit after
            // markShellLost). The 5s deadline preserves the old timeout.
            val deadline = System.currentTimeMillis() + 5_000
            while (true) {
                awaitResult?.let { return it }
                if (done) return code
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) return code
                finished.await(minOf(remaining, 200), TimeUnit.MILLISECONDS)
            }
        }

        override suspend fun awaitSuspend(): Int {
            overrideAwaitSuspend?.let { return it() }
            return super.awaitSuspend()
        }

        override fun kill(): Boolean {
            killResult?.let { return it }
            if (done) return false
            killed = true
            release.countDown()
            return true
        }

        override fun signalHost(signalNumber: Int): Boolean? =
            hostSignalResult?.also { hostSignals.add(signalNumber) }

        override fun hostTreeAlive(): Boolean? = hostAlive

        override val pollExitCode: Int? get() = if (done) code else null

        val wasKilled: Boolean get() = killed

        fun finishNow() {
            release.countDown()
        }
    }

    /** Spawner that logs every process it started; one log file per job. */
    private class FakeSpawner(
        private val dir: File,
        private val exitCode: Int = 0,
        private val hold: Boolean = true,
        private val createFile: Boolean = true,
        /** Pre-armed on every process: await() returns this at once (see [FakeProcess.awaitResult]). */
        private val forceAwaitResult: Int? = null,
        /** Pre-armed on every process: awaitSuspend() override (see [FakeProcess.overrideAwaitSuspend]). */
        private val awaitSuspendResult: (suspend () -> Int)? = null,
    ) {
        private val counter = java.util.concurrent.atomic.AtomicLong()
        private val fileCounter = java.util.concurrent.atomic.AtomicLong()
        val processes = mutableListOf<FakeProcess>()
        val guestPath = "/var/minis/workspace/tool-spill/jobs/fake.log"

        val fn: (String, String, String?) -> JobRegistry.SpawnedProcess = { _, _, _ ->
            val p = FakeProcess(newOutputFile(), exitCode, hold, guestPath, 41000 + counter.incrementAndGet().toInt())
            forceAwaitResult?.let { p.awaitResult = it }
            awaitSuspendResult?.let { p.overrideAwaitSuspend = it }
            synchronized(processes) { processes.add(p) }
            p
        }

        private fun newOutputFile(): File {
            val f = File(dir, "job-${fileCounter.incrementAndGet()}.log")
            if (createFile) f.createNewFile()
            return f
        }
    }

    private fun install(spawner: FakeSpawner) {
        JobRegistry.install(spawner.fn)
    }

    private fun startJob(sessionId: String, command: String = "make all", workdir: String? = null): JobRegistry.Job =
        when (val outcome = JobRegistry.start(sessionId, command, workdir)) {
            is JobRegistry.StartOutcome.Started -> outcome.job
            else -> throw AssertionError("expected Started, got $outcome")
        }

    private fun jobIn(sessionId: String, jobId: String): JobRegistry.Job? =
        JobRegistry.list(sessionId).firstOrNull { it.id == jobId }

    private fun awaitState(
        sessionId: String,
        jobId: String,
        expected: JobRegistry.JobState,
        timeoutMs: Long = 5_000,
    ): JobRegistry.Job {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            jobIn(sessionId, jobId)?.let { if (it.state == expected) return it }
            Thread.sleep(20)
        }
        throw AssertionError("job $jobId did not reach $expected within ${timeoutMs}ms")
    }

    /**
     * [T-job-shell-lost-reconcile] Poll until the watcher has entered the
     * non-terminal shell-lost mode for [jobId] (RUNNING + a shellLost stamp).
     */
    private fun awaitShellLost(
        sessionId: String,
        jobId: String,
        timeoutMs: Long = 5_000,
    ): JobRegistry.Job {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            jobIn(sessionId, jobId)?.let { if (it.shellLostAtMs != null) return it }
            Thread.sleep(20)
        }
        throw AssertionError("job $jobId did not enter the shell-lost mode within ${timeoutMs}ms")
    }

    // ------------------------------------------------------ registry cases

    @Test
    fun start_withoutSpawner_isRejectedWithStructuredSupport() = kotlinx.coroutines.runBlocking {
        val outcome = JobRegistry.start("s1", "make all", null)
        assertTrue(outcome is JobRegistry.StartOutcome.SpawnerMissing)
        // Tool mapping: structured UNSUPPORTED error, success=false.
        val result = JobTools.execute(
            """{"action":"start","command":"make all"}""",
            "s1",
            context = null,
        )
        assertFalse(result.success)
        assertEquals(ToolErrorCode.UNSUPPORTED, result.errorCode)
    }

    @Test
    fun start_thenListShowsRunningJob() {
        val sp = FakeSpawner(tmp)
        install(sp)
        val job = startJob("s1", command = "cargo build --release", workdir = "/var/minis/workspace/proj")
        assertTrue(job.id.startsWith("job_"))
        assertEquals(JobRegistry.JobState.RUNNING, job.state)
        assertEquals(null, job.exitCode)
        val listed = jobIn("s1", job.id)
        assertNotNull(listed)
        assertEquals("cargo build --release", listed!!.command)
        assertEquals("/var/minis/workspace/proj", listed.workingDir)
        assertEquals(JobRegistry.JobState.RUNNING, listed.state)
        assertEquals(sp.guestPath, listed.outputSpillPath)
    }

    @Test
    fun output_readsTailLines_andMarksTruncation() {
        val sp = FakeSpawner(tmp)
        install(sp)
        val job = startJob("s1")
        val file = sp.processes[0].stdoutFile
        file.writeText((1..10).joinToString("") { "line$it\n" })
        val three = JobRegistry.output("s1", job.id, 3)
        assertTrue(three is JobRegistry.OutputOutcome.Found)
        three as JobRegistry.OutputOutcome.Found
        // 3-of-10 lines: content before the tail was dropped -> truncated.
        assertTrue(three.truncated)
        assertEquals("line8\nline9\nline10", three.tail)
        val two = JobRegistry.output("s1", job.id, 2)
        assertTrue(two is JobRegistry.OutputOutcome.Found)
        two as JobRegistry.OutputOutcome.Found
        assertTrue(two.truncated)
        assertEquals("line9\nline10", two.tail)
    }

    @Test
    fun output_whenLogFileMissing_reportsNoOutputYet() {
        val sp = FakeSpawner(tmp, createFile = false)
        install(sp)
        val job = startJob("s1")
        val outcome = JobRegistry.output("s1", job.id, 200)
        assertTrue(outcome is JobRegistry.OutputOutcome.NoOutputYet)
        assertEquals(job.id, (outcome as JobRegistry.OutputOutcome.NoOutputYet).job.id)
    }

    @Test
    fun kill_forwardsSignalAndMarksJobKilled() {
        val sp = FakeSpawner(tmp)
        install(sp)
        val job = startJob("s1")
        val outcome = JobRegistry.kill("s1", job.id)
        assertTrue(outcome is JobRegistry.KillOutcome.Killed)
        assertTrue(sp.processes[0].wasKilled)
        val after = awaitState("s1", job.id, JobRegistry.JobState.KILLED)
        assertEquals(JobRegistry.JobState.KILLED, after.state)
        // Second kill is an idempotent success, not an error.
        val again = JobRegistry.kill("s1", job.id)
        assertTrue(again is JobRegistry.KillOutcome.AlreadyFinished)
    }

    @Test
    fun kill_onAlreadyFinishedJob_isIdempotentSuccessWithoutSignalling() {
        val sp = FakeSpawner(tmp, exitCode = 3)
        install(sp)
        val job = startJob("s1")
        sp.processes[0].finishNow()
        val done = awaitState("s1", job.id, JobRegistry.JobState.FAILED)
        assertEquals(3, done.exitCode)
        assertFalse(sp.processes[0].wasKilled)
        val outcome = JobRegistry.kill("s1", job.id)
        assertTrue(outcome is JobRegistry.KillOutcome.AlreadyFinished)
        val finished = (outcome as JobRegistry.KillOutcome.AlreadyFinished).job
        assertEquals(JobRegistry.JobState.FAILED, finished.state)
        assertEquals(3, finished.exitCode)
        assertFalse(sp.processes[0].wasKilled)
    }

    @Test
    fun sessionIsolation_bCannotSeeOrKillA() {
        val sp = FakeSpawner(tmp)
        install(sp)
        val aJob = startJob("sA")
        assertTrue(JobRegistry.list("sB").isEmpty())
        assertTrue(JobRegistry.output("sB", aJob.id) is JobRegistry.OutputOutcome.NotFound)
        assertTrue(JobRegistry.kill("sB", aJob.id) is JobRegistry.KillOutcome.NotFound)
        // A still sees its own job untouched.
        assertEquals(JobRegistry.JobState.RUNNING, jobIn("sA", aJob.id)?.state)
        assertFalse(sp.processes[0].wasKilled)
    }

    @Test
    fun runningLimit_rejectsNinthStart_untilOneIsKilled() {
        val sp = FakeSpawner(tmp)
        install(sp)
        val jobs = (1..JobRegistry.MAX_RUNNING_PER_SESSION).map { startJob("s1", command = "job $it") }
        assertEquals(JobRegistry.MAX_RUNNING_PER_SESSION, jobs.size)
        val ninth = JobRegistry.start("s1", "one too many", null)
        assertTrue(ninth is JobRegistry.StartOutcome.LimitReached)
        assertEquals(JobRegistry.MAX_RUNNING_PER_SESSION, (ninth as JobRegistry.StartOutcome.LimitReached).running)
        // Cleaning up one running job frees a slot.
        val killed = JobRegistry.kill("s1", jobs[0].id)
        assertTrue(killed is JobRegistry.KillOutcome.Killed)
        awaitState("s1", jobs[0].id, JobRegistry.JobState.KILLED)
        val retry = JobRegistry.start("s1", "fits now", null)
        assertTrue(retry is JobRegistry.StartOutcome.Started)
    }

    @Test
    fun concurrentStarts_neverExceedRunningCap() {
        val sp = FakeSpawner(tmp)
        install(sp)
        val pool = Executors.newFixedThreadPool(6)
        try {
            val futures = (1..12).map {
                pool.submit<Pair<Int, JobRegistry.StartOutcome>> {
                    it to JobRegistry.start("s1", "cmd $it", null)
                }
            }
            val outcomes = futures.map { it.get(10, TimeUnit.SECONDS) }
            val started = outcomes.count { it.second is JobRegistry.StartOutcome.Started }
            val rejected = outcomes.count { it.second is JobRegistry.StartOutcome.LimitReached }
            assertEquals(JobRegistry.MAX_RUNNING_PER_SESSION, started)
            assertEquals(12 - JobRegistry.MAX_RUNNING_PER_SESSION, rejected)
            assertEquals(
                JobRegistry.MAX_RUNNING_PER_SESSION,
                JobRegistry.list("s1").count { it.state == JobRegistry.JobState.RUNNING },
            )
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun exitZero_marksCompletedWithRecordedExitCode() {
        val sp = FakeSpawner(tmp, exitCode = 0, hold = false)
        install(sp)
        val job = startJob("s1")
        val done = awaitState("s1", job.id, JobRegistry.JobState.COMPLETED)
        assertEquals(0, done.exitCode)
    }

    @Test
    fun exitNonZero_marksFailedWithRecordedExitCode() {
        val sp = FakeSpawner(tmp, exitCode = 7, hold = false)
        install(sp)
        val job = startJob("s1")
        val done = awaitState("s1", job.id, JobRegistry.JobState.FAILED)
        assertEquals(7, done.exitCode)
    }

    /**
     * [T-job-watcher-nonblocking] The completion watcher waits on the
     * SUSPENDING variant: a process whose blocking await() never returns
     * still finishes when awaitSuspend() reports the exit — proving the
     * watcher does not depend on pinning a thread inside await().
     */
    @Test
    fun watcherUsesSuspendingAwait_notTheBlockingOne() {
        // Both knobs pre-armed via the spawner so the watcher cannot race the
        // arm: the blocking await() answers the SHELL_LOST sentinel AT ONCE
        // (which would leave the job RUNNING in the shell-lost mode, never
        // COMPLETED), while the suspending override reports the real exit.
        val sp = FakeSpawner(
            tmp,
            exitCode = 0,
            hold = true,
            forceAwaitResult = JobRegistry.SpawnedProcess.SHELL_LOST,
            awaitSuspendResult = { 0 },
        )
        install(sp)
        val job = startJob("s1")
        val done = awaitState("s1", job.id, JobRegistry.JobState.COMPLETED)
        assertEquals(0, done.exitCode)
        sp.processes[0].finishNow() // release the fake's daemon thread for teardown
    }

    @Test
    fun lazyPoll_reconcilesFinishedProcessWithoutWatcher() {
        val sp = FakeSpawner(tmp)
        install(sp)
        JobRegistry.watcherEnabled = false
        val job = startJob("s1")
        // No watcher: the job stays RUNNING until a read reconciles it.
        sp.processes[0].finishNow()
        // Give the fake's thread a beat, then a plain read must adopt the exit.
        val deadline = System.currentTimeMillis() + 5_000
        var state = JobRegistry.JobState.RUNNING
        while (System.currentTimeMillis() < deadline && state != JobRegistry.JobState.COMPLETED) {
            state = jobIn("s1", job.id)?.state ?: state
            Thread.sleep(20)
        }
        val done = jobIn("s1", job.id)
        assertNotNull(done)
        assertEquals(JobRegistry.JobState.COMPLETED, done!!.state)
        assertEquals(0, done.exitCode)
    }

    @Test
    fun prefixRef_ambiguousMatchesAreReported_notGuessed() {
        val sp = FakeSpawner(tmp)
        install(sp)
        startJob("s1", command = "one")
        startJob("s1", command = "two")
        // Every job id starts with the 4-char prefix "job_" -> ambiguous.
        val outcome = JobRegistry.output("s1", "job_", 10)
        assertTrue(outcome is JobRegistry.OutputOutcome.Ambiguous)
        assertEquals(2, (outcome as JobRegistry.OutputOutcome.Ambiguous).matches.size)
        val kill = JobRegistry.kill("s1", "job_")
        assertTrue(kill is JobRegistry.KillOutcome.Ambiguous)
    }

    // ------------------------------------------------- JobTools JSON cases

    @Test
    fun toolStart_returnsStructuredJobIdJson() = kotlinx.coroutines.runBlocking {
        install(FakeSpawner(tmp))
        val result = JobTools.execute(
            """{"action":"start","command":"make all","workdir":"/var/minis/workspace"}""",
            "s1",
            context = null,
        )
        assertTrue(result.success)
        val json = JSONObject(result.output)
        assertEquals(true, json.getBoolean("ok"))
        assertTrue(json.getString("job_id").startsWith("job_"))
        assertEquals("RUNNING", json.getString("state"))
        assertEquals("make all", json.getString("command"))
        assertEquals("/var/minis/workspace", json.getString("workdir"))
    }

    @Test
    fun toolList_emptySessionGetsFriendlyHint() = kotlinx.coroutines.runBlocking {
        val result = JobTools.execute("""{"action":"list"}""", "s1", context = null)
        assertTrue(result.success)
        assertTrue(result.output.contains("No background jobs"))
    }

    @Test
    fun toolUnknownAction_isInvalidArguments() = kotlinx.coroutines.runBlocking {
        val result = JobTools.execute("""{"action":"bogus"}""", "s1", context = null)
        assertFalse(result.success)
        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, result.errorCode)
    }

    // ---------------------------------------------------- [T-job-tree-spare]

    @Test
    fun activeJobPids_aggregatesRunningJobsAcrossSessions() {
        install(FakeSpawner(tmp))
        startJob("sess-a", "python3 server.py")
        startJob("sess-b", "make all")
        assertEquals(setOf(41001, 41002), JobRegistry.activeJobPids())
    }

    @Test
    fun activeJobPids_excludesFinishedJobs() {
        val spawner = FakeSpawner(tmp)
        install(spawner)
        val job = startJob("sess-a", "make all")
        spawner.processes.single().finishNow()
        awaitState("sess-a", job.id, JobRegistry.JobState.COMPLETED)
        // A finished job must not keep its tree spared.
        assertTrue(JobRegistry.activeJobPids().isEmpty())
    }

    @Test
    fun activeJobPids_emptyRegistryIsEmpty() {
        assertTrue(JobRegistry.activeJobPids().isEmpty())
    }

    // --------------------------------------- [T-job-kill-graceful] registry side

    @Test
    fun kill_whenGroupAlreadyGone_reportsAlreadyFinished_neverKilled() {
        val sp = FakeSpawner(tmp)
        install(sp)
        val job = startJob("s1")
        val fake = sp.processes[0]
        // The spawner now answers honestly: the group was gone before any
        // signal went out and there is no exit sidecar (the shell died).
        fake.killResult = false
        fake.hostAlive = false
        val outcome = JobRegistry.kill("s1", job.id)
        assertTrue(outcome is JobRegistry.KillOutcome.AlreadyFinished)
        val done = (outcome as JobRegistry.KillOutcome.AlreadyFinished).job
        // Honest terminal state: provably dead, exit unknowable — NOT KILLED.
        assertEquals(JobRegistry.JobState.UNKNOWN, done.state)
        assertEquals(null, done.exitCode)
        assertFalse(fake.wasKilled)
        // Terminal: the late watcher wake-up cannot flip it to KILLED either.
        assertEquals(JobRegistry.JobState.UNKNOWN, jobIn("s1", job.id)!!.state)
    }

    @Test
    fun kill_whenGroupGone_butSidecarLanded_adoptsRealExit() {
        val sp = FakeSpawner(tmp, exitCode = 3)
        install(sp)
        val job = startJob("s1")
        val fake = sp.processes[0]
        fake.finishNow() // job exited on its own just before the kill
        awaitState("s1", job.id, JobRegistry.JobState.FAILED)
        fake.killResult = false
        fake.hostAlive = false
        val outcome = JobRegistry.kill("s1", job.id)
        assertTrue(outcome is JobRegistry.KillOutcome.AlreadyFinished)
        val done = (outcome as JobRegistry.KillOutcome.AlreadyFinished).job
        assertEquals(JobRegistry.JobState.FAILED, done.state)
        assertEquals(3, done.exitCode)
    }

    @Test
    fun kill_whenUndeliveredAndTreeStillAlive_reportsKillFailed() {
        val sp = FakeSpawner(tmp)
        install(sp)
        val job = startJob("s1")
        val fake = sp.processes[0]
        fake.killResult = false
        fake.hostAlive = true
        val outcome = JobRegistry.kill("s1", job.id)
        assertTrue(outcome is JobRegistry.KillOutcome.KillFailed)
        assertEquals(JobRegistry.JobState.RUNNING, jobIn("s1", job.id)!!.state)
        assertFalse(fake.wasKilled)
    }

    // ------------------------------------ [T-job-control-bypass-mutex] registry side

    @Test
    fun signal_prefersHostSideDelivery() = kotlinx.coroutines.runBlocking {
        val sp = FakeSpawner(tmp)
        install(sp)
        val job = startJob("s1")
        val fake = sp.processes[0]
        fake.hostSignalResult = true
        assertTrue(JobRegistry.signal("s1", job.id, "TERM"))
        assertEquals(listOf(15), fake.hostSignals) // Linux SIGTERM
        // A false verdict is surfaced as a miss, not retried into a fake success.
        fake.hostSignalResult = false
        assertFalse(JobRegistry.signal("s1", job.id, "USR1"))
        assertEquals(listOf(15, 10), fake.hostSignals) // Linux SIGUSR1
        // Names the host cannot map never reach the host path.
        fake.hostSignalResult = true
        assertFalse(JobRegistry.signal("s1", job.id, "NOT-A-SIG"))
        assertEquals(listOf(15, 10), fake.hostSignals)
        // Numeric signals pass through as numbers.
        assertTrue(JobRegistry.signal("s1", job.id, "9"))
        assertEquals(listOf(15, 10, 9), fake.hostSignals)
        // A finished job has no live process group to signal at all.
        fake.finishNow()
        awaitState("s1", job.id, JobRegistry.JobState.COMPLETED)
        fake.hostSignalResult = true
        assertFalse(JobRegistry.signal("s1", job.id, "TERM"))
        assertEquals(listOf(15, 10, 9), fake.hostSignals)
    }

    @Test
    fun writeStdin_appendsHostSide_withoutGuestRoundTrip() = kotlinx.coroutines.runBlocking {
        val sp = FakeSpawner(tmp)
        install(sp)
        val job = startJob("s1")
        val fake = sp.processes[0]
        val hostFifo = File(tmp, "fake-job.in") // regular-file stand-in for the fifo
        fake.stdinHostFile = hostFifo
        fake.stdinGuestPath = "/var/minis/workspace/tool-spill/jobs/fake.in"
        assertEquals(
            "/var/minis/workspace/tool-spill/jobs/fake.in",
            JobRegistry.writeStdin("s1", job.id, "hello"),
        )
        assertEquals("hello", hostFifo.readText())
        // Appends, never truncates.
        JobRegistry.writeStdin("s1", job.id, "world")
        assertEquals("helloworld", hostFifo.readText())
        // A finished job has no live stdin and nothing is written.
        fake.finishNow()
        awaitState("s1", job.id, JobRegistry.JobState.COMPLETED)
        assertEquals(null, JobRegistry.writeStdin("s1", job.id, "late"))
        assertEquals("helloworld", hostFifo.readText())
    }

    @Test
    fun writeStdin_jobWithoutStdinPipe_returnsNull() = kotlinx.coroutines.runBlocking {
        val sp = FakeSpawner(tmp)
        install(sp)
        val job = startJob("s1")
        // No stdinGuestPath (fake default) and no host file: refused, no crash.
        assertEquals(null, JobRegistry.writeStdin("s1", job.id, "x"))
    }

    // --------------------------------------- [T-job-shell-lost-reconcile]

    @Test
    fun shellLost_treeStillAlive_keepsRecordRunningAndSpared() {
        val sp = FakeSpawner(tmp)
        install(sp)
        val job = startJob("s1")
        val fake = sp.processes[0]
        fake.awaitResult = JobRegistry.SpawnedProcess.SHELL_LOST
        fake.hostAlive = true
        val marked = awaitShellLost("s1", job.id)
        // NOT finished FAILED(-1): RUNNING with the shellLost stamp.
        assertEquals(JobRegistry.JobState.RUNNING, marked.state)
        assertEquals(null, marked.exitCode)
        // The genuinely-running tree keeps its kill-sweep spare.
        assertEquals(setOf(fake.pid), JobRegistry.activeJobPids())
        // Repeated reads never finish a live tree.
        assertEquals(JobRegistry.JobState.RUNNING, jobIn("s1", job.id)!!.state)
    }

    @Test
    fun shellLost_treeGone_finishesUnknownWithNullExit() {
        val sp = FakeSpawner(tmp)
        install(sp)
        val job = startJob("s1")
        val fake = sp.processes[0]
        fake.awaitResult = JobRegistry.SpawnedProcess.SHELL_LOST
        fake.hostAlive = false
        awaitShellLost("s1", job.id)
        val done = JobRegistry.list("s1").single()
        // Provably dead, exit unknowable: UNKNOWN(null), not FAILED(-1).
        assertEquals(JobRegistry.JobState.UNKNOWN, done.state)
        assertEquals(null, done.exitCode)
    }

    @Test
    fun shellLost_sidecarLandedBeforeDeath_adoptsRealExit() {
        val sp = FakeSpawner(tmp, exitCode = 0)
        install(sp)
        val job = startJob("s1")
        val fake = sp.processes[0]
        fake.awaitResult = JobRegistry.SpawnedProcess.SHELL_LOST
        fake.finishNow() // the exit sidecar landed before the shell died
        // The sidecar probe wins over the shell-lost mode: whether the
        // watcher marked shell-lost first or reconcile adopted the sidecar
        // directly (list() reconciles on every read — an inherent race with
        // the watcher), the record must settle COMPLETED with the REAL exit,
        // never the old FAILED(-1). The intermediate shell-lost state is
        // deliberately not asserted — it is order-dependent.
        val done = awaitState("s1", job.id, JobRegistry.JobState.COMPLETED)
        assertEquals(0, done.exitCode)
    }

    @Test
    fun shellLost_maxAgeFinishesUnknown_evenWhileTreeLives() {
        val sp = FakeSpawner(tmp)
        install(sp)
        val job = startJob("s1")
        val fake = sp.processes[0]
        fake.awaitResult = JobRegistry.SpawnedProcess.SHELL_LOST
        fake.hostAlive = true
        val marked = awaitShellLost("s1", job.id)
        assertEquals(JobRegistry.JobState.RUNNING, marked.state)
        JobRegistry.shellLostMaxAgeMs = 1L
        Thread.sleep(10) // let the age bound expire
        val done = JobRegistry.list("s1").single()
        assertEquals(JobRegistry.JobState.UNKNOWN, done.state)
        assertEquals(null, done.exitCode)
    }

    @Test
    fun shellLost_runningSlotIsHeldUntilTheTreeIsGone() {
        val sp = FakeSpawner(tmp)
        install(sp)
        val jobs = (1..JobRegistry.MAX_RUNNING_PER_SESSION).map { startJob("s1", command = "job $it") }
        val lost = sp.processes[0]
        lost.awaitResult = JobRegistry.SpawnedProcess.SHELL_LOST
        lost.hostAlive = true
        awaitShellLost("s1", jobs[0].id)
        // A shell-lost record with a live tree still holds its running slot.
        assertTrue(JobRegistry.start("s1", "one too many", null) is JobRegistry.StartOutcome.LimitReached)
        // Once the tree is gone the record finishes UNKNOWN and frees the slot.
        lost.hostAlive = false
        awaitState("s1", jobs[0].id, JobRegistry.JobState.UNKNOWN)
        assertTrue(JobRegistry.start("s1", "fits now", null) is JobRegistry.StartOutcome.Started)
    }
}
