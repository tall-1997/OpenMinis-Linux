package com.openminis.app.sandbox.jobs

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.sandbox.ProcLink
import com.openminis.app.sandbox.ProcessTree
import com.openminis.app.sandbox.SessionWorkspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Base64

/**
 * Host-side bridge from [JobRegistry]'s spawner seam to real sandbox
 * processes. The guest command is backgrounded *inside* the sandbox
 * (`setsid ... &` plus an exit-code sidecar file), so the coordinator
 * round-trip only spawns and returns — the job outlives the tool call and
 * inherits every sandbox safety chain (MCPToolPolicy, resource gate,
 * per-session mutex) because it rides the normal execute() path.
 *
 * Output/exit files live under the workspace tool-spill tree, which is
 * bind-mounted: the guest writes them, the host polls them, and the agent
 * can also `file_read` the same paths as GUEST paths.
 *
 * [T-android-jobs-registry]
 */
object JobSpawner {

    private const val GUEST_JOBS_DIR = "/var/minis/workspace/tool-spill/jobs"
    private const val SPAWN_TIMEOUT_MS = 30_000L

    /**
     * [T-android-jobs-await-backoff] Exit/liveness poll cadence: exponential
     * backoff instead of a fixed 500ms sleep — 100ms start (a short job
     * returns on the first or second check), x2 per round, capped at 1s. A
     * long-running job no longer wakes the host twice a second for its whole
     * lifetime; a dead shell is still noticed within one round of its death.
     */
    private const val AWAIT_POLL_INITIAL_MS = 100L
    private const val AWAIT_POLL_CAP_MS = 1_000L
    private const val PID_MARKER = "MINIS_JOB_PID="

    /** Idempotent; call once after [ExecutionCoordinator.init]. */
    fun install(appContext: Context) {
        val filesDir = appContext.filesDir
        JobRegistry.install { sessionId, command, workingDir ->
            runBlocking {
                withContext(Dispatchers.IO) { spawn(filesDir, sessionId, command, workingDir) }
            }
        }
    }

