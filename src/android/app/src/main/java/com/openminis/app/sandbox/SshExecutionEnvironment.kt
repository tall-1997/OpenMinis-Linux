package com.openminis.app.sandbox

import com.openminis.app.logging.AppLogger
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
 *   the first simple command. Keys that are not valid POSIX shell names are
 *   dropped (with a log line, never silent): a poisoned key would make the
 *   whole prefix a syntax error and break the ENTIRE command line, not just
 *   one variable (review P2-15). The drop-log renders the key through
 *   [sanitizeEnvKeyForLog] — control characters stripped, 64-char cap — so
 *   caller-controlled bytes can never forge or bloat log lines (P3 review).
 *   The caller is environment negotiation, so this is a skip, not an exception.
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

    /** Log tag. Not const: const val is only legal in objects/companions. */
    private val tag = "SshExecutionEnvironment"

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

    /** POSIX shell export-name rule (review P2-15): [A-Za-z_][A-Za-z0-9_]*. */
    private val SHELL_ENV_KEY = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /** Cap on the logged length of a rejected env key (see [sanitizeEnvKeyForLog]). */
    private val MAX_LOGGED_ENV_KEY_CHARS = 64

    /**
     * Log-safe rendering of a REJECTED env key (P3 review). The key is caller
     * data (environment negotiation), so before it reaches the log:
     *  - every control character (< 0x20 and 0x7F) is stripped — a `\n` in the
     *    key would forge extra log lines and a `\r` could rewrite them (log
     *    injection);
     *  - the remainder is truncated to [MAX_LOGGED_ENV_KEY_CHARS] chars so a
     *    giant key cannot bloat the log file.
     * Valid shell names never reach this path (the name rule accepts none of
     * the stripped characters and nothing near 64 chars matters diagnostically),
     * so the transformation can only ever make a diagnostic less verbatim.
     * Pure function, internal for tests — AppLogger has no injectable appender
     * to assert against.
     */
    internal fun sanitizeEnvKeyForLog(key: String): String {
        val printable = key.filter { it.code >= 0x20 && it.code != 0x7F }
        return printable.take(MAX_LOGGED_ENV_KEY_CHARS)
    }

    /** POSIX single-quote escaping: `it's` -> `'it'\''s'`. Visible for tests. */
    internal fun shellExportPrefix(env: Map<String, String>): String {
        if (env.isEmpty()) return ""
        // A key outside the POSIX name rule (empty, spaces, `-`, a leading
        // digit) turns the whole `export ...` line into a remote-shell syntax
        // error — that breaks the ENTIRE command, not one variable. Drop such
        // keys with a log line: the caller is environment negotiation, so a
        // malformed key is caller data, not an exception worth failing the
        // exec over (review P2-15). The key is sanitized before logging —
        // raw caller bytes never reach the log (P3 review).
        val assignments = StringBuilder()
        for ((k, v) in env) {
            if (!SHELL_ENV_KEY.matches(k)) {
                AppLogger.warning(
                    tag,
                    "shellExportPrefix: dropping env var with invalid shell name: ${sanitizeEnvKeyForLog(k)}",
                )
                continue
            }
            if (assignments.isNotEmpty()) assignments.append(' ')
            assignments.append("$k='${v.replace("'", "'\\''")}'")
        }
        if (assignments.isEmpty()) return ""
        return "export $assignments; "
    }
}
