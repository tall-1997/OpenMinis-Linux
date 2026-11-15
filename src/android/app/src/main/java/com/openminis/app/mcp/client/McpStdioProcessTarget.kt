package com.openminis.app.mcp.client

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * [T-mcp-stdio-target] The stdio client talks to THIS, never to
 * `java.lang.Process` directly — the close sequence, the kill order and the IO
 * streams are then testable against a fake (pi's stdio tests drive
 * child_process fakes the same way).
 *
 * The production target ([PRootSpawnTarget]) already is the in-process direct
 * spawn: the app's own ProcessBuilder starts proot with the full rootfs argv
 * and the MCP server runs as its tracee — no external relay process, no
 * stdin/stdout multiplexer. pi achieves the same isolation with process
 * groups; on Android the tree is owned by `SandboxWorkload` instead (setpgid
 * adoption + /proc tree walk + group SIGKILL), reached through
 * [onTerminated].
 */
interface StdioProcessTarget {
    /** Server stdin — the client writes one JSON-RPC line per message. */
    val stdin: OutputStream

    val stdout: InputStream

    val stderr: InputStream

    /** Host pid, when the platform exposes one (reflection, per SandboxWorkload.pidOf). */
    fun pid(): Int?

    fun isAlive(): Boolean

    /** Waits up to [timeoutMs] for exit; true = exited within the window. */
    fun awaitExit(timeoutMs: Long): Boolean

    /** SIGTERM — the polite terminate (JVM `destroy()` on POSIX). */
    fun sigterm()

    /** SIGKILL — the forcible terminate (host pid kill + `destroyForcibly`). */
    fun sigkill()

    /**
     * Close-time classification hook, called exactly once per spawned process:
     * graceful = exited (possibly only after SIGTERM) so the tracer's own
     * teardown ran; forcible = had to be SIGKILLed, so the host must kill the
     * surviving tree itself (`SandboxWorkload.release(kill = true)` — the
     * upstream beta.2 "stopping one ends its whole process tree" contract).
     */
    fun onTerminated(graceful: Boolean) {}
}

/**
 * Real-process target: a JVM [Process] plus the reflection pid — the same
 * `getMethod("pid")` pattern `SandboxWorkload.pidOf` uses, so the host-side
 * SIGKILL backstop works on Android JVMs that hide the pid elsewhere.
 */
open class JavaProcessTarget(protected val process: Process) : StdioProcessTarget {
    override val stdin: OutputStream get() = process.outputStream
    override val stdout: InputStream get() = process.inputStream
    override val stderr: InputStream get() = process.errorStream

    override fun pid(): Int? = runCatching {
        val raw = process.javaClass.getMethod("pid").invoke(process) as Number
        raw.toInt()
    }.getOrNull()?.takeIf { it > 0 }

    override fun isAlive(): Boolean = process.isAlive

    override fun awaitExit(timeoutMs: Long): Boolean =
        runCatching { process.waitFor(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)

    override fun sigterm() {
        // JVM destroy() = SIGTERM on POSIX — pi killProcessTree(child, "SIGTERM").
        runCatching { process.destroy() }
    }

    override fun sigkill() {
        val pid = pid()
        if (pid != null) {
            // Host-side SIGKILL first (android.os.Process; JVM unit tests stub
            // this to a no-op), destroyForcibly as the JVM-level backstop.
            runCatching { android.os.Process.killProcess(pid) }
        }
        runCatching { process.destroyForcibly() }
    }
}

/**
 * Spawns the guest stdio server IN-PROCESS: the full proot argv (rootfs + bind
 * mounts) from [McpStdioClient.SpawnSpec] goes straight into the app's own
 * ProcessBuilder — the Kotlin client owns the server's stdio pipes end to end.
 * Lifecycle ownership rides [McpStdioClient.ProcessHooks] (SandboxWorkload
 * track/release): background cgroup confinement, the global process cap, and
 * the tree-wide kill when a stop is not graceful.
 */
class PRootSpawnTarget(
    spec: McpStdioClient.SpawnSpec,
    private val hooks: McpStdioClient.ProcessHooks?,
) : JavaProcessTarget(spawnProcess(spec)) {

    companion object {
        /**
         * ProcessBuilder spawn with the spec's host env (PROOT_* loader vars)
         * overlaid by the guest env (PATH/HOME/config env) — mirrors the proot
         * prefix PersistentShell builds and daemon.py `MCPStdioServer.start`.
         * Throws IOException upward; the client maps it to a spawn McpError.
         */
        private fun spawnProcess(spec: McpStdioClient.SpawnSpec): Process {
            val pb = ProcessBuilder(spec.argv)
            pb.environment().clear()
            pb.environment().putAll(spec.processEnv)
            pb.environment().putAll(spec.guestEnv)
            pb.redirectErrorStream(false)
            return pb.start()
        }
    }

    init {
        // Registered with SandboxWorkload before any traffic flows — the whole
        // guest tree is owned from the first byte (background cgroup, process
        // cap, memory-pressure sweeps).
        hooks?.onSpawned(process)
    }

    override fun onTerminated(graceful: Boolean) {
        hooks?.onTerminated(process, graceful)
    }
}
