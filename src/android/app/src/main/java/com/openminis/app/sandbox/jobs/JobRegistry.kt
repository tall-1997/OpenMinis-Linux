package com.openminis.app.sandbox.jobs

import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Background job bookkeeping for agents (deepseek-harness-style "jobs": hang a
 * long command in the background, poll its output later, terminate it on
 * demand) — so a 20-minute build no longer holds the agent turn hostage the
 * way a blocking `shell_execute` does.
 *
 * [T-android-jobs-registry] This registry is BOOKKEEPING ONLY. It never
 * creates a process itself: how a guest command is spawned, where its
 * stdout/stderr land, and how it is signalled are host-side details the
 * registry must not know (the sandbox execution chain is private to the
 * host wiring). Process creation is delegated to a spawner installed once
 * at startup — the same static-injection shape as [com.openminis.app.di.AppGraph]:
 *
 * ```
 * JobRegistry.install { sessionId, command, workingDir ->
 *     // wrap the command, redirect output into the session spill dir,
 *     // return a SpawnedProcess view of the detached guest process
 * }
 * ```
 *
 * With no spawner installed the registry stays inert and `job start` reports
 * a structured UNSUPPORTED error instead of pretending to work.
 *
 * Completion is advanced two ways, and BOTH are safe together:
 *  - a watcher coroutine per job calls [SpawnedProcess.await] and records the
 *    exit code (the default; disable with [watcherEnabled] when the host
 *    spawner reaps in its own background);
 *  - lazy reconciliation on every read/kill path: a [SpawnedProcess] that
 *    implements the optional [SpawnedProcess.pollExitCode] probe lets any
 *    `list`/`output`/`kill` observe a finished process without waiting for
 *    the watcher.
 *
 * State lives in memory only, mirroring [com.openminis.app.tools.SubAgentBatchRegistry]:
 * the registry is process-local, so an app restart legitimately loses job
 * handles (the guest processes died with it). Session isolation is absolute —
 * every lookup is scoped to the caller's sessionId, so an agent can see and
 * kill only its own session's jobs and cannot even learn that another
 * session's job id exists (a foreign id reads as "not found").
 */
object JobRegistry {

    /** Max concurrently RUNNING jobs per session; further starts are refused. */
    const val MAX_RUNNING_PER_SESSION = 8

    /** Terminal jobs kept per session before oldest-finished trimming. */
    private const val MAX_JOB_HISTORY = 32

    const val DEFAULT_TAIL_LINES = 200
    const val MAX_TAIL_LINES = 2_000

    /**
     * Output is read as a bounded tail, not the whole file: a chatty build
     * log can be megabytes and the model asked for the last N lines. The
     * window caps the read; [MAX_TAIL_CHARS] then caps what actually reaches
     * the model (same spirit as [com.openminis.app.tools.ToolOutputSpill.LIMIT]).
     */
    private const val TAIL_WINDOW_BYTES = 256 * 1024
    private const val MAX_TAIL_CHARS = 16_000

    enum class JobState { RUNNING, COMPLETED, FAILED, KILLED }

    /**
     * Immutable snapshot of one background job. [sessionId] is the registry
     * key the job was started under (lanes keep their lane id, so a sub-agent
     * lane's jobs stay private to that lane unless the host keys them by the
     * owner session). [outputSpillPath] is the GUEST-visible path the agent
     * can `file_read` for the full log, when the spawner reports one.
     */
    data class Job(
        val id: String,
        val sessionId: String,
        val command: String,
        val workingDir: String?,
        val startedAt: Long,
        val state: JobState = JobState.RUNNING,
        val exitCode: Int? = null,
        val outputSpillPath: String? = null,
    )

    /**
     * The registry's view of one detached guest process. Implemented by the
     * host spawner; tests implement it with fakes.
     */
    interface SpawnedProcess {
        /** Host-side file the process stdout+stderr is redirected into. */
        val stdoutFile: File

        /** Blocking wait for process exit; returns the exit code. */
        fun await(): Int

        /** Terminate the process. True when a signal was delivered. */
        fun kill(): Boolean

        /**
         * Optional non-blocking exit probe: the exit code once the process
         * has exited, null while it is still running (or unknown). Default
         * null = no lazy probe; completion then relies on the await watcher.
         */
        val pollExitCode: Int? get() = null

        /**
         * Optional GUEST path of [stdoutFile] (e.g.
         * `/var/minis/workspace/tool-spill/jobs/<id>.log`) surfaced to the
         * agent in [Job.outputSpillPath]. Default null = the spawner does
         * not expose the log to the guest filesystem.
         */
        val outputGuestPath: String? get() = null
    }

