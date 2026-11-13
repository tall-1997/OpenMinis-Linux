package com.openminis.app.ui.chat

/**
 * [T-tool-title-single-source GH#344 / T-tool-card-detail-regression v337]
 * SINGLE settlement point for the human-readable tool-card label, used by the
 * collapsed row (ChatProcessCards), the detail sheet header (ChatToolDetailUI),
 * the status bar (ChatViewModelToolTurnExec), the composer chip, flat items and
 * persistence (buildTurnParts).
 *
 * Root cause of the regression the user reported after #336 shipped
 * ("read card only shows a path, not the detail; grep/glob too"): the old
 * resolver collapsed THREE meanings into ONE persisted `toolTitle` string —
 * the model's SEMANTIC `tool_title`, a streaming preview, and an
 * argument-derived summary ("Reading /full/path"). Live device evidence
 * (2026-11-13 session DB):
 *   description="Reading /var/minis/workspace/facts.txt"   <- no tool_title
 *   description="kan IAM shell page"                       <- tool_title present
 * The card's "detail" therefore depended on model whim. #334/#336 only fixed
 * "half path" -> "full path" and left the collapsed field untouched.
 *
 * v337 separates the meanings, once, for every tool:
 *   [ToolCardPresentation.title]  = SEMANTIC label (model tool_title > a prior
 *       genuine semantic title > the Title-Case friendly name). Never a path,
 *       never a pattern, never a command — no argument value may become the
 *       headline. History cards carrying a #334/#336 derived shadow self-heal.
 *   [ToolCardPresentation.detail] = full argument summary (path / pattern /
 *       command / query), rendered as a distinct secondary line. Computed only
 *       from COMPLETE args; partial/streaming JSON never fabricates detail.
 *
 * Bare snake_case names still never reach the UI (#331/#332 invariant).
 * Pure Kotlin + org.json — no Android/Context/Compose dependency — so the
 * whole ladder is JVM-unit-testable and mirrored by the CI ratchet.
 */
internal data class ToolCardPresentation(
    val title: String,
    val detail: String = "",
)

/** Semantic title only. Signature-compatible with all 16 existing callers. */
internal fun resolveToolTitle(
    toolName: String,
    argsJson: String,
    existingTitle: String = "",
): String = presentToolCard(toolName, argsJson, existingTitle).title

/** Rendering helper for history restore: same single settlement point. */
internal fun displayToolTitle(toolName: String, toolArgs: String, toolTitle: String): String =
    presentToolCard(toolName, toolArgs, toolTitle).title

/** The ONE settlement point for both meanings of a tool card. */
internal fun presentToolCard(
    toolName: String,
    argsJson: String,
    existingTitle: String = "",
): ToolCardPresentation {
    val canonical = com.openminis.app.security.ToolAliases.canonical(toolName)
    val friendly = friendlyToolTitle(canonical)
    val prior = existingTitle.trim()

    // Parse ONCE. A complete JSONObject is the only authoritative settlement
    // point; partial/streaming JSON is never used to fabricate a path detail.
    val complete: org.json.JSONObject? = try {
        org.json.JSONObject(argsJson)
    } catch (_: Exception) {
        null
    }

    // 1. The model's own semantic title is authoritative: it comes straight
    //    from the `tool_title` argument of THIS call, so it can never be a
    //    persisted #334/#336 derived shadow. Only bare identifiers, the
    //    friendly label itself, and raw paths/URLs are rejected here.
    val modelTitle = extractToolTitle(argsJson, fallback = null)?.trim()?.takeIf {
        isSemanticTitle(it, toolName, canonical, friendly)
    }

    // 2. A genuine semantic prior survives only when this args snapshot
    //    stripped tool_title (e.g. an online tool) AND the prior is not a
    //    collapsed-field-era derived shadow ("Reading /var/min"). History
    //    cards with such a shadow self-heal into the friendly label while
    //    their full args reappear in `detail` below.
    val semanticPrior = prior.takeIf {
        isSemanticTitle(it, toolName, canonical, friendly) && !isDerivedShadow(it)
    }

    val title = modelTitle ?: semanticPrior ?: friendly
    // The argument detail is INDEPENDENT of the title. Even when the model
    // supplies a semantic title the card still shows the full path/pattern in
    // its own secondary line — that is the information the user asked for.
    val detail = complete?.let { resolveToolDetail(canonical, it) }.orEmpty()
    return ToolCardPresentation(title = title, detail = detail)
}

