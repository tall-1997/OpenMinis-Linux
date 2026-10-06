package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.data.repository.EnvVarRepository
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.ssh.SshBackend
import com.openminis.app.ssh.SshAuthType
import com.openminis.app.ssh.SshConfigStore
import com.openminis.app.ssh.SshCredentials
import com.openminis.app.ssh.SshEngine
import com.openminis.app.ssh.SshFailure
import com.openminis.app.ssh.SshHostConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * [T-ssh-backend] Agent-facing surface of the SSH remote execution backend.
 *
 * Actions cover the full lifecycle: host config (add_host / remove_host /
 * list_hosts / forget_host_key), connectivity probe (test), remote command
 * execution (exec) and SFTP transfers (ls / get / put).
 *
 * Secret hygiene — the point the whole split storage exists for:
 * - credentials are accepted as literals, `env:VAR` references resolved via
 *   EnvVarRepository (keeping the value OUT of chat history), or a guest
 *   `private_key_path` read through the session sandbox resolver;
 * - stored material goes to the keystore-backed box, metadata stays clean;
 * - no action ever echoes a secret back into tool output.
 *
 * Local paths are GUEST paths (/var/minis/...) resolved to host Files via
 * PRootKernel before JSch (which runs in the host app process) sees them —
 * the same resolver file tools use, so sandbox escape rules apply.
 *
 * Blocking JSch I/O is confined to [route] and runs on Dispatchers.IO.
 */
object SshTool {

    const val NAME = "ssh_exec"

