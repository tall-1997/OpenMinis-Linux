package com.openminis.app.ui.chat

import com.openminis.app.data.BPETokenizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// [#25] Context-usage breakdown — "what is the next request made of".
//
// The sheet (ContextUsageSheet.kt) shows the current session's prompt
// composition: per-block token estimates + the existing totals. This file
// owns the data side only:
//   - [ContextBreakdownMath] is a pure, JVM-testable core (estimation basis
//     matches countPartTokens: BPETokenizer.countTokens for text);
//   - [buildContextBreakdown] wires it to the ChatViewModel's existing
//     capabilities (buildSystemPrompt / PromptParts / estimate* / policy
//     totals) — nothing here re-implements prompt assembly or estimation.
//
// Accuracy note: every number is a ROUGH estimate (±10%). The system-prompt
// sections use BPETokenizer.countTokens (countPartTokens's text basis),
// while the history block reuses estimateAgentHistoryTokens (TokenScanMemo
// mixed scan + per-model calibration). The sheet states the tolerance to
// the user.

/** Semantic blocks of the next request payload, in the order the UI shows them. */
internal enum class ContextBlockKey {
    /** Identity, tool catalogue and guidance — the stable head of the system prompt. */
    SYSTEM_TOOLS,

    /** Skill + MCP disclosure fragments injected after the base prompt. */
    SKILLS_MCPS,

    /** Memory injection (project rules, GLOBAL.md levels, daily window + index). */
    MEMORY,

    /** Per-turn dynamic tail: WorldBook, learned prefs, task anchor, runtime context. */
    RUNTIME_TAIL,

    /** Goal context + live plan-board / work-report / recall injections. */
    GOAL_PLAN,

    /** The effective (compacted / trimmed) agent history window. */
    HISTORY,

    /** Composer attachments not yet sent (images count like in-history images). */
    PENDING_ATTACHMENTS,
}

/** One block of the breakdown. [tokens] is a rough estimate, [share] is vs the sum. */
internal data class ContextBlock(
    val key: ContextBlockKey,
    val tokens: Int,
    /** 0f..1f share of [ContextBreakdown.totalTokens]; 0f when the total is 0. */
    val share: Float,
)

/**
 * The full breakdown snapshot the sheet renders. [usedTokens] /
 * [windowTokens] / [maxOutputTokens] come from the existing policy values
 * (contextTokensForPolicy / currentModelContextWindow / dynamicMaxTokens)
 * and are displayed independently of the per-block sum.
 */
internal data class ContextBreakdown(
    val blocks: List<ContextBlock>,
    /** Sum of all block estimates — self-consistent by construction. */
    val totalTokens: Int,
    /** Existing "real or floor" reading: contextTokensForPolicy(). */
    val usedTokens: Int,
    /** Model context window in tokens; 0 = unknown. */
    val windowTokens: Int,
    /** Max output tokens that still fit the window (dynamicMaxTokens); 0 = unknown. */
    val maxOutputTokens: Int,
)

/**
 * Pure core of the breakdown. Takes pre-computed texts/numbers so the JVM
 * test can pin the arithmetic without a ViewModel or Android types.
 */
internal object ContextBreakdownMath {

    /**
     * Block estimates are intentionally rough; the panel labels them ±10%.
     * The test uses the same constant to prove the block sum stays inside
     * the tolerance of an independently measured whole.
     */
    const val ESTIMATE_TOLERANCE = 0.10f

    /** Estimation basis shared with countPartTokens (text branch): BPE heuristic. */
    fun sectionTokens(text: String): Int = BPETokenizer.countTokens(text)

