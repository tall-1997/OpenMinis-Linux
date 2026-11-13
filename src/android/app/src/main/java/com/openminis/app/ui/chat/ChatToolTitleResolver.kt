package com.openminis.app.ui.chat

/**
 * [T-tool-title-single-source GH#344 / T-tool-card-detail-regression] SINGLE
 * source for the human-readable tool-card title used by the collapsed row
 * (ChatProcessCards), the detail sheet header (ChatToolDetailUI), the status
 * bar (ChatViewModelToolTurnExec) and persistence (buildTurnParts).
 *
 * Design — separation of concerns + idempotent settlement:
 *  - `tool_title` is the model's SEMANTIC summary and always wins.
 *  - A derived summary ("Reading /full/path", "Grep <pattern>", "$ cmd") is a
 *    pure function of COMPLETE args and is recomputed on every call. It is a
 *    fallback for when the model omitted `tool_title`, never a fabricated
 *    replacement for it.
 *  - STREAMING / PARTIAL args NEVER yield a derived summary: #334 derived from
 *    a half-arrived `{"path":"/va` and step 2 then trusted that "specific"
 *    prior forever, so "Reading /va" was frozen into the persisted
 *    `description` even after the full args arrived (2026-11-13 session DB:
 *    12 such shadows, each with the FULL path still present in `input`). During
 *    streaming we show only the model's `tool_title` preview or the Title-Case
 *    friendly label — both are transient and are replaced at the complete-args
 *    settlement.
 *  - COMPLETE args are the one authoritative settlement point: a prior that is
 *    itself a (possibly truncated) derived shadow yields to the fresh
 *    recomputation, so already-persisted half-path cards self-heal on render.
 *    A genuine semantic prior whose `tool_title` is absent from this args
 *    snapshot (e.g. an online tool that strips it before persistence) is kept.
 *
 * Bare snake_case names never reach the UI (#331/#332 invariant preserved).
 * Pure Kotlin + org.json — no Android/Context/Compose dependency — so the
 * whole ladder is JVM-unit-testable.
 */
internal fun resolveToolTitle(
    toolName: String,
    argsJson: String,
    existingTitle: String = "",
): String {
    val canonical = com.openminis.app.security.ToolAliases.canonical(toolName)
    val friendly = friendlyToolTitle(canonical)
    val prior = existingTitle.trim()

    // Parse ONCE. A complete JSONObject is the ONLY authoritative settlement
    // point; partial/streaming JSON is never used to fabricate a path title.
    val complete: org.json.JSONObject? = try {
        org.json.JSONObject(argsJson)
    } catch (_: Exception) {
        null
    }

    // 1. The model's own semantic title wins — from complete args or from a
    //    streaming `tool_title` preview (the model's words, not a path). A bare
    //    identifier ("file_read" — some schemas default tool_title to NAME) is
    //    not a title.
    extractToolTitle(argsJson, fallback = null)
        ?.trim()?.takeIf {
            it.isNotEmpty() && it != toolName && it != canonical && it != friendly &&
                !looksLikeBareName(it)
        }
        ?.let { return it }

    // 2. Complete args: authoritative, idempotent settlement. Recompute from
    //    the full JSON every time so a stale streaming shadow can never be
    //    frozen in.
    if (complete != null) {
        val fresh = deriveToolTitle(canonical, complete)
        val semanticPrior = prior.takeIf {
            it.isNotEmpty() && it != toolName && it != canonical && it != friendly &&
                !looksLikeBareName(it) && !isDerivedShadow(it, fresh)
        }
        return semanticPrior ?: fresh ?: friendly
    }

    // 3. Streaming / dirty JSON: NEVER derive a title from partial args. Keep
    //    a genuine semantic prior, else the Title-Case friendly label. This is
    //    a transient preview, replaced by step 2 as soon as args complete.
    val semanticPrior = prior.takeIf {
        it.isNotEmpty() && it != toolName && it != canonical && it != friendly &&
            !looksLikeBareName(it)
    }
    return semanticPrior ?: friendly
}

/**
 * [T-tool-title-single-source GH#344] Rendering helper for tool cards / detail
 * headers. History restore keeps the original toolTitle (possibly a bare name
 * or a #334 half-path shadow) plus the full args JSON, so call this instead of
 * `toolTitle.ifEmpty { name }` to reconstruct a readable, self-healed title for
 * every already-persisted card too.
 */
internal fun displayToolTitle(toolName: String, toolArgs: String, toolTitle: String): String =
    resolveToolTitle(toolName, toolArgs, toolTitle)

/** snake_case / lower-case identifier like `file_read`, not a readable label. */
private fun looksLikeBareName(s: String): Boolean =
    s.contains('_') && s == s.lowercase() &&
        s.all { it == '_' || it.isDigit() || (it.isLetter() && it.isLowerCase()) }

/**
 * A prior produced by the old streaming derivation — a truncated half-path
 * ("Reading /var/min"), a full derived label, or any prefix of the fresh
 * derivation — must yield to the complete-args recompute so #334's frozen
 * shadows self-heal on history restore. A genuine model title (a Chinese
 * summary, a plain human phrase) carries none of these markers and is kept.
 * When no fresh derivation exists there is nothing authoritative to replace
 * the prior with, so it is preserved instead of being blindly discarded.
 * Labels mirror deriveToolTitle's `when` branches; keep both in sync.
 */
private val DERIVED_LABEL_PREFIXES = listOf(
    "Reading ", "Writing ", "Editing ", "Viewing ", "Listing ", "Glob ", "Grep ",
    "${'$'} ", "Checkpoint ", "Multi-edit ", "Run ", "browser ", "Search ", "Fetch ",
    "Memory ", "Sessions ", "Session ", "Agent ", "Plan ", "Ask ", "Schedule ", "Tap ",
)

private fun isDerivedShadow(prior: String, fresh: String?): Boolean =
    fresh != null && (
        DERIVED_LABEL_PREFIXES.any { prior.startsWith(it) } || fresh.startsWith(prior)
    )

/**
 * Mirror of PreviewText.summarizeToolUse, shared by every UI entry point, but
 * driven by an ALREADY-PARSED complete [org.json.JSONObject] so a half-arrived
 * value can never leak into a title. Returns null when no concrete key exists.
 */
private fun deriveToolTitle(toolName: String, json: org.json.JSONObject): String? {
    fun str(key: String): String? = json.optString(key, "").trim().takeIf { it.isNotEmpty() }
    fun cap(s: String, n: Int = 100): String = if (s.length > n) s.take(n) + "…" else s
    // Canonicalize ONCE here via the registry's single alias map
    // (shell_exec → shell_execute, ls → list_dir, describe_image → read_image,
    // find_files → glob, ...). Duplicating aliases in the `when` below would
    // recreate the "three competing sources" root cause.
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