    /** Start outcomes; mapped to structured tool results by [com.openminis.app.tools.JobTools]. */
    sealed interface StartOutcome {
        data class Started(val job: Job) : StartOutcome
        /** [running] is the count the caller must shrink before retrying. */
        data class LimitReached(val running: Int) : StartOutcome
        object SpawnerMissing : StartOutcome
        data class SpawnFailed(val cause: String) : StartOutcome
    }

    sealed interface OutputOutcome {
        data class Found(val job: Job, val tail: String, val truncated: Boolean) : OutputOutcome
        /** Job exists but its output file does not (yet) — not an error. */
        data class NoOutputYet(val job: Job) : OutputOutcome
        data class Ambiguous(val matches: List<String>) : OutputOutcome
        object NotFound : OutputOutcome
    }

    sealed interface KillOutcome {
        data class Killed(val job: Job) : KillOutcome
        /** Idempotent success: the job had already reached a terminal state. */
        data class AlreadyFinished(val job: Job) : KillOutcome
        data class KillFailed(val job: Job, val reason: String) : KillOutcome
        data class Ambiguous(val matches: List<String>) : KillOutcome
        object NotFound : KillOutcome
    }

    /** Host-injected process factory (see class KDoc). Raw function type per spec. */
    @Volatile
    private var spawner: ((sessionId: String, command: String, workingDir: String?) -> SpawnedProcess)? = null

    /**
     * Wire the process factory. The host calls this ONCE at startup
     * (MinisApp.onCreate, after the execution chain is initialised); unit
     * tests reinstall a fake per test. A re-install logs but does not throw,
     * so a test that forgot its teardown cannot wedge the next test.
     */
    fun install(spawner: (sessionId: String, command: String, workingDir: String?) -> SpawnedProcess) {
        if (this.spawner != null) {
            java.util.logging.Logger.getLogger("JobRegistry").warning(
                "JobRegistry spawner re-installed — expected only at app startup or in tests",
            )
        }
        this.spawner = spawner
    }

    /**
     * Await-watcher switch. True (default) = every start launches a
     * coroutine that blocks on [SpawnedProcess.await]. Disable when the host
     * spawner reaps its processes and implements [SpawnedProcess.pollExitCode]
     * — then completion is reconciled lazily on reads.
     */
    @Volatile
    internal var watcherEnabled: Boolean = true

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** RunStore-style per-session lock + record list. */
    private val locks = ConcurrentHashMap<String, ReentrantLock>()
    private val memory = ConcurrentHashMap<String, MutableList<Record>>()

    /**
     * Every job id ever issued, globally and lock-free. Uniqueness must not
     * scan [memory]: that would iterate another session's list without its
     * lock (ConcurrentModificationException territory). Retired ids stay
     * here on purpose — a trimmed job's id must never be reissued.
     */
    private val usedIds = ConcurrentHashMap.newKeySet<String>()

    private class Record(
        val key: String,
        @Volatile var job: Job,
        val spawned: SpawnedProcess,
    ) {
        /** Set by [kill] so the watcher marks the death KILLED, not FAILED. */
        @Volatile
        var killRequested: Boolean = false
    }

    private fun key(sessionId: String): String = sessionId.ifBlank { "__default__" }

    private fun lock(k: String): ReentrantLock = locks.getOrPut(k) { ReentrantLock() }

    // ---------------------------------------------------------------- start

    fun start(sessionId: String, command: String, workingDir: String? = null): StartOutcome {
        val spawnFn = spawner ?: return StartOutcome.SpawnerMissing
        val cmd = command.trim()
        if (cmd.isEmpty()) return StartOutcome.SpawnFailed("command is empty")
        val k = key(sessionId)
        val workdir = workingDir?.trim()?.ifEmpty { null }

        // Cheap pre-check so the common overflow path never pays a spawn.
        val preCount = lock(k).withLock { runningCountLocked(k) }
        if (preCount >= MAX_RUNNING_PER_SESSION) return StartOutcome.LimitReached(preCount)

        // Spawn OUTSIDE the session lock: the host spawner may block for
        // seconds negotiating with the guest shell.
        val spawned = try {
            spawnFn(k, cmd, workdir)
        } catch (t: Throwable) {
            return StartOutcome.SpawnFailed("${t.javaClass.simpleName}: ${t.message ?: "spawn failed"}")
        }

        lock(k).withLock {
            // [T-android-jobs-registry] Re-check under the lock: two concurrent
            // starts both passed the pre-check, and the loser must not exceed
            // the cap. Kill the freshly spawned orphan instead of leaking it.
            val running = runningCountLocked(k)
            if (running >= MAX_RUNNING_PER_SESSION) {
                runCatching { spawned.kill() }
                return StartOutcome.LimitReached(running)
            }
            val job = Job(
                id = newJobIdLocked(),
                sessionId = k,
                command = cmd,
                workingDir = workdir,
                startedAt = System.currentTimeMillis(),
                state = JobState.RUNNING,
                exitCode = null,
                outputSpillPath = spawned.outputGuestPath,
            )
            val record = Record(k, job, spawned)
            val list = memory.getOrPut(k) { mutableListOf() }
            list.add(record)
            trimHistoryLocked(list)
            launchWatcher(record)
            return StartOutcome.Started(job)
        }
    }

