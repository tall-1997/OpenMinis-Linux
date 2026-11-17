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
import kotlinx.coroutines.withContext

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
 * Completion is advanced three ways, and ALL are safe together:
 *  - a watcher coroutine per job calls [SpawnedProcess.await] and records the
 *    exit code (the default; disable with [watcherEnabled] when the host
 *    spawner reaps in its own background);
 *  - lazy reconciliation on every read/kill path: a [SpawnedProcess] that
 *    implements the optional [SpawnedProcess.pollExitCode] probe lets any
 *    `list`/`output`/`kill` observe a finished process without waiting for
 *    the watcher;
 *  - [T-job-shell-lost-reconcile] a job whose sandbox shell died first is
 *    kept RUNNING (with a shellLost timestamp) and reconciled from the HOST
 *    process tree on every read/kill/sweep — never guessed FAILED.
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

    /**
     * [T-job-shell-lost-reconcile] Default age bound for a shell-lost record
     * (see [shellLostMaxAgeMs]).
     */
    const val DEFAULT_SHELL_LOST_MAX_AGE_MS = 24L * 60 * 60 * 1000

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

    /**
     * [T-job-shell-lost-reconcile] UNKNOWN is the honest terminal state for a
     * job whose outcome cannot be observed: the sandbox shell died before the
     * exit sidecar landed (the sidecar write is translated by that dead
     * shell), so the process is provably gone but its exit code never will be
     * known.
     */
    enum class JobState { RUNNING, COMPLETED, FAILED, KILLED, UNKNOWN }

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
        /** GUEST path of the job's stdin FIFO; null when the spawner has none. */
        val stdinGuestPath: String? = null,
        /** Process id of the job's setsid leader; -1 when unknown. */
        val pid: Int = -1,
        /**
         * [T-job-shell-lost-reconcile] Wall clock when the sandbox shell died
         * while this job was still RUNNING (null = shell intact). The record
         * stays RUNNING — the job's host tree genuinely continues (killOwned
         * spares it) — and every reconcile pass decides from the HOST process
         * tree whether the job finished (UNKNOWN, exit unknown) or lives on.
         */
        val shellLostAtMs: Long? = null,
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

        /**
         * Optional GUEST path of the job's stdin FIFO
         * (`/var/minis/workspace/tool-spill/jobs/<id>.in`); action=input
         * appends to it. Default null = spawner has no stdin pipe (fakes).
         */
        val stdinGuestPath: String? get() = null

        /**
         * Optional process id of the job's session leader (setsid), used by
         * action=signal to deliver arbitrary signals. Default -1 = unknown.
         */
        val pid: Int get() = -1

        /**
         * [T-job-control-bypass-mutex] Optional HOST-side delivery of
         * [signalNumber] to the job's process tree. Signalling from the host
         * takes NO session mutex, so a signal lands instantly even while a
         * long foreground command holds the sandbox shell. Return true/false
         * for the delivery verdict; null = this spawner has no host-side
         * control (the registry then falls back to the legacy guest
         * round-trip). Default null = not supported.
         */
        fun signalHost(signalNumber: Int): Boolean? = null

        /**
         * [T-job-shell-lost-reconcile] Optional HOST-side liveness probe of
         * the job's process tree (zombies do not count as alive): true = a
         * live process remains, false = the tree is gone, null = unknown.
         * Used to reconcile a job whose sandbox shell died before the exit
         * sidecar could land. Default null = probe unavailable.
         */
        fun hostTreeAlive(): Boolean? = null

        /**
         * [T-job-control-bypass-mutex] Optional HOST file of the job's stdin
         * fifo (the bind-mount source of [stdinGuestPath]). Non-null lets
         * action=input append from the host without the session mutex.
         * Default null = spawner has no host-side stdin (fakes).
         */
        val stdinHostFile: File? get() = null

        companion object {
            /**
             * [T-job-shell-lost-reconcile] Sentinel [await] returns when the
             * SANDBOX SHELL died before the job finished — never an exit code
             * (a shell exit code is 0..255). The registry keeps the record in
             * the non-terminal shell-lost mode instead of finish()ing it.
             */
            const val SHELL_LOST: Int = -1
        }
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

    /**
     * [T-job-shell-lost-reconcile] How long a shell-lost record may keep
     * reconciling before it is finished UNKNOWN. Without the bound a job
     * whose shell died and whose tree somehow never dies (a daemon that
     * outlives everything) would hold a RUNNING record — and its tree-spare —
     * forever. Test seam; reset by [resetForTest].
     */
    @Volatile
    internal var shellLostMaxAgeMs: Long = DEFAULT_SHELL_LOST_MAX_AGE_MS

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

    /**
     * [T-job-control-bypass-mutex] Guest signal names → host signal numbers
     * for the host-side delivery path. Numbers (1..64) pass through
     * [signalNumber] directly.
     */
    // [T-job-signal-posix-literals] The signal name→number map lives in
    // [JobSignals] — POSIX literals, not android.system.OsConstants (the
    // Robolectric mockable android.jar strips static-final ConstantValues, so
    // every OsConstants reference resolves to 0 under unit tests).
    private val signalNumber: (String) -> Int? = JobSignals::number

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
                stdinGuestPath = spawned.stdinGuestPath,
                pid = spawned.pid,
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
            when {
                exit == Int.MIN_VALUE -> finish(record, -1)
                // [T-job-shell-lost-reconcile] The sandbox shell died before
                // the job finished. That is NOT an exit code: the job's host
                // tree genuinely continues (killOwned spares it) and the exit
                // sidecar can never land through a dead shell. Keep the
                // record RUNNING with a shellLost timestamp and let every
                // reconcile pass decide from the HOST process tree — the old
                // code finished it FAILED(-1) permanently, so a job that was
                // actually still running showed FAILED forever.
                exit == SpawnedProcess.SHELL_LOST -> markShellLost(record)
                else -> finish(record, exit)
            }
        }
    }

    /**
     * [T-job-shell-lost-reconcile] Enter the non-terminal shell-lost mode:
     * the record stays RUNNING (it still counts toward the running cap and
     * its tree stays spared) and [reconcileLocked] takes over from here.
     */
    private fun markShellLost(record: Record) {
        lock(record.key).withLock {
            if (record.job.state != JobState.RUNNING) return@withLock
            if (record.job.shellLostAtMs == null) {
                record.job = record.job.copy(shellLostAtMs = System.currentTimeMillis())
            }
        }
    }

    /** First transition wins: lazy reconcile may have beaten the watcher. */
    private fun finish(record: Record, exitCode: Int) {
        lock(record.key).withLock {
            if (record.job.state != JobState.RUNNING) return
            adoptExitLocked(record, exitCode)
        }
    }

    /** Shared exit mapping for the watcher and the lazy reconcile. */
    private fun adoptExitLocked(record: Record, code: Int) {
        val state = when {
            record.killRequested -> JobState.KILLED
            code == 0 -> JobState.COMPLETED
            else -> JobState.FAILED
        }
        record.job = record.job.copy(state = state, exitCode = code)
    }

    /** Lazy path: adopt a finished process nobody has recorded yet. */
    private fun reconcileLocked(record: Record) {
        if (record.job.state != JobState.RUNNING) return
        record.spawned.pollExitCode?.let { code ->
            adoptExitLocked(record, code)
            return
        }
        val lostAt = record.job.shellLostAtMs ?: return
        // [T-job-shell-lost-reconcile] Shell lost and no exit sidecar (the
        // sidecar write is translated by the dead shell — waiting for it is
        // pointless): decide from the HOST process tree instead. Tree gone =
        // finish honestly as UNKNOWN(null) — provably dead, exit unknowable.
        // Tree alive = the job genuinely continues; only the age bound below
        // ever ends the record.
        // [T-job-orphan-tree-probe] hostTreeAlive() can be UNAVAILABLE (null
        // — spawners without the seam); the leader pid's own /proc entry is
        // the direct fallback: a vanished leader with no sidecar is provably
        // dead — otherwise an orphaned tree (externally killed, no sidecar)
        // holds its running slot AND its kill-spare for up to 24h. A spawner
        // that OVERRIDES the seam (test fakes) is honored as-is.
        val hostVerdict = record.spawned.hostTreeAlive()
        val leaderGone = hostVerdict == null && record.job.pid > 0 &&
            !java.io.File("/proc/${record.job.pid}").exists()
        if (hostVerdict == false || leaderGone ||
            System.currentTimeMillis() - lostAt > shellLostMaxAgeMs
        ) {
            record.job = record.job.copy(state = JobState.UNKNOWN, exitCode = null)
        }
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

    // -------------------------------------------------------- protection

    /**
     * [T-job-tree-spare] Pids of every RUNNING job across ALL sessions (the
     * setsid leader of each detached guest process). SandboxWorkload's tree
     * walks subtract these trees from every kill sweep: a user Stop, a
     * command watchdog timeout or the process-cap / memory-pressure
     * supervisor must never kill a background job the model started on
     * purpose. Qwen parity: there a background job is an independent
     * PoolEntry process, invisible to the foreground command's
     * pid-descendants walk; here jobs live inside the persistent shell's
     * tree, so the walkers must actively spare them.
     *
     * Runs the lazy reconcile so a just-finished job is not spared.
     */
    fun activeJobPids(): Set<Int> {
        val pids = LinkedHashSet<Int>()
        for (k in memory.keys) {
            lock(k).withLock {
                for (record in memory[k].orEmpty()) {
                    reconcileLocked(record)
                    val pid = record.job.pid
                    if (record.job.state == JobState.RUNNING && pid > 0) pids.add(pid)
                }
            }
        }
        return pids
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

    /**
     * [T-android-jobs-interactive] Append [text] to the job's stdin FIFO.
     * Returns the guest path written to, or null when the job has no stdin
     * pipe (fakes, or spawned before the fifo existed) or the write failed.
     *
     * [T-job-control-bypass-mutex] The fifo is bind-mounted, so when the
     * spawner exposes the HOST side of it the bytes are appended directly
     * from the host — no ExecutionCoordinator.execute round-trip and no wait
     * behind the foreground command's session mutex. Only spawners without a
     * host path fall back to the legacy base64-encoded guest round-trip.
     */
    suspend fun writeStdin(sessionId: String, ref: String, text: String): String? {
        val k = key(sessionId)
        var hostFile: File? = null
        val fifo = lock(k).withLock {
            // [T-job-refs-consistent] NEWEST-first, matching JobTools' own
            // pre-check resolution (sortedByDescending startedAt) — the old
            // insertion-order find made the tool validate the NEWEST job and
            // write/signal the OLDEST one when a short prefix matched two.
            val record = memory[k].orEmpty()
                .sortedByDescending { it.job.startedAt }
                .find { it.job.id == ref || it.job.id.startsWith(ref) }
                ?: return@withLock null
            reconcileLocked(record)
            // [T-android-jobs-interactive] Only a RUNNING job has a live
            // stdin fifo — the spawner's holder removes it once the job
            // dies, and appending into the removed path would silently
            // recreate a plain file and report a fake success.
            if (record.job.state != JobState.RUNNING) return@withLock null
            hostFile = record.spawned.stdinHostFile
            // [T-job-stdin-live-seam] Read the LIVE seam, not the Job row
            // copy: the spawner can rotate stdin paths after start (the
            // Job field is a snapshot), and a stale copy would either write
            // into a dead pipe or return the wrong guest path.
            record.spawned.stdinGuestPath ?: record.job.stdinGuestPath
        } ?: return null
        val host = hostFile
        if (host != null) {
            val ok = withContext(Dispatchers.IO) {
                JobHostControl.appendStdin(host, text.toByteArray(Charsets.UTF_8))
            }
            return if (ok) fifo else null
        }
        val b64 = java.util.Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
        val out = com.openminis.app.sandbox.ExecutionCoordinator.execute(
            sessionId,
            "printf '%s' '$b64' | base64 -d >> '$fifo'",
        )
        return if (out.exitCode == 0) fifo else null
    }

    /**
     * Deliver [signal] (e.g. "TERM", "USR1", "INT") to the job's process
     * group. Uses the persisted leader pid; distinct from [kill] which is
     * TERM+KILL unconditionally.
     *
     * [T-job-control-bypass-mutex] Delivered from the HOST when the spawner
     * supports it ([SpawnedProcess.signalHost]) — no session mutex, so a
     * signal lands instantly even while a long foreground command holds the
     * sandbox shell. Only spawners without host-side control fall back to
     * the legacy guest round-trip.
     */
    suspend fun signal(sessionId: String, ref: String, signal: String): Boolean {
        val k = key(sessionId)
        val record = lock(k).withLock {
            // [T-job-refs-consistent] NEWEST-first, matching JobTools' own
            // pre-check resolution (sortedByDescending startedAt) — the old
            // insertion-order find made the tool validate the NEWEST job and
            // write/signal the OLDEST one when a short prefix matched two.
            val record = memory[k].orEmpty()
                .sortedByDescending { it.job.startedAt }
                .find { it.job.id == ref || it.job.id.startsWith(ref) }
                ?: return@withLock null
            reconcileLocked(record)
            // A finished job has no live process group to signal — reporting
            // a delivered signal for one would be a fake success.
            if (record.job.state != JobState.RUNNING) return@withLock null
            record
        } ?: return false
        val pid = record.job.pid
        if (pid <= 0) return false
        val sig = signal.trim().removePrefix("-").uppercase()
        val safe = if (sig.matches(Regex("[A-Z0-9]+"))) sig else return false
        // No fake success on either path: a dead group fails the signal and
        // the caller reports the miss.
        signalNumber(safe)?.let { num ->
            record.spawned.signalHost(num)?.let { return it }
        }
        // Legacy guest round-trip (spawners without host-side control).
        val out = com.openminis.app.sandbox.ExecutionCoordinator.execute(
            sessionId,
            "kill -$safe -- -$pid 2>/dev/null || kill -$safe $pid 2>/dev/null",
        )
        return out.exitCode == 0
    }

    /**
     * [T-job-control-bypass-mutex] Guest signal name (or number) → host
     * signal number for [SpawnedProcess.signalHost]. OsConstants values are
     * compile-time constants, so this table costs nothing on the JVM test
     * classpath either. null = not a host-mappable signal; the legacy guest
     * path still accepts it as a name.
     */

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
            // [T-job-kill-graceful] Not delivered — and the spawner now
            // answers honestly: false means NO signal reached the group (it
            // was already gone before any signal went out, or the delivery
            // failed). Undo the kill request first so everything decided
            // from here on keeps its REAL verdict: a landed sidecar records
            // its actual exit (COMPLETED/FAILED), never a fake KILLED, and
            // when no sidecar can exist (it needs the shell — see
            // [T-job-shell-lost-reconcile]) the record finishes UNKNOWN(null)
            // instead of pretending.
            record.killRequested = false
            lock(k).withLock { reconcileLocked(record) }
            // Whatever the reconcile could prove — a real exit sidecar, or the
            // shell-lost UNKNOWN — means the job finished WITHOUT our signal:
            // "already finished", never "termination signal delivered".
            if (record.job.state != JobState.RUNNING) {
                return KillOutcome.AlreadyFinished(record.job)
            }
            when (record.spawned.hostTreeAlive()) {
                false -> {
                    // The tree is gone with no exit evidence.
                    lock(k).withLock {
                        if (record.job.state == JobState.RUNNING) {
                            record.job = record.job.copy(
                                state = JobState.UNKNOWN,
                                exitCode = null,
                            )
                        }
                    }
                    return KillOutcome.AlreadyFinished(record.job)
                }
                // Still alive (or the probe is unavailable and nothing can be
                // proven): the miss is real — report it.
                else -> return KillOutcome.KillFailed(
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
        shellLostMaxAgeMs = DEFAULT_SHELL_LOST_MAX_AGE_MS
        memory.clear()
        locks.clear()
        usedIds.clear()
    }
}
