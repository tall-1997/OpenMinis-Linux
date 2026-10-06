package com.openminis.app.ssh

/**
 * [T-ssh-backend] Data model for the remote SSH execution backend.
 *
 * The SSH stack runs in the HOST app process (JSch is a pure-Java client),
 * not inside the PRoot guest. Local file ends of SFTP transfers therefore
 * need guest→host path resolution (PRootKernel.resolveSessionHostPath)
 * before JSch ever sees them.
 *
 * Secrets (passwords, private keys, passphrases) never live in these model
 * objects' serialized form: SshConfigStore keeps metadata in a plain JSON
 * file and secret material in keystore-backed EncryptedSharedPreferences,
 * mirroring EnvVarRepository's split.
 */

enum class SshAuthType {
    PASSWORD,
    PRIVATE_KEY;

    companion object {
        fun parse(raw: String): SshAuthType? = when (raw.trim().lowercase()) {
            "password", "pass", "pwd" -> PASSWORD
            "private_key", "key", "pubkey" -> PRIVATE_KEY
            else -> null
        }
    }

    val wireName: String get() = if (this == PASSWORD) "password" else "private_key"
}

/**
 * One configured remote host. [id] is stable and generated; [name] is the
 * human/agent-facing reference (also accepted as a lookup key).
 * [hostKeyFingerprint] is the TOFU record — null until the first successful
 * connect pins it.
 */
data class SshHostConfig(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val authType: SshAuthType,
    val hostKeyFingerprint: String? = null,
    val createdAt: Long = 0L,
) {
    val label: String get() = "$username@$host:$port"

    /** Display form used in tool output — never contains secret material. */
    fun describe(): String = buildString {
        append("- ").append(name).append(" (id=").append(id).append(") ")
        append(label).append(" auth=").append(authType.wireName)
        if (!hostKeyFingerprint.isNullOrBlank()) append(" pinned=").append(hostKeyFingerprint.take(24)).append('…')
    }
}

/** Result of one remote command execution. */
data class SshExecResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val durationMs: Long,
    val hostLabel: String = "",
    /** True when stdout/stderr hit the capture cap and were truncated. */
    val truncated: Boolean = false,
) {
    val success: Boolean get() = !timedOut && exitCode == 0
}

/** One entry of a remote directory listing (SFTP `ls`). */
data class SshLsEntry(
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val mtimeEpochSec: Long,
)

/** Typed failure surface — SshTool maps these onto ToolErrorCode. */
sealed class SshFailure(message: String) : Exception(message) {
    class UnknownHost(ref: String) : SshFailure("No SSH host matching '$ref'. Use action=list_hosts to see configured hosts, or add_host to configure one.")
    class Auth(hostLabel: String, detail: String?) :
        SshFailure("SSH authentication failed for $hostLabel${detail?.let { " ($it)" } ?: ""}. Check username/password/private_key via action=add_host (re-add replaces credentials).")
    class HostKeyMismatch(hostLabel: String, stored: String, presented: String) :
        SshFailure("SSH host key for $hostLabel CHANGED (stored $stored, server presented $presented). Possible man-in-the-middle. If the server was legitimately reinstalled/rekeyed, remove the host and add it again to re-pin (TOFU).")
    class Network(hostLabel: String, detail: String?) :
        SshFailure("Cannot reach SSH server $hostLabel${detail?.let { " ($it)" } ?: ""}.")
    class Timeout(op: String, timeoutMs: Long) :
        SshFailure("SSH $op timed out after ${timeoutMs / 1000}s.")
    class Protocol(detail: String) : SshFailure("SSH protocol error: $detail")
    class Config(detail: String) : SshFailure(detail)
}

/** `cd <cwd> && <command>` with POSIX single-quote escaping. Pure + tested. */
internal object SshCommandWrap {
    fun wrapCwd(cwd: String?, command: String): String {
        val dir = cwd?.trim().orEmpty()
        if (dir.isEmpty()) return command
        val escaped = dir.replace("'", "'\\''")
        return "cd '$escaped' && $command"
    }
}
