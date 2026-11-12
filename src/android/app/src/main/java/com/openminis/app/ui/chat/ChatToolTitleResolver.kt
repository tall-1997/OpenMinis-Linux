package com.openminis.app.ui.chat

/**
 * [T-tool-title-single-source GH#344] SINGLE source for the human-readable
 * tool-card title.
 *
 * #331/#332 regression: toolTitle was computed in three competing places —
 * block creation used friendlyToolTitle ("Read File"), executeTool fell back
 * to the bare canonical name when the model omitted `tool_title`, and
 * finalizePlannedCall overwrote the friendly label with that non-empty bare
 * name (`result.toolTitle.ifEmpty { old }`). The renderer had only
 * `ifEmpty { toolName }`, so every tool the model did not title collapsed to
 * raw snake_case (file_read / read_image / ...) and was persisted that way —
 * exactly what the 2026-11-12 screenshots showed next to a titled
 * shell_execute card.
 *
 * This resolver is the ONE place that turns (toolName, argsJson, existing)
 * into a title that is NEVER a bare snake_case name:
 *   1. explicit `tool_title` from args (complete JSON or streaming partial);
 *   2. a previously resolved specific title (a Chinese summary, "Reading ...");
 *   3. a summary derived from the args (path / command / query / prompt ...);
 *   4. the Title-Case friendly label.
 * Bare names and the generic friendly label are discarded at step 2 so step 3
 * can upgrade them; this also rescues history blocks persisted as "file_read".
 *
 * Pure Kotlin + org.json — no Android/Context/Compose dependency — so the
 * whole ladder is JVM-unit-testable.
 */
internal fun resolveToolTitle(
    toolName: String,
    argsJson: String,
    existingTitle: String = "",
): String {
    val friendly = friendlyToolTitle(com.openminis.app.security.ToolAliases.canonical(toolName))
    val prior = existingTitle.trim()

    // 1. The model's own title wins (complete JSON first, streaming partial next),
    //    but a bare identifier ("read_image" — some tool schemas default
    //    tool_title to NAME) is NOT a title; let the ladder derive a real one.
    extractToolTitle(argsJson, fallback = null)
        ?.trim()?.takeIf {
            it.isNotEmpty() && it != toolName && it != friendly && !looksLikeBareName(it)
        }
        ?.let { return it }

    // 2. Keep an already-specific title; discard a bare snake_case name OR the
    //    generic friendly label (both should be upgraded by step 3 when args
    //    carry a path/command). This also rescues history blocks that were
    //    persisted as "file_read" before the fix.
    if (prior.isNotEmpty() && prior != toolName && prior != friendly && !looksLikeBareName(prior)) {
        return prior
    }

    // 3. Derive a concrete summary from the args.
    deriveToolTitle(toolName, argsJson)?.let { return it }

    // 4. Title-Case fallback — never snake_case.
    return friendly
}

/**
 * [T-tool-title-single-source GH#344] Rendering helper for tool cards / detail
 * headers. History restore keeps the original toolTitle (possibly a bare name)
 * plus the full args JSON, so call this instead of `toolTitle.ifEmpty { name }`
 * to reconstruct a readable title for every already-persisted card too.
 */
internal fun displayToolTitle(toolName: String, toolArgs: String, toolTitle: String): String =
    resolveToolTitle(toolName, toolArgs, toolTitle)

/** snake_case / lower-case identifier like `file_read`, not a readable label. */
private fun looksLikeBareName(s: String): Boolean =
    s.contains('_') && s == s.lowercase() &&
        s.all { it == '_' || it.isDigit() || (it.isLetter() && it.isLowerCase()) }

/** Mirror of PreviewText.summarizeToolUse, but shared by every UI entry point. */
private fun deriveToolTitle(toolName: String, argsJson: String): String? {
    if (argsJson.isBlank()) return null
    val json = try {
        org.json.JSONObject(argsJson)
    } catch (_: Exception) {
        null
    }
    fun str(key: String): String? {
        val complete = json?.optString(key, "")?.trim()?.takeIf { it.isNotEmpty() }
        if (complete != null) return complete
        return extractPartialJsonString(key, argsJson)?.trim()?.takeIf { it.isNotEmpty() }
    }
    fun cap(s: String, n: Int = 100): String = if (s.length > n) s.take(n) + "…" else s
    // [T-tool-title-single-source GH#344] Canonicalize ONCE here via the
    // registry's single alias map (shell_exec→shell_execute, ls→list_dir,
    // describe_image→read_image, ...). Duplicating aliases in the when below
    // would recreate the "three competing sources" root cause.
    val canonicalName = com.openminis.app.security.ToolAliases.canonical(toolName)
    return when (canonicalName) {
        "file_read" -> str("path")?.let { cap("Reading $it") }
        "file_write" -> str("path")?.let { cap("Writing $it") }
        "file_edit" -> str("path")?.let { cap("Editing $it") }
        "read_image" -> (str("path") ?: str("image_path"))?.let { cap("Viewing $it") }
            ?: str("prompt")?.let { cap("Viewing: $it") }
        "list_dir" -> str("path")?.let { cap("Listing $it") }
        "file_checkpoint" -> str("op")?.let { cap("Checkpoint $it") }
        "multi_edit" -> str("path")?.let { cap("Multi-edit $it") }
        "glob" -> str("pattern")?.let { cap("Glob $it") }
        "grep" -> str("pattern")?.let { cap("Grep $it") }
        "shell_execute", "env_exec", "su_exec", "ssh_exec" -> str("command")?.let { cap("${'$'} $it") }
        "execute_code" -> str("code")?.let { cap("Run $it") }
        "browser_use" -> {
            val action = str("action") ?: return null
            val url = str("url")
            cap(if (url != null) "$action $url" else "browser $action")
        }
        "web_search" -> str("query")?.let { cap("Search $it") }
        "web_fetch" -> str("url")?.let { cap("Fetch $it") }
        "memory_write" -> str("content")?.let { cap("Memory $it") }
        "memory_get" -> str("keywords")?.let { cap("Memory $it") }
        "search_sessions" -> str("query")?.let { cap("Sessions $it") }
        "read_session" -> str("session_id")?.let { cap("Session $it") }
        "spawn_agent", "dispatch_agents", "wolfpack_run" -> str("task")?.let { cap("Agent $it") }
        "agent_plan" -> str("title")?.let { cap("Plan $it") }
        "ask_user_question" -> str("question")?.let { cap("Ask $it") }
        "cronjob" -> str("prompt")?.let { cap("Schedule $it") }
        "ui_action" -> str("action")?.let { cap("Tap $it") }
        else -> null
    }
}
