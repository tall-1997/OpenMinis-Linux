package com.openminis.app.sandbox

import com.openminis.app.tools.ToolErrorCode
import com.openminis.app.tools.ToolExecutionResult
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import org.json.JSONObject

/**
 * One command execution's knobs (pi packages/env ExecOptions, simplified).
 * Every field has a production default so `exec(command)` is the common call.
 *
 * Support per implementation is declared by [EnvironmentInfo]:
 * - [env]: honored by [SshExecutionEnvironment] (`export` prefix); the PRoot
 *   persistent-shell route does NOT take per-call env — session environment
 *   variables flow through EnvVarRepository / ShellEnvWriter instead — so
 *   [PRootExecutionEnvironment] ignores it (declared honestly in
 *   [EnvironmentInfo.supportsPerCallEnv]).
 * - [stdin]: no current implementation feeds a stdin channel (the persistent
 *   shell drives commands as marker-wrapped lines; the SSH backend runs exec
 *   channels without stdin). Declared false in both [EnvironmentInfo]s; a
 *   caller that needs stdin must encode it into the command (heredoc / pipe).
 */
data class ExecOptions(
    /** Wall-clock cap for this command. 0/negative means "delegate default". */
    val timeoutMs: Long = 600_000L,
    /** Extra environment variables for THIS command only. */
    val env: Map<String, String> = emptyMap(),
    /** Optional stdin payload. Unsupported by current implementations. */
    val stdin: String? = null,
)

/**
 * One command execution's outcome (pi packages/env ExecResult, simplified).
 *
 * [stdout]/[stderr] follow each implementation's declared [EnvironmentInfo.mergesStderr]:
 * the PRoot route merges everything into [stdout] and leaves [stderr] empty,
 * the SSH route keeps the streams separate.
 */
data class ExecResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val durationMs: Long,
    /**
     * True only when the EXECUTION'S watchdog ended the command — never a
     * bare exit code guess (a user command may legitimately exit 124; see
     * ExecutionCoordinator.CommandResult.timedOut for the same distinction).
     */
    val timedOut: Boolean = false,
)

/**
 * Capability negotiation info (the simplified form of pi's daemon
 * `hello`/features handshake): the caller reads this ONCE and decides how to
 * phrase commands for the environment — path separators, whether stderr is
 * merged, whether per-call env / stdin exist. An older environment can never
 * grow fields, so consumers must treat unknown capabilities as absent.
 */
data class EnvironmentInfo(
    /** Environment family id: "proot", "ssh", or a future custom one. */
    val kind: String,
    /** Guest-side OS family the commands target, e.g. "linux". */
    val os: String,
    /** Shell the command line is parsed by, e.g. "/bin/bash" or "/bin/sh". */
    val shell: String,
    /** Path separator of the guest filesystem, e.g. "/". */
    val pathSeparator: String,
    /** True: [ExecResult.stdout] carries stderr too ([ExecResult.stderr] stays empty). */
    val mergesStderr: Boolean,
    /** True: [ExecOptions.env] is honored per call. */
    val supportsPerCallEnv: Boolean,
    /** True: [ExecOptions.stdin] is honored. */
    val supportsStdin: Boolean,
)

/**
 * Execution environment abstraction (pi packages/env ExecutionEnv, reduced to
 * the one surface this app needs today: run one command).
 *
 * Contract:
 * - [exec] returns the command's outcome; a command that FAILS is data
 *   ([ExecResult.exitCode] != 0), not a throw. Throwing is reserved for
 *   "the command never ran" — transport/setup failure (SSH auth, sandbox not
 *   booted) — so a caller can distinguish a failed command from a dead env.
 * - Implementations own no process management of their own: both current
 *   implementations delegate (PRoot -> ExecutionCoordinator's persistent
 *   shell pipeline, SSH -> SshBackend), per the pi-env rule that the
 *   abstraction wraps, never reimplements, the platform machinery.
 * - No Android types and no UI types cross this interface, so tests drive
 *   every implementation with fakes on the JVM.
 */
interface ExecutionEnvironment {

    /** Stable diagnostic id, e.g. "proot:<sessionId>" or "ssh:<hostRef>". */
    val id: String

    /** The directory commands start in (best-effort; guests may cd elsewhere). */
    val workingDir: String

    /**
     * Execute one command line. See the interface KDoc for the
     * data-vs-throw contract, and [environmentInfo] for what the
     * implementation actually honors from [options].
     */
    suspend fun exec(command: String, options: ExecOptions = ExecOptions()): ExecResult

