package com.openminis.app.ssh

import android.content.Context
import android.content.SharedPreferences
import com.openminis.app.sandbox.SessionWorkspace
import com.openminis.app.tools.SshTool
import com.openminis.app.tools.ToolErrorCode
import com.openminis.app.tools.ToolExecutionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * [T-ssh-workspace] (#32) SSH workspace binding + execution routing.
 *
 * A chat session can be BOUND to one configured SSH host ("SSH workspace").
 * While bound, the agent's shell-class tools run on that remote host through
 * the existing [SshBackend] (JSch) instead of the local PRoot sandbox, and
 * file-surface tools are refused with a clean, honest error — the basic
 * version supports only remote shell commands, and pretending otherwise
 * would silently touch the wrong filesystem.
 *
 * Layers, so the interesting parts stay pure-JVM testable:
 *  - [SshWorkspaceStore]  — tiny KV abstraction; production backs it with
 *    SharedPreferences, tests with an in-memory map.
 *  - [SshWorkspaceBindings] — owner-session → host-id map over that store.
 *    Bindings live in prefs keyed by the session id (no DB migration), per
 *    the task brief. Sub-agent lanes inherit via SessionWorkspace.ownerSessionId.
 *  - [SshWorkspaceRouter] — the pure decision table: which canonical tool
 *    routes remotely, which is refused, which proceeds locally.
 *  - [SshWorkspaceOps] — the thin Android-facing interceptor the tool
 *    dispatcher calls (one line in ChatViewModelExecuteToolExt).
 *
 * Secret hygiene: this file never reads or logs credentials — auth material
 * stays inside [SshConfigStore]'s encrypted box and is only ever handed to
 * the transport inside JschSshBackend. Tool outputs reuse SshTool's
 * formatting, which never echoes secrets either.
 */

/** KV seam so the binding store is constructible on the JVM. */
interface SshWorkspaceStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
    fun all(): Map<String, String>
}

class PrefsSshWorkspaceStore(private val prefs: SharedPreferences) : SshWorkspaceStore {
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
    override fun all(): Map<String, String> {
        val out = HashMap<String, String>()
        for (e in prefs.all) (e.value as? String)?.let { out[e.key] = it }
        return out
    }
}

/** Pure test double. */
class InMemorySshWorkspaceStore : SshWorkspaceStore {
    val map = HashMap<String, String>()
    override fun getString(key: String): String? = map[key]
    override fun putString(key: String, value: String) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
    override fun all(): Map<String, String> = HashMap(map)
}

/**
 * Session → SSH host bindings. Keys are the OWNER session id (draft ids and
 * sub-agent ids resolve to their owner before lookup), values are
 * [SshHostConfig.id]s. A binding pointing at a since-deleted host is
 * reported, never silently ignored — see [SshWorkspaceOps].
 */
class SshWorkspaceBindings(private val store: SshWorkspaceStore) {

    fun hostIdFor(ownerSessionId: String): String? {
        if (ownerSessionId.isBlank()) return null
        return store.getString(bindingKey(ownerSessionId))?.takeIf { it.isNotBlank() }
    }

    fun bind(ownerSessionId: String, hostId: String) {
        val owner = ownerSessionId.trim()
        val host = hostId.trim()
        if (owner.isEmpty() || host.isEmpty()) return
        if (!SessionWorkspace.isSafeId(owner)) return
        store.putString(bindingKey(owner), host)
    }

    fun unbind(ownerSessionId: String) {
        if (ownerSessionId.isBlank()) return
        store.remove(bindingKey(ownerSessionId))
    }

    /**
     * Drop every binding that points at [hostId] — called when the host is
     * removed. A binding left dangling would route shells at a host the user
     * has deleted (failing with a model-facing hint) while the binding sheet
     * claimed the session was working locally; the class promise above says
     * binding state is "reported, never silently ignored", and sweeping at
     * removal is the only place that promise can be kept honestly.
     */
    fun unbindAllForHost(hostId: String) {
        val target = hostId.trim()
        if (target.isEmpty()) return
        for ((owner, bound) in all()) {
            if (bound == target) store.remove(bindingKey(owner))
        }
    }

    /** Every binding, oldest-first by key — for the management UI. */
    fun all(): Map<String, String> =
        store.all().filterKeys { it.startsWith(KEY_PREFIX) }
            .mapKeys { it.key.removePrefix(KEY_PREFIX) }

    companion object {
        const val PREFS_NAME = "ssh_workspace_prefs"
        private const val KEY_PREFIX = "bind."

        fun bindingKey(ownerSessionId: String): String = "$KEY_PREFIX$ownerSessionId"

        fun forContext(context: Context): SshWorkspaceBindings {
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return SshWorkspaceBindings(PrefsSshWorkspaceStore(prefs))
        }
    }
}

/**
 * The pure routing table. Given the canonical tool name and the session's
 * bound host id (null = not an SSH workspace), decide what happens to the
 * call. No Android types, no I/O — [SshWorkspaceRouterTest] drives this.
 */
object SshWorkspaceRouter {

    /** Tools whose whole job is running a shell command — routed remotely. */
    val SHELL_TOOLS: Set<String> = setOf("shell_execute", "env_exec")

