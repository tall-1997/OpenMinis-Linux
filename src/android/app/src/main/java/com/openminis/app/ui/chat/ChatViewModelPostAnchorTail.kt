package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

/**
 * [T-android-postanchor-tail-budget] Hard tail window over the v2 compact
 * marker's postAnchor ("active") region.
 *
 * The marker moves only when a compaction runs. In a long agent session the
 * active region therefore grows without bound — 909 stale tool rounds were
 * re-assembled into every single request while the summary that already
 * covers them sat right above. ProgressiveTrim cannot fix this: it strips
 * thinking and stubs old tool outputs but never reduces the message count,
 * so the model kept looping inside a 900-entry tool skeleton.
 *
 * The fix mirrors zcode/codex request-side pruning: walk the newest rounds
 * backward, keep what fits [Budget.budgetTokens], and drop the OLDEST WHOLE
 * rounds. A "round" starts at a USER message that carries no tool result —
 * the same round-boundary rule [WalkBack.bounded] uses — so a tool_use and
 * its tool_result always live in the same group and are dropped together
 * (never an orphan tool_use, the pairing invariant [ChatViewModel
 * .dropOrphanedToolParts] also enforces downstream).
 *
 * Pure JVM, no ViewModel state: the result is a request-assembly artifact.
 * Nothing here touches agentHistory or the database.
 */
internal object PostAnchorTail {
    /**
     * [T-android-postanchor-tail-budget] Estimated-token ceiling for the
     * postAnchor tail after a v2 compact marker; older whole rounds are
     * dropped with a head notice. Request-assembly only, DB untouched.
     */
    internal const val BUDGET_TOKENS: Int = 60_000

    /** [T-compact-window-scaled-budget] postAnchor share of the window. */
    internal const val WINDOW_FRACTION: Double = 0.35

    /**
     * [T-compact-gate-unblinded] SINGLE source for the production tail budget.
     *
     * [applyTailBudget] = false returns [Int.MAX_VALUE] so request assembly
     * can produce the UN-BUDGETED slice for the compact gate. The gate must
     * not measure the same cap that does the dropping, or it can never fire
     * again after the first compact (the capped request sits below the
     * threshold forever while the unsummarized region keeps growing).
     */
    internal fun budgetTokensForWindow(windowTokens: Int?, applyTailBudget: Boolean = true): Int {
        if (!applyTailBudget) return Int.MAX_VALUE
        if (windowTokens == null) return BUDGET_TOKENS
        return minOf(BUDGET_TOKENS, (windowTokens * WINDOW_FRACTION).toInt())
    }


    /** Estimated-token budget for the kept postAnchor tail. */
    internal data class Budget(
        val budgetTokens: Int,
        /** Per-message estimator; production passes [WalkBack.estimateMessageTokens]. */
        val estimate: (LLMMessage) -> Int,
        /**
         * [T-postanchor-dropped-digest] Optional bounded excerpt of the rounds
         * being dropped. The summary sits BEFORE the anchor, so it cannot
         * cover these rounds; without a digest their user instructions and
         * assistant decisions disappear silently. Returning null keeps the
         * plain count notice (pure tool traffic with nothing to excerpt).
         */
        val digest: (dropped: List<LLMMessage>) -> String? = { null },
    )

    internal data class Result(
        /** Kept slice; [notice] already prepended when rounds were dropped. */
        val kept: List<LLMMessage>,
        val droppedRounds: Int,
        val droppedMessages: Int,
        val droppedTokens: Int,
    ) {
        val notice: LLMMessage? get() = kept.firstOrNull()?.takeIf { it.isDroppedRoundsNotice }
        val hasNotice: Boolean get() = notice != null
    }

    /**
     * The exact one-line notice injected at the head of the kept slice when
     * rounds were dropped. N/K are the real dropped-round count and estimated
     * token weight. Assembly-only artifact — never persisted, and it is
     * recomputed from scratch on every request so it cannot accumulate.
     *
     * [T-postanchor-truthful-notice] It used to claim "the summary above
     * covers them". That is false: the v2 anchor is the compact boundary, so
     * every post-anchor round is younger than the summary. The wording now
     * states the actual contract and points at the two real carriers (the
     * digest and the working-memory note).
     */
    internal fun droppedRoundsNotice(droppedRounds: Int, droppedTokens: Int): String =
        "[context window: $droppedRounds older rounds dropped (≈$droppedTokens tokens) — " +
            "the earlier summary does NOT cover them; see the digest and working-memory note, " +
            "and re-run a specific tool if you need exact output]"

