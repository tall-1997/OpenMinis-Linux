package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.repository.PreviewText
import com.openminis.app.security.ToolAliases

/**
 * [T-compaction-cascade] Request-time context pipeline: tier-① LOCAL
 * micro-prune of old whitelisted tool results + untrusted-tag neutralization
 * for remote-content tool results — no LLM call, no persisted-row mutation.
 *
 * The reference harnesses all run a cheap tier BEFORE paying for a full LLM
 * compaction: OpenHarness `auto_compact_if_needed` (microcompact clears old
 * tool results with a COMPACTABLE_TOOLS whitelist, keeping the recent 5),
 * ZCode `microcompact` (local projection of old Bash outputs), dsh
 * `compaction-tool-result-pruner`. The pressure THRESHOLD is derived from the
 * operator's compact percent ([ChatViewModel.effectiveCompactPercent]) — one
 * formula family for every tier, so the cheap tier always fires BEFORE the
 * compaction gate instead of drifting from it (ZCode compact/policy.ts).
 *
 * Contracts:
 *  - Under the trigger the history passes through UNCHANGED (same instance) —
 *    byte-stable requests keep provider prefix caches warm.
 *  - Recency: the most recent [MICRO_PRUNE_KEEP_RECENT] prunable tool-result
 *    carriers stay VERBATIM; older ones are slimmed to head + marker + tail.
 *  - Whitelist: only tools whose output is re-runnable/re-fetchable
 *    ([MICRO_PRUNABLE_TOOLS], matched through [ToolAliases.canonical]).
 *    file_read is deliberately EXCLUDED — its verbatim content is what the
 *    model crafts file_edit old_string/new_string against (the on-disk
 *    read cache is fingerprint-based, but losing the text still degrades
 *    edit quality); ToolResultAging covers its bulk separately.
 *  - Structure: tool_use/tool_result pairing is never touched — only the
 *    text `content` of a prunable ToolResult part is replaced, so every
 *    provider still sees a balanced conversation. Idempotent.
 *  - The pressure reading is [ChatViewModel.contextTokensForPolicy] — the
 *    "real or calibrated floor" number the compaction gate itself uses, so a
 *    freshly-resumed session (no usage report yet) is still covered.
 */
internal const val MICRO_PRUNE_KEEP_RECENT = 4
internal const val MICRO_PRUNE_HEAD_CHARS = 400
internal const val MICRO_PRUNE_TAIL_CHARS = 400

/**
 * [T-threshold-formula-family] The micro-prune trigger sits at this fraction
 * of the compaction percent (0.75 default → 0.60 trigger), so raising the
 * operator's compact percent raises the prune point with it instead of the
 * two tiers drifting apart across hardcoded constants.
 */
internal const val MICRO_TRIGGER_FRACTION_OF_COMPACT = 0.8

/**
 * Tools whose old outputs are safe to slim: re-runnable or re-fetchable, and
 * bulky enough to matter. Matched through [ToolAliases.canonical] so history
 * aliases (shell_exec) reach the same verdict as their canonical name.
 */
internal val MICRO_PRUNABLE_TOOLS: Set<String> = setOf(
    "shell_execute",
    "web_fetch",
    "web_search",
    "browser_use",
    "execute_code",
    "grep",
)

private fun prunableTool(name: String): Boolean =
    ToolAliases.canonical(name) in MICRO_PRUNABLE_TOOLS

/**
 * [T-untrusted-tag-neutralization] Tools whose results carry REMOTE content
 * (fetched pages, search results, browser snapshots, MCP servers). deer-flow
 * sanitizes exactly this class: a page can contain literal
 * `<system-reminder>` / `[MEMORY_REFERENCE` markup, and a tool result is a
 * USER-role message — unescaped, it spoofs harness framing (bubbles, fold
 * classification, authority envelopes). Framework text itself is never
 * touched; only remote tool output is.
 */
internal val REMOTE_CONTENT_TOOLS: Set<String> = setOf(
    "web_fetch",
    "web_search",
    "browser_use",
    "mcp",
    "ssh_exec",
)

private fun remoteContentTool(name: String): Boolean =
    ToolAliases.canonical(name) in REMOTE_CONTENT_TOOLS

