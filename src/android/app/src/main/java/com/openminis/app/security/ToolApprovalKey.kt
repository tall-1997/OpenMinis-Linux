package com.openminis.app.security

import org.json.JSONObject

/**
 * [#36] Approval-key construction for the session-scoped tool allowlist.
 *
 * The approval card's "always allow this session" action stores a key built
 * from the canonical tool name plus an ARGS GIST — the one argument that
 * identifies what the call touches (a shell command, a file path, an SSH
 * target). Re-calls with the same gist auto-approve; a different target asks
 * again. Tools with no recognizable argument gist collapse to the bare
 * tool-name key, which then covers every call of that tool for the session.
 *
 * Keys are in-memory only (session lifetime): they ride [com.openminis.app.service.ApprovalGate]'s
 * session maps, are migrated on draft→persisted re-keying and dropped on
 * teardown. Nothing here touches the database.
 *
 * Pure JVM — unit tests cover gist extraction and key construction directly.
 */
object ToolApprovalKey {

    /** Tools whose gist is the `command` argument. */
    private val COMMAND_TOOLS = setOf(
        "shell_exec", "shell_execute", "su_exec", "env_exec", "execute_code", "code_exec",
    )

    /** Tools whose gist is the `path` argument. */
    private val PATH_TOOLS = setOf(
        "file_read", "file_write", "file_edit", "multi_edit", "list_dir", "glob",
        "grep", "grep_source", "present_file", "file_checkpoint",
    )

    /**
     * The identifying argument of a tool call, or "" when the tool has no
     * single identifying argument (falls back to the tool-name key). Mirrors
     * the extraction shape SecurityGateImpl uses for previews, kept separate
     * so the allowlist stays a pure, independently testable unit.
     *
     * Whitespace in the gist is collapsed: an agent re-issuing the same
     * command with different alignment/indentation must still hit the
     * "always allow" grant — requiring byte-identical spacing would make
     * the third button a recurring ask for shell-heavy work.
     */
    fun gist(toolName: String, argsJson: String): String {
        if (argsJson.isBlank()) return ""
        return try {
            val a = JSONObject(argsJson)
            when {
                toolName in COMMAND_TOOLS -> a.optString("command", "").collapseWs()
                toolName in PATH_TOOLS -> a.optString("path", "").trim()
                toolName == "ssh_exec" -> buildString {
                    append(a.optString("action", "").trim())
                    val host = a.optString("host", "").trim()
                    if (host.isNotBlank()) append(' ').append(host)
                    val cmd = a.optString("command", "").collapseWs()
                    if (cmd.isNotBlank()) append(' ').append(cmd)
                }.trim()
                toolName == "browser_use" -> a.optString("action", "").trim().lowercase()
                else -> ""
            }
        } catch (_: Exception) {
            // Non-JSON args: use the raw text as the gist so two identical
            // raw-arg calls still collide on the same key.
            argsJson.trim()
        }
    }

    private fun String.collapseWs(): String = trim().replace(Regex("\\s+"), " ")

    /**
     * Allowlist key: `tool` when the gist is blank (covers every call of the
     * tool), `tool|gist` otherwise (covers this exact target only).
     */
    fun key(toolName: String, gist: String): String =
        if (gist.isBlank()) toolName else "$toolName|$gist"
}