    private fun runningCountLocked(k: String): Int =
        memory[k].orEmpty().count { it.job.state == JobState.RUNNING }

    /** Terminal jobs are history; drop the oldest ones past the cap. */
    private fun trimHistoryLocked(list: MutableList<Record>) {
        while (list.size > MAX_JOB_HISTORY) {
            val victim = list.indexOfFirst { it.job.state != JobState.RUNNING }
            if (victim < 0) return // all RUNNING; the running cap bounds size anyway
            list.removeAt(victim)
        }
    }

    private fun newJobIdLocked(): String {
        while (true) {
            val id = "job_" + UUID.randomUUID().toString().replace("-", "").take(10)
            if (usedIds.add(id)) return id
        }
    }

    // ----------------------------------------------------------- completion

    private fun launchWatcher(record: Record) {
        if (!watcherEnabled) return
        scope.launch {
            // A throwing await() is a spawner bug, not a job outcome; surface
            // it as a FAILED exit -1 rather than leaving the job RUNNING
            // forever. Int.MIN_VALUE marks "await blew up" distinctly from a
            // legitimate negative exit code before the mapping below.
            val exit = runCatching { record.spawned.await() }.getOrElse { Int.MIN_VALUE }
            finish(record, if (exit == Int.MIN_VALUE) -1 else exit)
        }
    }

    /** First transition wins: lazy reconcile may have beaten the watcher. */
    private fun finish(record: Record, exitCode: Int) {
        lock(record.key).withLock {
            if (record.job.state != JobState.RUNNING) return
            val state = when {
                record.killRequested -> JobState.KILLED
                exitCode == 0 -> JobState.COMPLETED
                else -> JobState.FAILED
            }
            record.job = record.job.copy(state = state, exitCode = exitCode)
        }
    }

    /** Lazy path: adopt a finished process nobody has recorded yet. */
    private fun reconcileLocked(record: Record) {
        if (record.job.state != JobState.RUNNING) return
        val code = record.spawned.pollExitCode ?: return
        val state = when {
            record.killRequested -> JobState.KILLED
            code == 0 -> JobState.COMPLETED
            else -> JobState.FAILED
        }
        record.job = record.job.copy(state = state, exitCode = code)
    }

    // --------------------------------------------------------------- lookup

    /** Newest-first snapshot of this session's jobs (RunStore ordering). */
    fun list(sessionId: String): List<Job> {
        val k = key(sessionId)
        return lock(k).withLock {
            memory[k].orEmpty()
                .onEach { reconcileLocked(it) }
                .map { it.job }
                .sortedByDescending { it.startedAt }
        }
    }

    private sealed interface JobRef {
        data class Found(val record: Record) : JobRef
        data class Ambiguous(val ids: List<String>) : JobRef
        object Missing : JobRef
    }

    /**
     * Resolve an id or unique id prefix WITHIN one session. MUST be called
     * under the session lock: it iterates the session's record list.
     * Cross-session refs resolve to [JobRef.Missing] on purpose: a foreign
     * job must be indistinguishable from a nonexistent one.
     */
    private fun resolveLocked(k: String, ref: String): JobRef {
        val records = memory[k].orEmpty()
        if (ref.isEmpty()) return JobRef.Missing
        records.firstOrNull { it.job.id == ref }?.let { return JobRef.Found(it) }
        // Prefix matching only for a ref long enough to be meaningful, same
        // floor as CronJobTool.removeJob (job ids are `job_` + 10 hex chars).
        if (ref.length < 4) return JobRef.Missing
        val hits = records.filter { it.job.id.startsWith(ref) }
        return when {
            hits.size == 1 -> JobRef.Found(hits.first())
            hits.isEmpty() -> JobRef.Missing
            else -> JobRef.Ambiguous(hits.map { it.job.id })
        }
    }

