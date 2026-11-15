package com.openminis.app.sandbox

import com.openminis.app.ssh.SshBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The thin second implementation proving the [ExecutionEnvironment]
 * abstraction (pi packages/env: local and remote behind one interface): run
 * the command on a remote host through the EXISTING [SshBackend] channel —
 * the same pooled JSch session SshTool drives, so no second transport,
 * credential path, or host-key store is created here.
 *
 * This is a feasibility proof, not a full remote-workspace port: an SSH-bound
 * session still resolves through SshWorkspaceOps ahead of the dispatcher
 * (that path keeps its own file-surface semantics). What this class buys is
 * the seam: while a session is SSH-bound, SshWorkspaceOps registers an
 * instance of it into [ExecutionEnvironments] under the owner session id —
 * with registration/unregistration aligned to the binding lifecycle — so
 * remote execution IS a registry entry, even though the intercept branch
 * still produces the model-visible result today.
 *
 * Instances are cheap, stateless views over the shared backend (backend ref +
 * host id + optional cwd), which is what lets the registry keep one entry per
 * bound session without owning transport state.
 *
 * Known limitations (deliberate, documented):
 * - exec-channel only: no SFTP surface, no PTY, no interactive sessions.
 * - [ExecOptions.env] is honored by prefixing `export K='V'; ` (POSIX shell
 *   escaping) so the assignments apply to the WHOLE command line, not just
 *   the first simple command.
 * - [ExecOptions.stdin] is unsupported (the backend's exec channel exposes no
 *   stdin input); declared honestly in [environmentInfo].
 * - [com.openminis.app.ssh.SshFailure] propagates from [exec]: "the command
 *   never ran" (auth/network/changed host key) is a throw per the interface
 *   contract, while a remote command's non-zero exit is data in the result.
 */
class SshExecutionEnvironment(
    private val backend: SshBackend,
    private val hostRef: String,
    /** Remote working directory; the backend wraps it as a quoted cd prefix. */
    private val cwd: String? = null,
) : ExecutionEnvironment {

    override val id: String = "ssh:$hostRef"

    override val workingDir: String get() = cwd ?: "~"

    override suspend fun exec(command: String, options: ExecOptions): ExecResult =
        withContext(Dispatchers.IO) {
            val effective = if (options.env.isEmpty()) command else shellExportPrefix(options.env) + command
            val r = backend.exec(hostRef, effective, options.timeoutMs, cwd)
            ExecResult(
                exitCode = r.exitCode,
                stdout = r.stdout,
                stderr = r.stderr,
                durationMs = r.durationMs,
                timedOut = r.timedOut,
            )
        }

    override fun environmentInfo(): EnvironmentInfo = EnvironmentInfo(
        kind = "ssh",
        os = "linux",
        // exec-channel commands are parsed by the user's remote login shell;
        // POSIX sh is the portable assumption for command translation.
        shell = "/bin/sh",
        pathSeparator = "/",
        mergesStderr = false,
        supportsPerCallEnv = true,
        supportsStdin = false,
    )

    /** POSIX single-quote escaping: `it's` -> `'it'\''s'`. Visible for tests. */
    internal fun shellExportPrefix(env: Map<String, String>): String {
        if (env.isEmpty()) return ""
        val assignments = env.entries.joinToString(" ") { (k, v) ->
            "$k='${v.replace("'", "'\\''")}'"
        }
        return "export $assignments; "
    }
}