/** Full argument summary; pure function of COMPLETE args, never a title. */
internal fun resolveToolDetail(toolName: String, json: org.json.JSONObject): String {
    fun str(key: String): String? = json.optString(key, "").trim().takeIf { it.isNotEmpty() }
    fun cap(s: String, n: Int = 160): String = if (s.length > n) s.take(n) + "…" else s
    val canonical = com.openminis.app.security.ToolAliases.canonical(toolName)
    return when (canonical) {
        "file_read", "file_write", "file_edit", "list_dir", "multi_edit" -> str("path") ?: ""
        "read_image" -> (str("path") ?: str("image_path") ?: str("prompt")) ?: ""
        "glob", "grep" -> str("pattern") ?: ""
        "shell_execute", "env_exec", "su_exec", "ssh_exec" -> str("command")?.let { cap("${'$'} $it") } ?: ""
        "execute_code" -> str("code")?.let { cap(it) } ?: ""
        "browser_use" -> listOfNotNull(str("action"), str("url")).joinToString(" ")
        "web_search" -> str("query") ?: ""
        "web_fetch" -> str("url") ?: ""
        "memory_write" -> str("content")?.let { cap(it) } ?: ""
        "memory_get" -> str("keywords") ?: ""
        "search_sessions" -> str("query") ?: ""
        "read_session" -> str("session_id") ?: ""
        "spawn_agent", "dispatch_agents", "wolfpack_run" -> str("task")?.let { cap(it) } ?: ""
        "agent_plan" -> str("title") ?: ""
        "ask_user_question" -> str("question")?.let { cap(it) } ?: ""
        "cronjob" -> str("prompt")?.let { cap(it) } ?: ""
        "ui_action" -> str("action") ?: ""
        "file_checkpoint" -> str("op") ?: ""
        else -> ""
    }.let { cap(it) }
}

/** True only for a human/model semantic label, never an argument value. */
private fun isSemanticTitle(
    s: String, toolName: String, canonical: String, friendly: String,
): Boolean =
    s.isNotEmpty() && s != toolName && s != canonical && s != friendly &&
        !looksLikeBareName(s) &&
        // A model title may legitimately begin with a verb ("Plan the fix",
        // "Search related docs"); derived-prefix self-heal belongs ONLY to
        // the persisted prior, never to the model's own words. A whole
        // path/URL as the headline is the #334 failure mode, though.
        !s.startsWith("/") && !s.startsWith("http://") && !s.startsWith("https://")

/** snake_case / lower-case identifier like `file_read`, not a readable label. */
private fun looksLikeBareName(s: String): Boolean =
    s.contains('_') && s == s.lowercase() &&
        s.all { it == '_' || it.isDigit() || (it.isLetter() && it.isLowerCase()) }

/**
 * Prior produced by the old streaming derivation — a truncated half-path
 * ("Reading /var/min"), a full derived label ("Grep resolve"), "$ ls", etc.
 *
 * Shape-based, NOT a prefix blacklist: the tail of a derived label is always
 * a MACHINE VALUE (path, glob pattern, command, single token), while a
 * model-written semantic title is natural language — a multi-word English
 * phrase ("Plan the fix") or CJK text ("Search 相关文档"). A bare prefix
 * match would silently discard those legitimate titles. History cards with
 * a real shadow self-heal into the friendly label while their full args
 * reappear in `detail`. Exported for the CI ratchet and JVM tests.
 */
internal val DERIVED_LABEL_PREFIXES = listOf(
    "Reading ", "Writing ", "Editing ", "Viewing ", "Listing ", "Glob ", "Grep ",
    "${'$'} ", "Checkpoint ", "Multi-edit ", "Run ", "browser ", "Search ", "Fetch ",
    "Memory ", "Sessions ", "Session ", "Agent ", "Plan ", "Ask ", "Schedule ", "Tap ",
)

internal fun isDerivedShadow(prior: String): Boolean {
    val head = DERIVED_LABEL_PREFIXES.firstOrNull { prior.startsWith(it) } ?: return false
    val tail = prior.removePrefix(head)
    if (tail.isEmpty()) return true
    // CJK text in the tail is a human-written title, never a derived argument.
    if (tail.any { it.code in 0x4E00..0x9FFF }) return false
    // Path / glob / URL / shell metacharacters are machine values.
    if (tail.startsWith("/") || tail.startsWith(".") || tail.startsWith("~") ||
        tail.contains('*') || tail.contains("://") ||
        tail.any { it == '|' || it == '>' || it == '&' || it == ';' }
    ) return true
    // A single ASCII token ("resolve", "v337") is an argument value or its
    // streamed prefix; "$ <command ...>" is always the old command label.
    val singleToken = !tail.contains(' ') && !tail.contains('\t')
    if (singleToken || head == "${'$'} ") return true
    // Multi-word natural-language phrase: a genuine semantic title.
    return false
}
