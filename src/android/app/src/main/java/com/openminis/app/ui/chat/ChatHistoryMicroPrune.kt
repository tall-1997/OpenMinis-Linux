package com.openminis.app.ui.chat

import com.openminis.app.agent.ToolResultProjection
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
 *  - Recency: the newest [MICRO_PRUNE_KEEP_RECENT] results and their complete
 *    tool rounds stay verbatim, using the same scope as tool-result aging.
 *  - Whitelist: only known bulky tool output ([MICRO_PRUNABLE_TOOLS], matched
 *    through [ToolAliases.canonical]); pruning does not authorize another call.
 *    file_read is deliberately EXCLUDED — its verbatim content is what the
 *    model crafts file_edit old_string/new_string against (the on-disk
 *    read cache is fingerprint-based, but losing the text still degrades
 *    edit quality); ToolResultAging covers its bulk separately.
 *  - Structure: tool_use/tool_result pairing is never touched — only the
 *    text `content` of a prunable ToolResult part is replaced, so every
 *    provider still sees a balanced conversation. Idempotent.
 *  - The caller supplies context pressure and the policy-derived trigger for
 *    the current request, including when no provider usage has been reported.
 */
/**
 * 6 = ToolResultAging's keep window: the two cheap layers must preserve the
 * SAME newest-results set, or aging's deliberately-kept work products get
 * slimmed by the layer that runs after it (round-3 adversarial finding).
 */
internal const val MICRO_PRUNE_KEEP_RECENT = ToolResultProjection.DEFAULT_KEEP_RECENT
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
 * Tools with typically bulky text outputs. Matched through [ToolAliases.canonical]
 * so history aliases (shell_exec) reach the same verdict as their canonical name.
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
        if (marker == HarnessNoteMarkers.MEMORY_ENVELOPE || marker == HarnessNoteMarkers.STANDING_RULES_ENVELOPE) {
            // Authority envelopes escape ANYWHERE (global replace).
            out = out.replace(marker, "［" + marker.substring(1))
        } else {
            // A marker missing from the table must never crash request
            // assembly ([P3-2 final]): skip with a log so the drift is visible
            // instead of failing every request that carries a remote result.
            val neutralizer = LINE_START_NEUTRALIZERS[marker]
            if (neutralizer == null) {
                com.openminis.app.logging.AppLogger.warning(
                    "ChatHistoryMicroPrune",
                    "no line-start neutralizer for harness marker ${marker.take(24)} — escape skipped",
                )
            } else {
                out = neutralizer.replace(out) { m -> m.value.take(1) + "\u200B" + m.value.substring(1) }
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
 * point (compact percent × [MICRO_TRIGGER_FRACTION_OF_COMPACT]). Measure it on
 * the SAME [history] instance passed here: when aging runs first
 * (projectChatRequest), the pre-aging reading is an overestimate of the aged
 * request and would re-fire the trim on requests already under the trigger
 * ([T-micro-prune-post-aging-pressure]).
 */
internal fun microPruneOldToolResults(
    history: List<LLMMessage>,
    usedTokens: Long,
    windowTokens: Long,
    keepRecent: Int = MICRO_PRUNE_KEEP_RECENT,
    headChars: Int = MICRO_PRUNE_HEAD_CHARS,
    tailChars: Int = MICRO_PRUNE_TAIL_CHARS,
    triggerTokens: Long = 0L,
    verifiedSpillPaths: Set<String> = emptySet(),
): List<LLMMessage> {
    // triggerTokens <= 0 means the window policy declines auto-compaction.
    if (windowTokens <= 0 || usedTokens <= 0 || triggerTokens <= 0 || usedTokens < triggerTokens) return history
    val protectFrom = ToolResultProjection.protectedSuffixStart(history, keepRecent)
    var projected: MutableList<LLMMessage>? = null
    for (index in 0 until protectFrom) {
        val message = history[index]
        var parts: MutableList<AgentContentPart>? = null
        for ((partIndex, part) in message.contentParts.withIndex()) {
            if (part !is AgentContentPart.ToolResult || !prunableTool(part.name)) continue
            val replacement = ToolResultProjection.replacement(
                part, ToolResultProjection.MICRO_PRUNE_MARKER, headChars, tailChars,
                verifiedSpillPaths = verifiedSpillPaths,
            ) ?: continue
            val changed = parts ?: message.contentParts.toMutableList().also { parts = it }
            changed[partIndex] = part.copy(content = replacement)
        }
        parts?.let {
            val changed = projected ?: history.toMutableList().also { projected = it }
            changed[index] = message.copy(contentParts = it)
        }
    }
    return projected ?: history
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
                    // [T-untrusted-tag-neutralization] Deterministic transform
                    // memoized per text (WeakHashMap) — 11 regex passes per
                    // remote result no longer repeat on every request.
                    val clean = TokenScanMemo.neutralized(part.content)
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