/**
 * Escapes the framework-reserved markers inside untrusted text (deer-flow
 * `neutralize_untrusted_tags`): harness framing that survives verbatim would
 * let remote content impersonate the host — the MODEL reads raw text, so
 * mid-line occurrences spoof exactly as hard as line-leading ones.
 *
 * Two escape classes:
 *  - AUTHORITY envelopes (`[MEMORY_REFERENCE` / `[STANDING_RULES`) escape
 *    ANYWHERE — a forged authority envelope is a standing-instruction-grade
 *    spoof, and byte-exact preservation of a forged anchor has no legitimate
 *    reader. (Content that legitimately quotes them survives via the fullwidth
 *    bracket being visually near-identical; re-filing exact bytes over HTTP is
 *    the documented trade-off.)
 *  - The other classifier markers escape at LINE START only (leading-match is
 *    what the fold classifier checks; mid-line bytes stay intact for content
 *    the user may fetch and re-file).
 *
 * Order matters: MARKERS first, TAGS second. GOAL's marker text embeds
 * `<goal_context>` — the tag loop would escape it first and the marker match
 * would silently never fire. Marker escapes produce no `<`, so the tag loop
 * still clears residual tags; both passes are idempotent.
 */
internal fun neutralizeUntrustedTags(text: String): String {
    var out = text
    // Bracket/text MARKERS — the classifier's own marker table (shared
    // constants with [HarnessNoteMarkers.ALL] and the legacy host-note head,
    // so the escape list can never drift from the classify list). Authority
    // envelopes escape globally; the rest at line start.
    for (marker in HarnessNoteMarkers.ALL + PreviewText.HOST_NOTE_NO_REPLY_HEAD) {
        if (marker == "[MEMORY_REFERENCE" || marker == "[STANDING_RULES") {
            // Authority envelopes escape ANYWHERE (global replace).
            out = out.replace(marker, "［" + marker.substring(1))
        } else {
            out = LINE_START_NEUTRALIZERS[marker]!!.let { rx ->
                rx.replace(out) { m -> m.value.take(1) + "\u200B" + m.value.substring(1) }
            }
        }
    }
    // Framework TAGS — case-insensitive, anywhere (inline blocks spoof just as
    // hard as line-leading ones). Runs LAST: marker escapes produce no `<`, so
    // residual tags still clear.
    for (tag in listOf("system-reminder", "context-summary", "goal_context")) {
        out = TAG_NEUTRALIZERS[tag]!!.replace(out) { m -> "&lt;" + m.groupValues[1] }
    }
    return out
}

/** Hoisted per-marker regexes ([T-no-loop-regex]): compiled once, reused per request. */
private val LINE_START_NEUTRALIZERS: Map<String, Regex> = mapOf(
    HarnessNoteMarkers.RECALL to Regex("^" + Regex.escape(HarnessNoteMarkers.RECALL), RegexOption.MULTILINE),
    HarnessNoteMarkers.GOAL to Regex("^" + Regex.escape(HarnessNoteMarkers.GOAL), RegexOption.MULTILINE),
    HarnessNoteMarkers.STATE to Regex("^" + Regex.escape(HarnessNoteMarkers.STATE), RegexOption.MULTILINE),
    HarnessNoteMarkers.WORKING_MEMORY to Regex("^" + Regex.escape(HarnessNoteMarkers.WORKING_MEMORY), RegexOption.MULTILINE),
    HarnessNoteMarkers.PLAN_RETRY to Regex("^" + Regex.escape(HarnessNoteMarkers.PLAN_RETRY), RegexOption.MULTILINE),
    HarnessNoteMarkers.DROPPED_ROUNDS to Regex("^" + Regex.escape(HarnessNoteMarkers.DROPPED_ROUNDS), RegexOption.MULTILINE),
    PreviewText.HOST_NOTE_NO_REPLY_HEAD to Regex("^" + Regex.escape(PreviewText.HOST_NOTE_NO_REPLY_HEAD), RegexOption.MULTILINE),
)

private val TAG_NEUTRALIZERS: Map<String, Regex> = mapOf(
    "system-reminder" to Regex("<(/?system-reminder)", RegexOption.IGNORE_CASE),
    "context-summary" to Regex("<(/?context-summary)", RegexOption.IGNORE_CASE),
    "goal_context" to Regex("<(/?goal_context)", RegexOption.IGNORE_CASE),
)

/**
 * Tier-① projection. Returns [history] unchanged (same instance) when the
 * trigger does not fire — callers keep byte-stable request prefixes.
 *
 * `usedTokens` is the caller's pressure reading
 * ([ChatViewModel.contextTokensForPolicy]); `triggerRatio` the derived prune
 * point (compact percent × [MICRO_TRIGGER_FRACTION_OF_COMPACT]).
 */