    /** Capability negotiation for command-translation decisions upstream. */
    fun environmentInfo(): EnvironmentInfo
}

/**
 * Process-wide per-session environment override registry — the seam that lets
 * a session route its shell-class tools somewhere other than the default PRoot
 * pipeline. The production registrant is the SSH workspace interceptor
 * (com.openminis.app.ssh.SshWorkspaceOps.intercept): while a session is bound
 * to an SSH host, its [SshExecutionEnvironment] is registered under the owner
 * session id and unregistered on every teardown path (unbind, host removal,
 * binding gone at call time). That intercept still resolves ahead of the
 * dispatcher, so remote shell calls keep their SshTool result shape — the
 * registry makes the session's environment LOOKABLE by the dispatcher's one
 * lookup point ([ChatViewModelExecuteToolExt.executeTool]'s shell branch)
 * without changing which code runs today.
 */
object ExecutionEnvironments {

    private val overrides = ConcurrentHashMap<String, ExecutionEnvironment>()

    /** The registered override for [sessionId], or null for the default PRoot route. */
    fun overrideFor(sessionId: String): ExecutionEnvironment? = overrides[sessionId]

    /** Register an override; returns the previous one (null when none). */
    fun register(sessionId: String, environment: ExecutionEnvironment): ExecutionEnvironment? =
        overrides.put(sessionId, environment)

    /** Remove an override; returns the removed one (null when none). */
    fun unregister(sessionId: String): ExecutionEnvironment? = overrides.remove(sessionId)

    /**
     * The default environment for a session: the PRoot pipeline. A factory
     * (not a singleton) because the persistent shell is per-session state.
     */
    fun prootForSession(sessionId: String): PRootExecutionEnvironment =
        PRootExecutionEnvironment.forSession(sessionId)

    /**
     * shell-class tool arguments -> [ExecutionEnvironment] -> [ToolExecutionResult]:
     * the mapping the ChatViewModel dispatcher uses when a session runs with
     * a registered override. Mirrors the legacy executeShellCommand result
     * shape — output, then a ` (exit code N)` suffix on non-zero — so a
     * caller cannot tell which environment produced the result by format.
     */
    suspend fun executeShell(
        environment: ExecutionEnvironment,
        argsJson: String,
    ): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrNull() ?: JSONObject()
        val command = args.optString("command", "")
        val toolTitle = args.optString("tool_title", "shell_execute")
        if (command.isBlank()) {
            return ToolExecutionResult("Error: 'command' is required", false, toolTitle = toolTitle)
        }
        // Cap at the SSH tool's ceiling for parity: both remote and local
        // commands share one bound in this mapped path.
        val timeoutMs = args.optLong("timeout", 0L).let { requested ->
            if (requested <= 0) ExecOptions().timeoutMs else requested * 1000L
        }.coerceIn(1_000L, 900_000L)
        return try {
            val result = environment.exec(command, ExecOptions(timeoutMs = timeoutMs))
            result.toShellToolResult(toolTitle)
        } catch (e: CancellationException) {
            // Coroutine hygiene: a user Stop must keep cancelling, never turn
            // into a fabricated failed tool result.
            throw e
        } catch (e: Exception) {
            // "The command never ran" — transport/setup failure. Honest
            // failure, no fabricated exit code.
            ToolExecutionResult(
                "${environment.id} failed to run the command: ${e.message}",
                false,
                errorCode = ToolErrorCode.EXECUTION_FAILED,
                toolTitle = toolTitle,
            )
        }
    }
}

/** [ExecResult] -> the shell_execute result shape the model already knows. */
private fun ExecResult.toShellToolResult(toolTitle: String): ToolExecutionResult {
    val body = buildString {
        append(stdout.ifBlank { if (stderr.isBlank()) "(no output)" else "" })
        if (stderr.isNotBlank()) {
            if (isNotEmpty() && !endsWith("\n")) appendLine()
            appendLine("--- stderr ---")
            append(stderr)
        }
    }.ifBlank { "(no output)" }
    val output = if (exitCode != 0) "$body (exit code $exitCode)" else body
    return ToolExecutionResult(
        output = output,
        success = exitCode == 0,
        toolTitle = toolTitle,
        timedOut = timedOut,
        errorCode = if (timedOut) ToolErrorCode.TIMEOUT else null,
    )
}
