package com.openminis.app.sandbox.jobs

import android.content.Context
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.sandbox.SessionWorkspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
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
        val spawnCmd = "mkdir -p '$GUEST_JOBS_DIR' && echo $b64 | base64 -d > '$GUEST_JOBS_DIR/$token.sh' && " +
            "setsid sh '$GUEST_JOBS_DIR/$token.sh' >> '$GUEST_JOBS_DIR/$token.log' 2>&1 & echo $PID_MARKER\$!"
        val result = ExecutionCoordinator.execute(sessionId, spawnCmd, SPAWN_TIMEOUT_MS)
        if (result.exitCode != 0) {
            throw IllegalStateException("job spawn failed (exit ${result.exitCode})")
        }
        val pid = Regex("$PID_MARKER(\\d+)").find(result.output)?.groupValues?.get(1)
            ?: throw IllegalStateException("job spawn produced no pid")
        val logHost = hostJobsDir.resolve("$token.log")
        val exitHost = hostJobsDir.resolve("$token.exit")
        val guestLog = "$GUEST_JOBS_DIR/$token.log"
        return object : JobRegistry.SpawnedProcess {
            override val stdoutFile: File = logHost
            override val outputGuestPath: String = guestLog

            override fun await(): Int {
                // [T-android-jobs-await-backoff] Same checks as before (exit
                // sidecar first, then shell liveness as the isAlive probe),
                // but the sleep doubles each round from 100ms up to the 1s cap
                // instead of a fixed 500ms spin.
                var pollMs = AWAIT_POLL_INITIAL_MS
                while (true) {
                    pollExitCode?.let { return it }
                    if (!ExecutionCoordinator.hasLiveShell(sessionId)) return -1
                    Thread.sleep(pollMs)
                    pollMs = (pollMs * 2).coerceAtMost(AWAIT_POLL_CAP_MS)
                }
            }

            override val pollExitCode: Int?
                get() = exitHost.takeIf { it.isFile }
                    ?.readText()?.trim()?.toIntOrNull()

            override fun kill(): Boolean {
                // setsid makes the spawned pid a session leader, so the
                // negative-pid form takes down its whole process tree.
                val out = runBlocking {
                    withContext(Dispatchers.IO) {
                        ExecutionCoordinator.execute(
                            sessionId,
                            "kill -TERM -- -$pid 2>/dev/null; kill -KILL -- -$pid 2>/dev/null; true",
                            SPAWN_TIMEOUT_MS,
                        )
                    }
                }
                return out.exitCode == 0
            }
        }
    }

    private fun hostJobsDir(filesDir: File, owner: String): File =
        SessionWorkspace.hostDir(filesDir, owner, "workspace").resolve("tool-spill/jobs")
}