    private val LLMMessage.isDroppedRoundsNotice: Boolean
        get() = role == LLMMessage.Role.USER &&
            content.startsWith("[context window: ") &&
            content.contains(" older rounds dropped (")

    /**
     * Apply the tail budget. The NEWEST round is always kept even when it
     * alone exceeds the budget (the model must never lose its own current
     * turn; [ProgressiveTrim.PROTECTED_RECENT_MESSAGES] relies on the same
     * invariant). With zero or one round the input is returned untouched —
     * sessions under budget are byte-identical to the previous behavior.
     */
    fun apply(postAnchor: List<LLMMessage>, budget: Budget): Result {
        if (postAnchor.isEmpty() || budget.budgetTokens <= 0) {
            return Result(postAnchor, 0, 0, 0)
        }
        val groups = splitRounds(postAnchor)
        if (groups.size <= 1) {
            return Result(postAnchor, 0, 0, 0)
        }

        // Newest-to-oldest accumulation. The newest group is unconditionally
        // accepted; each older group is kept only while the running total
        // still fits the budget. Everything older than the cutoff is dropped
        // as whole groups — a half-kept round could orphan a tool_use.
        val costs = IntArray(groups.size) { i ->
            groups[i].sumOf { msg -> budget.estimate(msg) }.coerceAtLeast(0)
        }
        var total = costs[groups.size - 1]
        var firstKept = groups.size - 1
        var i = groups.size - 2
        while (i >= 0) {
            val next = total + costs[i]
            if (next > budget.budgetTokens) break
            total = next
            firstKept = i
            i -= 1
        }
        val droppedRounds = firstKept
        if (droppedRounds == 0) {
            return Result(postAnchor, 0, 0, 0)
        }
        var droppedMessages = 0
        var droppedTokens = 0
        val dropped = ArrayList<LLMMessage>()
        for (g in 0 until droppedRounds) {
            droppedMessages += groups[g].size
            droppedTokens += costs[g]
            dropped.addAll(groups[g])
        }
        val noticeText = buildNotice(droppedRounds, droppedTokens, dropped, budget)
        val keptMessages = ArrayList<LLMMessage>(postAnchor.size - droppedMessages + 1)
        keptMessages.add(LLMMessage(
            role = LLMMessage.Role.USER,
            content = noticeText,
            contentParts = listOf(AgentContentPart.Text(noticeText)),
        ))
        for (g in firstKept until groups.size) {
            keptMessages.addAll(groups[g])
        }
        return Result(keptMessages, droppedRounds, droppedMessages, droppedTokens)
    }

    /**
     * Count notice + optional digest of the dropped rounds. The digest is
     * best-effort: a failure there must never break request assembly, so it
     * degrades to the count-only notice.
     */
    private fun buildNotice(
        droppedRounds: Int,
        droppedTokens: Int,
        dropped: List<LLMMessage>,
        budget: Budget,
    ): String {
        val base = droppedRoundsNotice(droppedRounds, droppedTokens)
        val digest = runCatching { budget.digest(dropped) }.getOrNull()
        return if (digest.isNullOrBlank()) base else base + "\n" + digest
    }

    /**
     * Split into chronological rounds. A new round starts at a USER message
     * that does NOT carry a tool result (tool results are persisted as
     * USER-role messages — see [WalkBack.bounded]'s orphan-toolcall note);
     * everything before the first such boundary forms group 0, which may be
     * the tail of the pre-anchor round (assistant tool_use first).
     */
    internal fun splitRounds(messages: List<LLMMessage>): List<List<LLMMessage>> {
        if (messages.isEmpty()) return emptyList()
        val groups = ArrayList<ArrayList<LLMMessage>>()
        var current = ArrayList<LLMMessage>()
        groups.add(current)
        for (msg in messages) {
            val startsRound = msg.role == LLMMessage.Role.USER &&
                msg.contentParts.none { it is AgentContentPart.ToolResult }
            if (startsRound && current.isNotEmpty()) {
                current = ArrayList()
                groups.add(current)
            }
            current.add(msg)
        }
        return groups
    }
}