    // -------------------------------------------------------------- output

    fun output(sessionId: String, ref: String, tailLines: Int = DEFAULT_TAIL_LINES): OutputOutcome {
        val k = key(sessionId)
        val record = lock(k).withLock {
            when (val r = resolveLocked(k, ref)) {
                is JobRef.Found -> {
                    reconcileLocked(r.record)
                    r.record
                }
                is JobRef.Ambiguous -> return OutputOutcome.Ambiguous(r.ids)
                JobRef.Missing -> return OutputOutcome.NotFound
            }
        }
        val file = record.spawned.stdoutFile
        if (!file.isFile) return OutputOutcome.NoOutputYet(record.job)
        val (tail, truncated) = readTailLines(file, tailLines.coerceIn(1, MAX_TAIL_LINES))
        return OutputOutcome.Found(record.job, tail, truncated)
    }

    // ---------------------------------------------------------------- kill

    fun kill(sessionId: String, ref: String): KillOutcome {
        val k = key(sessionId)
        val record = lock(k).withLock {
            when (val r = resolveLocked(k, ref)) {
                is JobRef.Found -> {
                    reconcileLocked(r.record)
                    r.record
                }
                is JobRef.Ambiguous -> return KillOutcome.Ambiguous(r.ids)
                JobRef.Missing -> return KillOutcome.NotFound
            }
        }
        if (record.job.state != JobState.RUNNING) {
            return KillOutcome.AlreadyFinished(record.job)
        }
        record.killRequested = true
        val delivered = try {
            record.spawned.kill()
        } catch (t: Throwable) {
            false
        }
        if (delivered) {
            // Optimistic transition: the signal is out and the process is
            // going down; the await watcher respects killRequested if it
            // lands first. Adopt the probe's code when it already knows one.
            val code = record.spawned.pollExitCode
            lock(k).withLock {
                if (record.job.state == JobState.RUNNING) {
                    record.job = record.job.copy(state = JobState.KILLED, exitCode = code)
                }
            }
        } else {
            // Not delivered: either the process died between the resolve and
            // the signal (fine), or the spawner could not reach it (report).
            lock(k).withLock { reconcileLocked(record) }
            if (record.job.state == JobState.RUNNING) {
                return KillOutcome.KillFailed(
                    record.job,
                    "kill signal was not delivered and the process is still running",
                )
            }
        }
        return KillOutcome.Killed(record.job)
    }

    // ---------------------------------------------------------- tail read

    /**
     * Bounded tail read: last [TAIL_WINDOW_BYTES] of the file, split to
     * lines, last [maxLines] kept. The first (possibly partial) line of the
     * window is dropped when the window starts mid-file, so a UTF-8 char cut
     * in half by the seek never leaks mojibake into the result.
     */
    private fun readTailLines(file: File, maxLines: Int): Pair<String, Boolean> {
        val len = file.length()
        if (len <= 0L) return "" to false
        val window = minOf(len, TAIL_WINDOW_BYTES.toLong())
        val start = len - window
        val bytes = ByteArray(window.toInt())
        var read = 0
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            while (read < bytes.size) {
                val n = raf.read(bytes, read, bytes.size - read)
                if (n < 0) break
                read += n
            }
        }
        var text = String(bytes, 0, read, Charsets.UTF_8)
        var truncated = start > 0L
        if (start > 0L) {
            val nl = text.indexOf('\n')
            if (nl < 0) return "" to true // one partial line fills the whole window
            text = text.substring(nl + 1)
        }
        val lines = text.split('\n').toMutableList()
        if (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.lastIndex)
        if (lines.size > maxLines) {
            while (lines.size > maxLines) lines.removeAt(0)
            truncated = true
        }
        var out = lines.joinToString("\n")
        if (out.length > MAX_TAIL_CHARS) {
            out = safeTail(out, MAX_TAIL_CHARS)
            truncated = true
        }
        return out to truncated
    }

    /** Surrogate-safe tail cut (mirror of ToolOutputPolicy.safeSubstring). */
    private fun safeTail(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        var cut = text.length - maxChars
        if (Character.isLowSurrogate(text[cut])) cut++
        return text.substring(cut)
    }

    /** Test seam: forget every job and the installed spawner. */
    internal fun resetForTest() {
        spawner = null
        watcherEnabled = true
        memory.clear()
        locks.clear()
        usedIds.clear()
    }
}
