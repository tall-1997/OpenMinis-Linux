package com.openminis.app.ui.chat

/**
 * Humanize a snake_case tool name into a Title-Case label for pill headers
 * while the model's own `tool_title` arg has not yet streamed in.
 */
internal fun friendlyToolTitle(toolName: String): String = when (toolName) {
    "shell_execute" -> "Execute Shell"
    "file_read" -> "Read File"
    "file_write" -> "Write File"
    "file_edit" -> "Edit File"
    "browser_use" -> "Browse Web"
    "read_image" -> "Read Image"
    "memory_write" -> "Write Memory"
    "memory_get" -> "Read Memory"
    "web_search" -> "Search Web"
    "search_sessions" -> "Search Sessions"
    "read_session" -> "Read Session"
    "spawn_agent", "run_subagent" -> "Sub-agent"
    "ask_user_question", "AskUserQuestion" -> "Ask User"
    "cronjob" -> "Cron Job"
    "list_dir" -> "List Dir"
    "glob" -> "Glob"
    "grep", "grep_source" -> "Grep"
    "web_fetch" -> "Fetch URL"
    "multi_edit" -> "Multi Edit"
    "file_checkpoint" -> "Checkpoint"
    "shell_exec", "env_exec" -> "Execute Shell"
    "su_exec" -> "Host su"
    "dispatch_agents" -> "Dispatch Agents"
    "wolfpack_run" -> "Wolfpack"
    "agent_plan" -> "Agent Plan"
    "execute_code" -> "Execute Code"
    "invoke_skill" -> "Invoke Skill"
    "skill_manage" -> "Manage Skill"
    "ask_reasoning" -> "Ask Reasoning"
    "generate_image" -> "Generate Image"
    "generate_video" -> "Generate Video"
    "describe_image" -> "Describe Image"
    "transcribe_audio" -> "Transcribe"
    "translate_text" -> "Translate"
    "save_memory" -> "Write Memory"
    "recall_memory" -> "Read Memory"
    else -> if (toolName.startsWith("online_")) "Online Plugin" else toolName
        .split('_')
        .filter { it.isNotEmpty() }
        .joinToString(" ") { it.replaceFirstChar { ch -> ch.uppercase() } }
}