internal fun microPruneOldToolResults(
    history: List<LLMMessage>,
    usedTokens: Long,
    windowTokens: Long,
    keepRecent: Int = MICRO_PRUNE_KEEP_RECENT,
    headChars: Int = MICRO_PRUNE_HEAD_CHARS,
    tailChars: Int = MICRO_PRUNE_TAIL_CHARS,
    triggerRatio: Double = MICRO_TRIGGER_FRACTION_OF_COMPACT * 0.75,
): List<LLMMessage> {
    if (windowTokens <= 0 || usedTokens <= 0) return history
    if (usedTokens < (windowTokens * triggerRatio).toLong()) return history
    // Index the prunable carriers newest-first; the newest [keepRecent] stay verbatim.
    val carrierIndexes = history.withIndex()
        .filter { (_, msg) -> msg.role == LLMMessage.Role.USER && hasPrunableResult(msg) }
        .map { it.index }
    if (carrierIndexes.size <= keepRecent) return history
    val slimTargets = carrierIndexes.dropLast(keepRecent).toHashSet()
    var changed = false
    val out = history.mapIndexed { index, msg ->
        if (index !in slimTargets) return@mapIndexed msg
        val pruned = pruneToolResultParts(msg, headChars, tailChars)
        if (pruned !== msg) changed = true
        pruned
    }
    return if (changed) out else history
}

private fun hasPrunableResult(msg: LLMMessage): Boolean =
    msg.contentParts.any { it is AgentContentPart.ToolResult && prunableTool(it.name) }

/** Slims every prunable ToolResult part of [msg]; returns [msg] untouched when nothing changed. */
private fun pruneToolResultParts(msg: LLMMessage, headChars: Int, tailChars: Int): LLMMessage {
    var changed = false
    val newParts = msg.contentParts.map { part ->
        if (part is AgentContentPart.ToolResult && prunableTool(part.name)) {
            val slimmed = slimText(part.content, headChars, tailChars)
            if (slimmed != part.content) {
                changed = true
                part.copy(content = slimmed)
            } else {
                part
            }
        } else {
            part
        }
    }
    return if (!changed) msg else msg.copy(contentParts = newParts)
}

/** Stable identifying prefix of the slim marker — the idempotence guard. */
private const val MICRO_PRUNE_MARKER_HEAD = "\n…[micro-pruned "

/** head + marker + tail; short content passes through unchanged (idempotence). */
private fun slimText(content: String, headChars: Int, tailChars: Int): String {
    // Already-slimmed content passes through unchanged: re-slicing would chew
    // the previous marker into the new head and grow the block on every pass.
    if (content.contains(MICRO_PRUNE_MARKER_HEAD)) return content
    if (content.length <= headChars + tailChars + 64) return content
    val omitted = content.length - headChars - tailChars
    val head = content.take(headChars)
    val tail = content.takeLast(tailChars)
    return "$head\n…[micro-pruned $omitted chars — re-run the tool or file_read the path to recover]…\n$tail"
}

// ── Request-prep — the loop's single pre-step entry ──────────────────────

/**
 * [T-compaction-cascade] The loop's request-history preparation:
 *  1. neutralize untrusted framework markers in remote-content tool results
 *     (EVERY request — spoofing does not care about token pressure);
 *  2. tier-① micro-prune when pressure crosses the derived trigger.
 * Both are request-time projections over the history — the persisted rows
 * stay byte-identical. The in-loop compaction guard
 * ([ChatViewModelInLoopContext.inLoopContextCheck]) remains the compaction
 * authority: it fires at the operator's compact percent and zeroes the stale
 * reading after compacting, which also re-arms this pipeline's trigger.
 */
internal fun ChatViewModel.prepareRequestHistory(stallResumeContext: String?): List<LLMMessage> {
    val win = effectiveContextWindowTokens()?.toLong() ?: 0L
    val pressure = contextTokensForPolicy().toLong()
    val triggerRatio = effectiveCompactPercent() / 100.0 * MICRO_TRIGGER_FRACTION_OF_COMPACT
    return effectiveAgentHistory()
        .let { applyStallNudge(it, stallResumeContext) }
        .let { history -> neutralizeRemoteToolResults(history) }
        .let { history ->
            microPruneOldToolResults(
                history = history,
                usedTokens = pressure,
                windowTokens = win,
                triggerRatio = triggerRatio,
            )
        }
}

/**
 * Neutralizes framework-reserved markers inside every remote-content tool
 * result (recent AND old). Returns the same instance when nothing changed.
 */
internal fun neutralizeRemoteToolResults(history: List<LLMMessage>): List<LLMMessage> {
    var changed = false
    val out = history.map { msg ->
        if (msg.role != LLMMessage.Role.USER ||
            msg.contentParts.none { it is AgentContentPart.ToolResult && remoteContentTool(it.name) }
        ) {
            msg
        } else {
            var msgChanged = false
            val parts = msg.contentParts.map { part ->
                if (part is AgentContentPart.ToolResult && remoteContentTool(part.name)) {
                    val clean = neutralizeUntrustedTags(part.content)
                    if (clean != part.content) {
                        msgChanged = true
                        part.copy(content = clean)
                    } else {
                        part
                    }
                } else {
                    part
                }
            }
            if (msgChanged) {
                changed = true
                msg.copy(contentParts = parts)
            } else {
                msg
            }
        }
    }
    return if (changed) out else history
}
