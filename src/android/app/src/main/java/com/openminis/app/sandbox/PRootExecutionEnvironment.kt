package com.openminis.app.sandbox

/**
 * The default [ExecutionEnvironment]: the PRoot persistent-shell pipeline.
 *
 * Delegation choice (pi packages/env rule: the abstraction wraps the platform
 * machinery, never reimplements it): [ExecutionCoordinator] is the
 * session-scoped persistent-shell route the agent's shell_execute already
 * runs through — output sanitization, output caps, MCP tool policy, resource
 * gating, stop epochs and channel recovery all stay in place. The one-shot
 * [ShellExecutor] was the alternative, but it bypasses that pipeline and
 * would have made "same command, same output, same exit code" impossible.
 *
 * Semantics carried across:
 * - stdout and stderr arrive MERGED (the persistent shell merges both streams
 *   and [ExecutionCoordinator] post-processes one combined buffer), so
 *   [ExecResult.stderr] is always empty and
 *   [EnvironmentInfo.mergesStderr] is true.
 * - [ExecOptions.env] is NOT applied per call: the coordinator's env story is
 *   session-level (EnvVarRepository -> ShellEnvWriter). Declared honestly in
 *   [EnvironmentInfo.supportsPerCallEnv].
 * - [ExecOptions.stdin] is unsupported (commands are marker-wrapped stdin
 *   lines, not a raw stdin channel).
 *
 * The [delegate] seam exists for JVM tests: production resolves through the
 * companion factory (which references [ExecutionCoordinator]); tests inject a
 * fake delegate and never load the coordinator's Android context path.
 */
class PRootExecutionEnvironment(
    private val sessionId: String,
    private val delegate: Delegate,
) : ExecutionEnvironment {

    /**
     * The narrowed view of [ExecutionCoordinator.execute] this environment
     * needs. Kept as a fun interface so tests fake it without Android.
     */
    fun interface Delegate {
        suspend fun execute(
            command: String,
            timeoutMs: Long,
            resourceClass: SandboxResourceGate.ResourceClass,
            lineCallback: ((String) -> Unit)?,
        ): ExecutionCoordinator.CommandResult
    }

    override val id: String = "proot:$sessionId"

    override val workingDir: String = "/var/minis/workspace"

    override suspend fun exec(command: String, options: ExecOptions): ExecResult {
        val timeoutMs = if (options.timeoutMs <= 0) ExecOptions().timeoutMs else options.timeoutMs
        val r = delegate.execute(
            command = command,
            timeoutMs = timeoutMs,
            resourceClass = SandboxResourceGate.ResourceClass.AUTO,
            lineCallback = null,
        )
        return ExecResult(
            exitCode = r.exitCode,
            // Merged-stream semantics: the coordinator already sanitized,
            // truncated and annotated the combined output.
            stdout = r.output,
            stderr = "",
            durationMs = r.durationMs,
            timedOut = r.timedOut,
        )
    }

    override fun environmentInfo(): EnvironmentInfo = EnvironmentInfo(
        kind = "proot",
        os = "linux",
        // The guest ships GNU bash; the agent path also self-heals into bash
        // when ash-incompatible syntax is detected.
        shell = "/bin/bash",
        pathSeparator = "/",
        mergesStderr = true,
        supportsPerCallEnv = false,
        supportsStdin = false,
    )

    companion object {
        /**
         * Production environment for a session: delegates to
         * [ExecutionCoordinator.execute] with the session's persistent shell.
         * The reference to the coordinator lives INSIDE the lambda so that
         * loading this class (as tests do, with an injected delegate) never
         * forces the coordinator's Android wiring.
         */
        fun forSession(sessionId: String): PRootExecutionEnvironment =
            PRootExecutionEnvironment(sessionId) { command, timeoutMs, resourceClass, lineCallback ->
                ExecutionCoordinator.execute(
                    sessionId = sessionId,
                    command = command,
                    timeout = timeoutMs,
                    lineCallback = lineCallback,
                    resourceClass = resourceClass,
                )
            }
    }
}