    /**
     * File-surface tools that read/write the LOCAL sandbox filesystem. In an
     * SSH workspace they would operate on the phone's PRoot tree while the
     * agent believes it is working on the remote host — refused, never
     * downgraded silently.
     */
    val UNSUPPORTED_FILE_TOOLS: Set<String> = setOf(
        "file_read", "file_write", "file_edit", "multi_edit",
        "list_dir", "grep", "glob", "read_image", "file_checkpoint",
    )

    /**
     * Host-Android privileged shell. It cannot run on a remote Linux box
     * (no Android su there) and must not leak onto the local one by
     * accident, so an SSH workspace refuses it explicitly.
     */
    val HOST_ONLY_TOOLS: Set<String> = setOf("su_exec")

    /**
     * Terminal PTY sessions open on the LOCAL PRoot sandbox no matter where
     * the shell tools route. Leaving it out of this table would let an
     * agent (and the user watching its terminal) believe they are looking
     * at the remote host while the PTY runs on the phone — the exact
     * silent-environment-mismatch this router exists to prevent.
     */
    val LOCAL_TERMINAL_TOOLS: Set<String> = setOf("agent_terminal")

    sealed class Decision {
        /** No binding, or a tool that is meaningful locally. Run as usual. */
        object Local : Decision()

        /** Run the shell command on the bound remote host. */
        data class RouteRemote(val hostId: String) : Decision()

        /** Refuse with the clean "shell commands only" boundary error. */
        data class Unsupported(val tool: String) : Decision()
    }

    fun decide(canonicalTool: String, boundHostId: String?): Decision {
        val host = boundHostId?.trim().orEmpty()
        if (host.isEmpty()) return Decision.Local
        return when {
            canonicalTool in SHELL_TOOLS -> Decision.RouteRemote(host)
            canonicalTool in UNSUPPORTED_FILE_TOOLS -> Decision.Unsupported(canonicalTool)
            canonicalTool in HOST_ONLY_TOOLS -> Decision.Unsupported(canonicalTool)
            canonicalTool in LOCAL_TERMINAL_TOOLS -> Decision.Unsupported(canonicalTool)
            else -> Decision.Local
        }
    }
}

/**
 * Android-facing interceptor. ONE call site: the tool dispatcher in
 * ChatViewModelExecuteToolExt feeds every canonical tool name through
 * [intercept] right before its own `when`; a non-null result replaces the
 * local pipeline for that call.
 */
object SshWorkspaceOps {

    /**
     * Returns a [ToolExecutionResult] when this call is handled by the SSH
     * workspace (remote exec or a refusal), or null when the local pipeline
     * should proceed unchanged.
     */
    suspend fun intercept(
        canonical: String,
        argsJson: String,
        sessionId: String?,
        context: Context,
    ): ToolExecutionResult? {
        if (sessionId.isNullOrEmpty()) return null
        val owner = SessionWorkspace.ownerSessionId(sessionId)
        val hostId = SshWorkspaceBindings.forContext(context).hostIdFor(owner)
            ?: return null
        return when (val d = SshWorkspaceRouter.decide(canonical, hostId)) {
            is SshWorkspaceRouter.Decision.Local -> null
            is SshWorkspaceRouter.Decision.Unsupported -> unsupportedResult(context, d.tool)
            is SshWorkspaceRouter.Decision.RouteRemote ->
                routeShellRemotely(argsJson, d.hostId, canonical, context)
        }
    }

    /**
     * Run the shell command on the remote host. Formatting, timeout
     * handling, typed failure mapping and the non-zero-exit-is-data contract
     * are SshTool's — we assemble the same `action=exec` payload that tool
     * would have produced and reuse its pure [SshTool.route] core, so an
     * SSH-workspace shell call looks exactly like an explicit ssh_exec call
     * to the model.
     */
    private suspend fun routeShellRemotely(
        argsJson: String,
        hostId: String,
        canonical: String,
        context: Context,
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        val args = runCatching { JSONObject(argsJson) }.getOrDefault(JSONObject())
        val payload = JSONObject()
            .put("action", "exec")
            .put("host", hostId)
            .put("command", args.optString("command", ""))
        val timeoutS = args.optInt("timeout", -1)
        if (timeoutS > 0) payload.put("timeout", timeoutS)
        val cwd = args.optString("cwd", "").trim()
        if (cwd.isNotEmpty()) payload.put("cwd", cwd)
        val backend = SshEngine.backend(context)
        val result = SshTool.route(
            argsJson = payload.toString(),
            backend = backend,
            // The remote path never resolves through the local guest jail;
            // exec does not need the resolver (only get/put do, unsupported here).
            pathResolver = { null },
            envResolver = { null },
        )
        // Keep the caller's tool title (shell_execute / env_exec) so the
        // chat UI labels the block the way the model named it, not as
        // "ssh exec:…". The ssh routing detail stays visible in the output
        // header SshTool produces (host label + exit code + duration).
        result.copy(
            toolTitle = args.optString("tool_title", "").ifBlank { canonical },
        )
    }

    /** The honest boundary error for file-surface / host-only tools. */
    private fun unsupportedResult(context: Context, tool: String): ToolExecutionResult {
        val body = context.getString(
            com.openminis.app.R.string.ssh_workspace_shell_only,
            tool,
        )
        return ToolExecutionResult(
            output = body,
            success = false,
            errorCode = ToolErrorCode.UNSUPPORTED,
            recoveryHint = context.getString(
                com.openminis.app.R.string.ssh_workspace_shell_only_hint,
            ),
            toolTitle = tool,
        )
    }
}
