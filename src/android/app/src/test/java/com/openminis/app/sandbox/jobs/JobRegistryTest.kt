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
     */
    private class FakeProcess(
        override val stdoutFile: File,
        private val exitCode: Int,
        private val hold: Boolean,
        override val outputGuestPath: String? = null,
    ) : JobRegistry.SpawnedProcess {
        private val release = CountDownLatch(1)
        private val finished = CountDownLatch(1)

        @Volatile private var killed = false
        @Volatile private var done = false
        @Volatile private var code = exitCode

        init {
            Thread({
                if (hold) release.await()
                if (killed) code = 137
                done = true
                finished.countDown()
            }, "fake-job").apply { isDaemon = true }.start()
        }

        override fun await(): Int {
            finished.await(5, TimeUnit.SECONDS)
            return code
        }

        override fun kill(): Boolean {
            if (done) return false
            killed = true
            release.countDown()
            return true
        }

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
    ) {
        private val counter = java.util.concurrent.atomic.AtomicLong()
        val processes = mutableListOf<FakeProcess>()
        val guestPath = "/var/minis/workspace/tool-spill/jobs/fake.log"

        val fn: (String, String, String?) -> JobRegistry.SpawnedProcess = { _, _, _ ->
            val p = FakeProcess(newOutputFile(), exitCode, hold, guestPath)
            synchronized(processes) { processes.add(p) }
            p
        }

        private fun newOutputFile(): File {
            val f = File(dir, "job-${counter.incrementAndGet()}.log")
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

    // ------------------------------------------------------ registry cases

    @Test
    fun start_withoutSpawner_isRejectedWithStructuredSupport() {
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
    fun toolStart_returnsStructuredJobIdJson() {
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
    fun toolList_emptySessionGetsFriendlyHint() {
        val result = JobTools.execute("""{"action":"list"}""", "s1", context = null)
        assertTrue(result.success)
        assertTrue(result.output.contains("No background jobs"))
    }

    @Test
    fun toolUnknownAction_isInvalidArguments() {
        val result = JobTools.execute("""{"action":"bogus"}""", "s1", context = null)
        assertFalse(result.success)
        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, result.errorCode)
    }
}