    private suspend fun spawn(
        filesDir: File,
        sessionId: String,
        command: String,
        workingDir: String?,
    ): JobRegistry.SpawnedProcess {
        val token = "job-" + java.util.UUID.randomUUID().toString().replace("-", "").take(12)
        val owner = ExecutionCoordinator.ownerSessionId(sessionId)
        val hostJobsDir = hostJobsDir(filesDir, owner)
        // The script is built host-side and shipped base64-encoded, so the
        // agent's command never fights shell quoting on the spawn line.
        val script = buildString {
            append("#!/bin/sh\n")
            if (!workingDir.isNullOrBlank()) {
                append("cd '").append(workingDir.replace("'", "'\\''")).append("' 2>/dev/null || true\n")
            }
            append(command).append('\n')
            append("echo $? > '").append(GUEST_JOBS_DIR).append('/').append(token).append(".exit'\n")
        }
        val b64 = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_8))
        // [T-android-jobs-interactive] A named pipe per job keeps stdin alive:
        // the script reads it as its stdin, a background holder keeps the
        // write end open so the reader blocks instead of seeing EOF, and
        // action=input appends base64-decoded bytes into it. The holder is a
        // `while kill -0 $JOB` watchdog, NOT `sleep infinity`: the moment the
        // job's process dies it exits AND removes the fifo/script/pid
        // sidecars — a finished job leaks neither a process nor a fifo. The
        // pid sidecar is written on the same spawn line (one coordinator
        // round trip less) so action=signal can reach the process group.
        val spawnCmd = "mkdir -p '$GUEST_JOBS_DIR' && rm -f '$GUEST_JOBS_DIR/$token.in' && mkfifo '$GUEST_JOBS_DIR/$token.in' && " +
            "echo $b64 | base64 -d > '$GUEST_JOBS_DIR/$token.sh' && " +
            "{ setsid sh '$GUEST_JOBS_DIR/$token.sh' < '$GUEST_JOBS_DIR/$token.in' >> '$GUEST_JOBS_DIR/$token.log' 2>&1 & " +
            "JOB=\$!; echo \$JOB > '$GUEST_JOBS_DIR/$token.pid'; " +
            "( while kill -0 \$JOB 2>/dev/null; do sleep 1; done; " +
            "rm -f '$GUEST_JOBS_DIR/$token.in' '$GUEST_JOBS_DIR/$token.sh' '$GUEST_JOBS_DIR/$token.pid' ) > '$GUEST_JOBS_DIR/$token.in' & }; " +
            "echo $PID_MARKER\$JOB"
        val result = ExecutionCoordinator.execute(sessionId, spawnCmd, SPAWN_TIMEOUT_MS)
        if (result.exitCode != 0) {
            throw IllegalStateException("job spawn failed (exit ${result.exitCode})")
        }
        val pidText = Regex("$PID_MARKER(\\d+)").find(result.output)?.groupValues?.get(1)
            ?: throw IllegalStateException("job spawn produced no pid")
        // [T-job-control-bypass-mutex] The setsid leader's pid IS a host pid
        // (PRoot has no pid namespace) — captured once so the object members
        // below can never resolve the outer String local over the Int member.
        val hostPid = pidText.toIntOrNull() ?: -1
        val logHost = hostJobsDir.resolve("$token.log")
        val exitHost = hostJobsDir.resolve("$token.exit")
        // [T-job-control-bypass-mutex] The fifo is a bind-mounted file: the
        // guest path above AND this host path are the same inode, so stdin
        // bytes can be appended from the host without a guest round-trip.
        val stdinHost = hostJobsDir.resolve("$token.in")
        val guestLog = "$GUEST_JOBS_DIR/$token.log"
        val guestStdin = "$GUEST_JOBS_DIR/$token.in"
        return object : JobRegistry.SpawnedProcess {
            override val stdoutFile: File = logHost
            override val outputGuestPath: String = guestLog
            override val stdinGuestPath: String = guestStdin
            override val stdinHostFile: File = stdinHost
            override val pid: Int = hostPid

            override fun await(): Int {
                // [T-android-jobs-await-backoff] Same checks as before (exit
                // sidecar first, then shell liveness as the isAlive probe),
                // but the sleep doubles each round from 100ms up to the 1s cap
                // instead of a fixed 500ms spin.
                var pollMs = AWAIT_POLL_INITIAL_MS
                while (true) {
                    pollExitCode?.let { return it }
                    // [T-job-shell-lost-reconcile] A dead shell is NOT an exit
                    // code. Report the sentinel and let JobRegistry keep the
                    // record in the non-terminal shell-lost mode — the job's
                    // host tree may well still be running (killOwned spares
                    // it), and the old code marked it FAILED(-1) forever.
                    if (!ExecutionCoordinator.hasLiveShell(sessionId)) {
                        return JobRegistry.SpawnedProcess.SHELL_LOST
                    }
                    Thread.sleep(pollMs)
                    pollMs = (pollMs * 2).coerceAtMost(AWAIT_POLL_CAP_MS)
                }
            }

            override suspend fun awaitSuspend(): Int {
                // [T-job-watcher-nonblocking] The registry's completion
                // watcher runs this instead of [await]: identical checks
                // (exit sidecar, then shell liveness) and the same 100ms→1s
                // backoff cadence, but the wait suspends between polls so the
                // job does not pin a shared Dispatchers.IO thread for its
                // whole lifetime.
                var pollMs = AWAIT_POLL_INITIAL_MS
                while (true) {
                    pollExitCode?.let { return it }
                    if (!ExecutionCoordinator.hasLiveShell(sessionId)) {
                        return JobRegistry.SpawnedProcess.SHELL_LOST
                    }
                    delay(pollMs)
                    pollMs = (pollMs * 2).coerceAtMost(AWAIT_POLL_CAP_MS)
                }
            }

            override val pollExitCode: Int?
                get() = exitHost.takeIf { it.isFile }
                    ?.readText()?.trim()?.toIntOrNull()

            override fun kill(): Boolean {
                // [T-job-kill-graceful] TERM, a real grace window, then KILL
                // only if the group is still alive — and an honest verdict:
                // a group that was already gone before any signal reports
                // false so JobRegistry.kill reconciles the real exit instead
                // of marking KILLED.
                // [T-job-control-bypass-mutex] Delivered straight from the
                // host (the setsid leader's pid IS a host pid — PRoot runs
                // without a pid namespace), so no ExecutionCoordinator.execute
                // round-trip and no wait behind the foreground command's
                // session mutex.
                return JobHostControl.killTree(hostPid)
            }

            override fun signalHost(signalNumber: Int): Boolean =
                JobHostControl.signalJob(hostPid, signalNumber)

            override fun hostTreeAlive(): Boolean? = JobHostControl.groupAlive(hostPid)
        }
    }

    private fun hostJobsDir(filesDir: File, owner: String): File =
        SessionWorkspace.hostDir(filesDir, owner, "workspace").resolve("tool-spill/jobs")
}

