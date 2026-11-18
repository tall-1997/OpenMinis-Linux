package com.openminis.app.ui.chat

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.security.ToolAliases

/**
 * [T-compaction-cascade] Tier-① of the context compaction cascade: LOCAL
 * micro-prune of old whitelisted tool results — no LLM call, request-time
 * projection only.
 *
 * The reference harnesses all run a cheap tier BEFORE paying for a full LLM
 * compaction: OpenHarness `auto_compact_if_needed` (microcompact clears old
 * tool results with a COMPACTABLE_TOOLS whitelist, keeping the recent 5),
 * ZCode `microcompact` (local projection of old Bash outputs), dsh
 * `compaction-tool-result-pruner`. Minis Ultra previously jumped straight
 * from "history growing" to the reactive overflow compaction — a guaranteed
 * overflow round trip at full cost.
 *
 * Contract:
 *  - Trigger: `usedTokens >= MICRO_PRUNE_TRIGGER_RATIO * windowTokens` (and
 *    both positive). Under the trigger the history passes through untouched —
 *    byte-stable requests keep provider prefix caches warm.
 *  - Recency: the most recent [keepRecent] prunable tool-result carriers stay
 *    VERBATIM (they anchor what the model is working on right now); older
 *    ones are slimmed to head + marker + tail.
 *  - Whitelist: only tools whose output is re-runnable/re-fetchable
 *    ([MICRO_PRUNABLE_TOOLS], matched through [ToolAliases.canonical]).
 *    file_read is deliberately EXCLUDED — its output anchors edit read-state.
 *  - Structure: tool_use/tool_result pairing is never touched — only the
 *    text `content` of a prunable ToolResult part is replaced, so every
 *    provider still sees a balanced conversation. Idempotent: a slimmed
 *    result re-matches and re-slims to the same bytes.
 */
internal const val PROACTIVE_COMPACT_RATIO = 0.80
internal const val MICRO_PRUNE_TRIGGER_RATIO = 0.60
internal const val MICRO_PRUNE_KEEP_RECENT = 4
internal const val MICRO_PRUNE_HEAD_CHARS = 400
internal const val MICRO_PRUNE_TAIL_CHARS = 400

/** [T-proactive-compact] Tier-② threshold: full LLM compaction BEFORE the next request. */
internal fun shouldProactivelyCompact(usedTokens: Long, windowTokens: Long): Boolean {
    if (windowTokens <= 0 || usedTokens <= 0) return false
    return usedTokens >= (windowTokens * PROACTIVE_COMPACT_RATIO).toLong()
}

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
    "grep_source",
)

private fun prunableTool(name: String): Boolean =
    ToolAliases.canonical(name) in MICRO_PRUNABLE_TOOLS

/**
 * Tier-① projection. Returns [history] unchanged (same instance) when the
 * trigger does not fire — callers keep byte-stable request prefixes.
 */
internal fun microPruneOldToolResults(
    history: List<LLMMessage>,
    usedTokens: Long,
    windowTokens: Long,
    keepRecent: Int = MICRO_PRUNE_KEEP_RECENT,
    headChars: Int = MICRO_PRUNE_HEAD_CHARS,
    tailChars: Int = MICRO_PRUNE_TAIL_CHARS,
): List<LLMMessage> {
    if (windowTokens <= 0 || usedTokens <= 0) return history
    if (usedTokens < (windowTokens * MICRO_PRUNE_TRIGGER_RATIO).toLong()) return history
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

// ── Tier-② / request-prep — the loop's single pre-step entry ──────────────

/**
 * [T-proactive-compact] Tier-② pre-step pressure check (dsh compaction-basic
 * pre-step / deer-flow summarization trigger / ZCode auto-full-compact /
 * OpenHarness auto_compact_if_needed): at ≥ [PROACTIVE_COMPACT_RATIO] of the
 * window, run the FULL compaction BEFORE building the next request instead of
 * paying a guaranteed-overflow round trip at full cost. The reactive overflow
 * tier stays as the safety net for what still slips through.
 *
 * Cap = the reading itself: compaction nulls [ChatViewModel.lastKnownContextUsage],
 * so the gate re-arms only after the next real usage report — one pass per
 * reading, no thrash counter needed. The session key in the same pair keeps a
 * stale reading from session A from compacting session B.
 */
internal suspend fun ChatViewModel.maybeProactiveCompact() {
    val win = effectiveContextWindowTokens()?.toLong() ?: return
    if (win <= 0) return
    val (usageSession, used) = lastKnownContextUsage ?: return
    if (usageSession != activeSessionId) return
    if (!shouldProactivelyCompact(used, win)) return
    Log.w(ChatViewModel.TAG, "[ContextCascade] proactive compact: usage=$used win=$win (tier-②, before reactive overflow)")
    withContext(Dispatchers.Main) {
        setTransientInlineError(
            context.getString(
                com.openminis.app.R.string.vm_context_filling_compacting,
                contextTokensForPolicy(),
                effectiveContextWindowTokens() ?: 0,
            ),
        )
    }
    awaitCompaction()
    withContext(Dispatchers.Main) { clearInlineError() }
    // The gate re-arms only after the next real usage report — the old reading
    // is obsolete once compacted.
    lastKnownContextUsage = null
}

/**
 * [T-compaction-cascade] The loop's request-history preparation: tier-② first
 * (the compact sees the pre-notice history and the fresh notices land on the
 * compacted base), then tier-① micro-prune as a request-time projection over
 * the history (the persisted rows stay byte-identical).
 */
internal suspend fun ChatViewModel.prepareRequestHistory(stallResumeContext: String?): List<LLMMessage> {
    maybeProactiveCompact()
    return effectiveAgentHistory()
        .let { applyStallNudge(it, stallResumeContext) }
        .let {
            microPruneOldToolResults(
                history = it,
                usedTokens = lastKnownContextUsage?.second ?: 0L,
                windowTokens = effectiveContextWindowTokens()?.toLong() ?: 0L,
            )
        }
}