    /**
     * Build the breakdown. Blank/zero contributors are dropped so the panel
     * never shows a 0-token row for something that is not injected at all.
     * [totalTokens] is exactly the sum of the emitted blocks, so the sheet's
     * per-block percentages always add up to 100%.
     */
    fun build(
        sections: List<Pair<ContextBlockKey, String>>,
        historyTokens: Int,
        pendingAttachmentTokens: Int,
        usedTokens: Int,
        windowTokens: Int,
        maxOutputTokens: Int,
    ): ContextBreakdown {
        val blocks = buildList {
            for ((key, text) in sections) {
                // Whitespace-only fragments are absent injections, not content:
                // the heuristic estimator would still charge them a token or
                // two, so drop them before measuring.
                if (text.isBlank()) continue
                val tokens = sectionTokens(text)
                if (tokens > 0) add(ContextBlock(key, tokens, 0f))
            }
            if (historyTokens > 0) add(ContextBlock(ContextBlockKey.HISTORY, historyTokens, 0f))
            if (pendingAttachmentTokens > 0) {
                add(ContextBlock(ContextBlockKey.PENDING_ATTACHMENTS, pendingAttachmentTokens, 0f))
            }
        }
        val total = blocks.sumOf { it.tokens }
        val resolved = if (total > 0) {
            blocks.map { it.copy(share = it.tokens.toFloat() / total) }
        } else {
            blocks
        }
        return ContextBreakdown(
            blocks = resolved,
            totalTokens = total,
            usedTokens = usedTokens,
            windowTokens = windowTokens,
            maxOutputTokens = maxOutputTokens,
        )
    }
}

/**
 * Compute the full breakdown for the sheet. Runs the existing assembly once
 * on IO (buildSystemPrompt does disk reads — the request path runs the same
 * work per request), then estimates each block:
 *   - system sections come from [buildSystemPromptWithSections]'s RETURN
 *     value — the sections of the exact build this call just ran
 *     ([T-context-breakdown-race]; the old global lastSystemSections
 *     registry let a concurrent session's build bleed into this session's
 *     numbers, and served stale sections after a failed build);
 *   - goal/plan = the PromptParts registry render (work report, lanes,
 *     jobs, plan board, recall) + the active goal's <goal_context> prompt —
 *     exactly what withGoalAndPlanContext appends to the last user message,
 *     so HISTORY (measured without the injection) + GOAL_PLAN don't overlap;
 *   - history reuses estimateAgentHistoryTokens (effective/compacted window);
 *   - pending attachments count images at the same flat 1,500 the history
 *     estimator uses. Document attachments are NOT byte-read here — that is
 *     the OOM exposure ChatViewModelAttachmentExt deliberately avoids; they
 *     start counting once they ride the history as text parts.
 */
internal suspend fun ChatViewModel.buildContextBreakdown(): ContextBreakdown = withContext(Dispatchers.IO) {
    // One assembly pass; its sections ARE this breakdown's system blocks —
    // no shared registry between build and read.
    val systemSections = buildSystemPromptWithSections().second

    val goalPrompt = runCatching {
        val manager = com.openminis.app.goal.GoalManager(chatRepository)
        val state = manager.get(activeSessionId)
        state
            ?.takeIf { com.openminis.app.goal.GoalStateMachine.canContinue(it, usefulActivity = true) }
            ?.let { com.openminis.app.goal.goalContextPrompt(it) }
    }.getOrNull()

    val goalPlanText = buildList {
        addAll(PromptParts.render(PromptParts.validate(PromptParts.of(this@buildContextBreakdown))))
        if (!goalPrompt.isNullOrBlank()) add(goalPrompt)
    }.joinToString("\n\n")

    val maxOutput = currentProvider?.let { dynamicMaxTokens(it, _lastTurnContextTokens.value) }
        ?: currentModelMaxOutputTokens
        ?: 0

    ContextBreakdownMath.build(
        sections = systemSections +
            listOf(ContextBlockKey.GOAL_PLAN to goalPlanText),
        historyTokens = estimateAgentHistoryTokens(),
        pendingAttachmentTokens = pendingAttachmentTokens(),
        usedTokens = contextTokensForPolicy(),
        windowTokens = currentModelContextWindow ?: 0,
        maxOutputTokens = maxOutput,
    )
}

/**
 * Rough token estimate for composer attachments not yet in history. Images
 * use the same flat 1,500 the history estimator assigns to
 * AgentContentPart.ImageData; documents stay 0 (see the KDoc above).
 */
private fun ChatViewModel.pendingAttachmentTokens(): Int =
    attachments.value.count { it.isImage } * 1_500
