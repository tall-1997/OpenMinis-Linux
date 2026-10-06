package com.openminis.app.security

/**
 * Flatten model-invented names onto the registry names OpenMinis actually runs.
 *
 * Adapted from XINCODE-Public ToolRegistry.canonicalName (GPL-3.0-or-later).
 */
object ToolAliases {
    private val MAP = mapOf(
        "shell_exec" to "shell_execute",
        "shell" to "shell_execute",
        "bash" to "shell_execute",
        "run_command" to "shell_execute",
        "search_web" to "web_search",
        "websearch" to "web_search",
        "fetch_url" to "web_fetch",
        "fetch" to "web_fetch",
        "list_directory" to "list_dir",
        "ls" to "list_dir",
        "listdir" to "list_dir",
        "grep_source" to "grep",
        "search_files" to "grep",
        "find_files" to "glob",
        "checkpoint" to "file_checkpoint",
        "rollback" to "file_checkpoint",
        "undo" to "file_checkpoint",
        "run_subagent" to "spawn_agent",
        "dispatch_agent" to "dispatch_agents",
        "save_memory" to "memory_write",
        "recall_memory" to "memory_get",
        "describe_image" to "read_image",
        "code_exec" to "execute_code",
        "AskUserQuestion" to "ask_user_question",
    )

    fun canonical(raw: String): String {
        val n = raw.trim()
        if (n.isEmpty()) return n
        MAP[n]?.let { return it }
        MAP[n.lowercase()]?.let { return it }
        return n
    }
}