    private val ACTIONS = listOf(
        "list_hosts", "add_host", "remove_host", "forget_host_key",
        "test", "exec", "ls", "get", "put",
    )
    private val READ_ONLY_ACTIONS = setOf("list_hosts", "ls", "test")
    private const val DEFAULT_TIMEOUT_S = 60L
    private const val MAX_TIMEOUT_S = 900L
    private const val MAX_KEY_BYTES = 256 * 1024

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Remote SSH execution backend — run commands and transfer files on remote hosts over SSH/SFTP. " +
            "Manage hosts (add_host/remove_host/list_hosts/forget_host_key), probe connectivity (test), execute " +
            "remote commands (exec, with optional cwd and timeout), list remote directories (ls), download (get) " +
            "and upload (put) between the session sandbox and the remote host. Credentials are stored " +
            "keystore-encrypted and never echoed; host keys are pinned trust-on-first-use and a CHANGED key aborts " +
            "the connection. Local paths are guest paths like /var/minis/workspace/file.",
        parameters = mapOf(
            "action" to AgentToolParam(
                "string",
                "One of: list_hosts, add_host, remove_host, forget_host_key, test, exec, ls, get, put.",
                enumValues = ACTIONS,
            ),
            "host" to AgentToolParam("string", "Host id or name (all actions except list_hosts/add_host)."),
            // add_host
            "name" to AgentToolParam("string", "New host's name, letters/digits/._- (add_host)."),
            "hostname" to AgentToolParam("string", "Remote hostname or IP (add_host)."),
            "port" to AgentToolParam("integer", "SSH port, default 22 (add_host)."),
            "username" to AgentToolParam("string", "Remote username (add_host)."),
            "auth_type" to AgentToolParam(
                "string", "password | private_key (add_host).",
                enumValues = listOf("password", "private_key"),
            ),
            "password" to AgentToolParam(
                "string",
                "Password literal or env:VAR_NAME reference resolved from Environment Variables (add_host, auth_type=password). Prefer env: — literals stay in chat history.",
            ),
            "private_key" to AgentToolParam(
                "string",
                "Inline PEM private key or env:VAR_NAME (add_host, auth_type=private_key). Prefer private_key_path or env: over inline.",
            ),
            "private_key_path" to AgentToolParam(
                "string",
                "Guest path (e.g. /var/minis/workspace/id_ed25519) of a PEM key file to import (add_host, auth_type=private_key).",
            ),
            "passphrase" to AgentToolParam("string", "Private key passphrase, literal or env:VAR_NAME (add_host, optional)."),
            // exec
            "command" to AgentToolParam("string", "Remote shell command (exec)."),
            "cwd" to AgentToolParam("string", "Remote working directory; wrapped as a quoted cd prefix (exec, optional)."),
            "timeout" to AgentToolParam("integer", "Exec timeout in seconds, default 60, max 900 (exec)."),
            // sftp
            "remote_path" to AgentToolParam("string", "Remote path (ls/get/put)."),
            "local_path" to AgentToolParam("string", "Guest path under /var/minis/ (get: destination, put: source)."),
        ),
        required = listOf("action"),
        propertyOrdering = listOf(
            "action", "host", "name", "hostname", "port", "username", "auth_type",
            "password", "private_key", "private_key_path", "passphrase",
            "command", "cwd", "timeout", "remote_path", "local_path",
        ),
    )

    /** Production entry point (dispatched from ChatViewModelExecuteToolExt). */
    suspend fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            val backend = SshEngine.backend(context)
            val env = EnvVarRepository(context)
            route(
                argsJson = argsJson,
                backend = backend,
                pathResolver = { guestPath -> PRootKernel.resolveSessionHostPath(sessionId, guestPath, context) },
                envResolver = { name -> env.getValue(name) },
            )
        }

    /**
     * Pure routing/formatting core — no Android types cross this boundary
     * except through the injected [pathResolver]/[envResolver], so tests
     * drive it with a fake backend and temp-dir resolver.
     */
    fun route(
        argsJson: String,
        backend: SshBackend,
        pathResolver: (String) -> File?,
        envResolver: (String) -> String?,
    ): ToolExecutionResult {
        val args = try {
            JSONObject(argsJson)
        } catch (e: Exception) {
            return invalid("arguments must be a JSON object: ${e.message}")
        }
        val action = args.optString("action", "").trim()
        if (action !in ACTIONS) {
            return invalid("'action' must be one of: ${ACTIONS.joinToString(", ")} (got '${action}')")
        }
        val hostRef = args.optString("host", "").trim()
        val title = "ssh $action${if (hostRef.isNotEmpty()) ":$hostRef" else ""}"

        fun needHost(): ToolExecutionResult? =
            if (hostRef.isEmpty()) invalid("'host' (id or name) is required for action=$action") else null

        return try {
            when (action) {
                "list_hosts" -> listHosts(backend)

                "add_host" -> addHost(args, backend, pathResolver, envResolver)

                "remove_host" -> needHost() ?: run {
                    val removed = backend.removeHost(hostRef)
                    if (removed) ToolExecutionResult("Removed SSH host '$hostRef' (credentials and pinned host key deleted).", true, toolTitle = title)
                    else notFound(hostRef)
                }

                "forget_host_key" -> needHost() ?: run {
                    backend.forgetHostKey(hostRef)
                    ToolExecutionResult(
                        "Forgot the pinned host key for '$hostRef'. The next connection re-pins trust-on-first-use; verify the new fingerprint out-of-band if this host is security-sensitive.",
                        true, toolTitle = title,
                    )
                }

                "test" -> needHost() ?: run {
                    val summary = backend.test(hostRef)
                    ToolExecutionResult(summary, true, toolTitle = title)
                }

                "exec" -> needHost() ?: exec(args, backend, hostRef, title)

                "ls" -> needHost() ?: ls(args, backend, hostRef, title)

                "get" -> needHost() ?: transfer(args, backend, hostRef, title, pathResolver, upload = false)

                "put" -> needHost() ?: transfer(args, backend, hostRef, title, pathResolver, upload = true)

                else -> invalid("unsupported action '$action'")
            }
        } catch (f: SshFailure) {
            failure(f, title)
        } catch (e: Exception) {
            ToolExecutionResult(
                "SSH ${action} failed: ${e.javaClass.simpleName}: ${e.message}",
                false,
                errorCode = ToolErrorCode.EXECUTION_FAILED,
                recoveryHint = "Retry once; if it persists run action=test to probe connectivity and auth.",
                toolTitle = title,
            )
        }
    }

    // ------------------------------------------------------------ actions

    private fun listHosts(backend: SshBackend): ToolExecutionResult {
        val hosts = backend.store.list()
        val body = if (hosts.isEmpty()) {
            "No SSH hosts configured. Add one with action=add_host " +
                "(name, hostname, username, auth_type=password|private_key, plus password / private_key_path)."
        } else {
            hosts.joinToString("\n") { it.describe() }
        }
        return ToolExecutionResult(body, true, toolTitle = "ssh list_hosts")
    }

    private fun addHost(
        args: JSONObject,
        backend: SshBackend,
        pathResolver: (String) -> File?,
        envResolver: (String) -> String?,
    ): ToolExecutionResult {
        val name = args.optString("name", "").trim()
        val hostname = args.optString("hostname", "").trim()
        val port = args.optInt("port", 22)
        val username = args.optString("username", "").trim()
        val authType = SshAuthType.parse(args.optString("auth_type", ""))
            ?: return invalid("'auth_type' must be password or private_key for action=add_host")

        val passphrase = resolveSecret(args, "passphrase", envResolver)
        val creds = when (authType) {
            SshAuthType.PASSWORD -> {
                val pw = resolveSecret(args, "password", envResolver)
                    ?: return invalid("'password' (literal or env:VAR_NAME) is required for auth_type=password")
                SshCredentials(password = pw, passphrase = passphrase)
            }
            SshAuthType.PRIVATE_KEY -> {
                val keyPath = args.optString("private_key_path", "").trim()
                val pem = if (keyPath.isNotEmpty()) {
                    val f = pathResolver(keyPath)
                        ?: return invalid("private_key_path '$keyPath' does not resolve inside this session's sandbox")
                    if (!f.isFile) return invalid("private_key_path '$keyPath' is not a readable file")
                    if (f.length() > MAX_KEY_BYTES) return invalid("private_key_path too large (${f.length()} bytes, max $MAX_KEY_BYTES)")
                    runCatching { f.readText() }.getOrNull()
                        ?: return invalid("private_key_path '$keyPath' could not be read")
                } else {
                    resolveSecret(args, "private_key", envResolver)
                        ?: return invalid("'private_key' / 'private_key_path' (or env:VAR_NAME) is required for auth_type=private_key")
                }
                if (!pem.contains("PRIVATE KEY")) {
                    return invalid("'private_key' does not look like PEM key material (no 'PRIVATE KEY' marker)")
                }
                SshCredentials(privateKeyPem = pem, passphrase = passphrase)
            }
        }

        val store = backend.store
        val taken = store.list().map { it.id }.toSet()
        val id = SshConfigStore.newId(name, taken)
        val config = SshHostConfig(
            id = id,
            name = name,
            host = hostname,
            port = port,
            username = username,
            authType = authType,
            createdAt = System.currentTimeMillis(),
        )
        // put() validates and throws SshFailure.Config on bad input.
        store.put(config, creds)
        return ToolExecutionResult(
            "Added SSH host ${config.describe()}\n" +
                "Credentials stored encrypted; they are never echoed. Run action=test to verify connectivity " +
                "(the first connect pins the host key, TOFU).",
            true,
            toolTitle = "ssh add_host:$name",
        )
    }

    private fun exec(args: JSONObject, backend: SshBackend, hostRef: String, title: String): ToolExecutionResult {
        val command = args.optString("command", "")
        if (command.isBlank()) return invalid("'command' is required for action=exec")
        val timeoutS = args.optLong("timeout", DEFAULT_TIMEOUT_S).coerceIn(1, MAX_TIMEOUT_S)
        val cwd = args.optString("cwd", "").trim().ifEmpty { null }

        val r = backend.exec(hostRef, command, timeoutS * 1000, cwd)
        val header = buildString {
            append("[exit ").append(r.exitCode).append("] ").append(r.hostLabel.ifEmpty { hostRef })
            append(" · ").append(r.durationMs).append("ms")
            if (r.truncated) append(" · output truncated at ${MAX_CAPTURE_KB}KB per stream")
        }
        val body = buildString {
            appendLine(header)
            if (r.stdout.isNotEmpty()) append(r.stdout)
            if (r.stderr.isNotEmpty()) {
                if (r.stdout.isNotEmpty()) appendLine()
                appendLine("--- stderr ---")
                append(r.stderr)
            }
            if (r.stdout.isEmpty() && r.stderr.isEmpty()) appendLine("(no output)")
        }
        return if (r.timedOut) {
            ToolExecutionResult(
                body,
                false,
                errorCode = ToolErrorCode.TIMEOUT,
                recoveryHint = "Partial output above. The remote command may still be running; raise 'timeout' (max ${MAX_TIMEOUT_S}s) or re-run with nohup/& for long jobs.",
                toolTitle = title,
                timedOut = true,
            )
        } else {
            // Non-zero exit is DATA, not failure — same contract as shell_execute.
            ToolExecutionResult(body, true, toolTitle = title)
        }
    }

    private fun ls(args: JSONObject, backend: SshBackend, hostRef: String, title: String): ToolExecutionResult {
        val path = args.optString("remote_path", "").trim().ifEmpty { "." }
        val entries = backend.ls(hostRef, path)
        if (entries.isEmpty()) {
            return ToolExecutionResult("(empty) $hostRef:$path", true, toolTitle = title)
        }
        val body = entries.joinToString("\n") { e ->
            val type = if (e.isDir) "d" else "-"
            val size = if (e.isDir) "" else " ${e.size}B"
            val mtime = if (e.mtimeEpochSec > 0) " ${e.mtimeEpochSec}" else ""
            "$type ${e.name}$size$mtime"
        }
        return ToolExecutionResult("$hostRef:$path (${entries.size} entries)\n$body", true, toolTitle = title)
    }

    private fun transfer(
        args: JSONObject,
        backend: SshBackend,
        hostRef: String,
        title: String,
        pathResolver: (String) -> File?,
        upload: Boolean,
    ): ToolExecutionResult {
        val remotePath = args.optString("remote_path", "").trim()
        if (remotePath.isEmpty()) return invalid("'remote_path' is required for action=${if (upload) "put" else "get"}")
        val localGuest = args.optString("local_path", "").trim()
        if (localGuest.isEmpty()) return invalid("'local_path' (guest path under /var/minis/) is required")
        val local = pathResolver(localGuest)
            ?: return invalid("local_path '$localGuest' does not resolve inside this session's sandbox")

        return if (upload) {
            if (!local.isFile) return invalid("local_path '$localGuest' is not a readable file")
            val bytes = backend.put(hostRef, local, remotePath)
            ToolExecutionResult("Uploaded $bytes bytes: $localGuest → $hostRef:$remotePath", true, toolTitle = title)
        } else {
            val bytes = backend.get(hostRef, remotePath, local)
            ToolExecutionResult("Downloaded $bytes bytes: $hostRef:$remotePath → $localGuest", true, toolTitle = title)
        }
    }

    // ------------------------------------------------------------ helpers

    /**
     * Resolves one credential argument: `env:VAR` → envResolver, literal →
     * as-is, absent/blank → null. The env form keeps secrets out of chat
     * history (same rationale as MCP's $$VAR header references).
     */
    private fun resolveSecret(args: JSONObject, key: String, envResolver: (String) -> String?): String? {
        val raw = args.optString(key, "").trim()
        if (raw.isEmpty()) return null
        if (raw.startsWith("env:")) {
            val varName = raw.removePrefix("env:").trim()
            if (varName.isEmpty()) return null
            return envResolver(varName)
        }
        return raw
    }

    private fun invalid(msg: String): ToolExecutionResult = ToolExecutionResult(
        msg,
        false,
        errorCode = ToolErrorCode.INVALID_ARGUMENTS,
        recoveryHint = "Example: {\"action\":\"exec\",\"host\":\"myserver\",\"command\":\"uptime\"}",
        toolTitle = NAME,
    )

    private fun notFound(hostRef: String): ToolExecutionResult = ToolExecutionResult(
        "No SSH host matching '$hostRef'.",
        false,
        errorCode = ToolErrorCode.NOT_FOUND,
        recoveryHint = "Use action=list_hosts to see configured ids/names.",
        toolTitle = NAME,
    )

    /** Maps typed backend failures onto ToolErrorCode (mirrors McpNativeTool.failure). */
    fun failure(f: SshFailure, title: String): ToolExecutionResult {
        val (code, hint, timedOut) = when (f) {
            is SshFailure.UnknownHost -> Triple(
                ToolErrorCode.NOT_FOUND,
                "Use action=list_hosts to see configured hosts, or add_host to configure one.",
                false,
            )
            is SshFailure.Auth -> Triple(
                ToolErrorCode.AUTH_REQUIRED,
                "Re-run add_host with corrected credentials (password / private_key / passphrase).",
                false,
            )
            is SshFailure.HostKeyMismatch -> Triple(
                ToolErrorCode.PERMISSION_DENIED,
                "Connection aborted BEFORE auth — possible MITM. Verify out-of-band; if the server was legitimately rekeyed, run action=forget_host_key then reconnect to re-pin.",
                false,
            )
            is SshFailure.Network -> Triple(
                ToolErrorCode.NETWORK_ERROR,
                "Check hostname/port, that the server allows SSH from this network, and device connectivity.",
                false,
            )
            is SshFailure.Timeout -> Triple(
                ToolErrorCode.TIMEOUT,
                "Retry with a larger 'timeout' (max ${MAX_TIMEOUT_S}s).",
                true,
            )
            is SshFailure.Config -> Triple(ToolErrorCode.INVALID_ARGUMENTS, null, false)
            is SshFailure.Protocol -> Triple(
                ToolErrorCode.EXECUTION_FAILED,
                "Check the remote path/permissions (action=ls to probe).",
                false,
            )
        }
        return ToolExecutionResult(
            f.message ?: f.javaClass.simpleName,
            false,
            errorCode = code,
            recoveryHint = hint,
            toolTitle = title,
            timedOut = timedOut,
        )
    }

    private const val MAX_CAPTURE_KB = 200
}