/**
 * [T-job-control-bypass-mutex] Host-side control plane for background job
 * process trees — the narrow signalling/kill/stdin surface the registry
 * reaches through its [JobRegistry.SpawnedProcess] seam so that a
 * model-initiated `kill`/`signal`/`input` never queues behind the per-session
 * execution mutex (a 20-minute foreground build used to block them for its
 * whole wall clock, because the old guest-side `kill`/`printf >> fifo` went
 * through ExecutionCoordinator.execute).
 *
 * The pids are real HOST pids: PRoot runs without a pid namespace, so the
 * setsid leader pid the guest reports is exactly what /proc shows here (the
 * same assumption SandboxWorkload's job-tree sparing already relies on), and
 * the app uid owns those processes, so the host may signal them. Tree walks
 * reuse [ProcessTree]; group/state reads use /proc/<pid>/stat directly.
 */
internal object JobHostControl {

    /**
     * [T-job-kill-graceful] TERM→KILL grace: long enough for a graceful
     * handler (a build tool flushing state, a server closing sockets) to
     * actually run, since the old back-to-back `kill -TERM; kill -KILL` gave
     * it zero time. No session mutex is held while waiting.
     */
    private const val TERM_GRACE_MS = 2_000L

    /** Bounded post-KILL observation so a fast death is reported as observed. */
    private const val DEATH_OBSERVE_MS = 2_000L
    private const val DEATH_POLL_MS = 100L

    /**
     * [T-job-kill-graceful] TERM the job's process group (and any tree
     * member that escaped the group), wait the grace window, KILL only what
     * is still alive, and answer honestly:
     *  - `false` ONLY when the group was already gone before any signal went
     *    out (or the TERM itself failed ESRCH — the job died in the race);
     *    JobRegistry.kill then reconciles the real exit instead of marking
     *    KILLED;
     *  - `true` when a signal was delivered and the death was observed (or
     *    SIGKILL is out and the kernel must finish it).
     */
    fun killTree(rootPid: Int): Boolean {
        if (rootPid <= 0) return false
        if (groupAlive(rootPid) == false) return false
        val termDelivered = signalGroup(rootPid, OsConstants.SIGTERM)
        signalEscapees(rootPid, OsConstants.SIGTERM)
        sleepMs(TERM_GRACE_MS)
        if (groupAlive(rootPid) == false) return termDelivered
        signalGroup(rootPid, OsConstants.SIGKILL)
        signalEscapees(rootPid, OsConstants.SIGKILL)
        var waited = 0L
        while (waited < DEATH_OBSERVE_MS && groupAlive(rootPid) == true) {
            sleepMs(DEATH_POLL_MS)
            waited += DEATH_POLL_MS
        }
        return true
    }

    /**
     * [T-job-control-bypass-mutex] Deliver [signalNumber] to the job's
     * process group, falling back to the leader alone — the host-side mirror
     * of the old guest `kill -SIG -- -pid || kill -SIG pid`.
     */
    fun signalJob(rootPid: Int, signalNumber: Int): Boolean {
        if (rootPid <= 0 || signalNumber <= 0) return false
        if (signalGroup(rootPid, signalNumber)) return true
        return runCatching { Os.kill(rootPid, signalNumber) }.isSuccess
    }

    /**
     * [T-job-shell-lost-reconcile] Is any LIVE process still in the job's
     * process group? A zombie member does not count: after a kill the leader
     * can linger as an unreaped zombie (the shell that would reap it may be
     * gone), and a plain `kill(-pgid, 0)` would report it alive forever.
     * true = alive, false = gone, null = procfs could not be inspected.
     */
    fun groupAlive(pgid: Int): Boolean? {
        if (pgid <= 0) return null
        val dirs = File("/proc").listFiles() ?: return null
        var scanned = false
        for (dir in dirs) {
            val pid = dir.name.toIntOrNull() ?: continue
            val stat = runCatching { File(dir, "stat").readText() }.getOrNull() ?: continue
            val parsed = parseStat(stat) ?: continue
            scanned = true
            if (parsed.second == pgid && parsed.first != 'Z') return true
        }
        return if (scanned) false else null
    }

    /**
     * [T-job-control-bypass-mutex] Append [bytes] to the job's stdin fifo
     * from the HOST (same inode as the guest path — bind mount). The
     * non-blocking open is load-bearing: a fifo whose reader (the job's
     * script) is already gone must fail fast with ENXIO/ENOENT instead of
     * blocking the caller forever, and it must NOT be recreated as a plain
     * file by a creat-mode append — the fake success the guest path already
     * guarded against.
     */
    fun appendStdin(target: File, bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        return try {
            // Probe the posix layer and the target in one call. On the JVM
            // test classpath android.system.Os is a stub — it either returns
            // null or throws a non-posix stub error — and a plain java append
            // is exact for the regular-file stand-ins the tests use. On
            // device ENOENT MUST propagate: an append must never recreate the
            // removed fifo as a plain file (the fake success this whole
            // non-blocking path exists to avoid).
            val st = try {
                Os.stat(target.absolutePath)
            } catch (e: ErrnoException) {
                throw e
            } catch (t: Throwable) {
                null
            }
            if (st == null) return plainAppend(target, bytes)
            val flags = OsConstants.O_WRONLY or OsConstants.O_APPEND or OsConstants.O_NONBLOCK
            val fd = Os.open(target.absolutePath, flags, 0)
            try {
                writeAll(fd, bytes)
            } finally {
                runCatching { Os.close(fd) }
            }
        } catch (e: ErrnoException) {
            // ENOENT (holder removed the fifo) or ENXIO (no reader): the job
            // is not accepting input — never a fake success.
            false
        } catch (t: Throwable) {
            false
        }
    }

    /** JVM-test fallback: plain java append, exact for regular files. */
    private fun plainAppend(target: File, bytes: ByteArray): Boolean =
        runCatching { FileOutputStream(target, true).use { it.write(bytes) } }.isSuccess

    /**
     * Write the whole buffer. The fd is O_NONBLOCK, so a full pipe surfaces
     * as EAGAIN. [T-job-stdin-partial-honest] A PARTIAL write is a FAILURE,
     * not success: the old `return written > 0` let a >64 KB payload to a
     * slow-reading job land truncated while the tool result claimed "input
     * delivered" — silent byte loss on the exact workload this path serves
     * (multi-KB REPL commands). Only a complete write returns true; the
     * caller reports the miss honestly.
     */
    private fun writeAll(fd: java.io.FileDescriptor, bytes: ByteArray): Boolean {
        var written = 0
        var attempts = 0
        while (written < bytes.size) {
            try {
                val n = Os.write(fd, bytes, written, bytes.size - written)
                if (n <= 0) return false
                written += n
                attempts = 0
            } catch (e: ErrnoException) {
                if (e.errno != OsConstants.EAGAIN) return false
                attempts++
                if (attempts > 10) return false
                sleepMs(20)
            }
        }
        return true
    }

    /** Tree members outside the leader's own group (daemonised escapees). */
    private fun signalEscapees(rootPid: Int, sig: Int) {
        val links = readLinks()
        for (pid in ProcessTree.collect(rootPid, links)) {
            if (pid == rootPid) continue
            runCatching { Os.kill(pid, sig) }
        }
    }

    private fun signalGroup(pgid: Int, sig: Int): Boolean =
        try {
            Os.kill(-pgid, sig)
            true
        } catch (_: Throwable) {
            false
        }

    private fun readLinks(): List<ProcLink> {
        val links = ArrayList<ProcLink>(64)
        val dirs = File("/proc").listFiles() ?: return links
        for (dir in dirs) {
            val pid = dir.name.toIntOrNull() ?: continue
            val status = runCatching { File(dir, "status").readText() }.getOrNull() ?: continue
            val (ppid, tracer) = ProcessTree.parseStatusIds(status)
            links += ProcLink(pid, ppid, tracer)
        }
        return links
    }

    /**
     * `pid (comm) state ppid pgrp ...` — comm may contain spaces and
     * parentheses, so fields restart after the last `)` (mirror of
     * [ProcessTree.parseStatPgrp], plus the state letter).
     */
    private fun parseStat(stat: String): Pair<Char, Int>? {
        val close = stat.lastIndexOf(')')
        if (close < 0 || close + 2 >= stat.length) return null
        val fields = stat.substring(close + 2).trim().split(Regex("\\s+"))
        val state = fields.getOrNull(0)?.trim()?.firstOrNull() ?: return null
        val pgrp = fields.getOrNull(2)?.toIntOrNull() ?: return null
        return state to pgrp
    }

    private fun sleepMs(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
