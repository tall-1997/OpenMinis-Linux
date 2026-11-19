package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

/** [T-detached-tail-budget] Hard cap on entries, independent of the token walk. */
internal const val DETACHED_TAIL_MAX_MESSAGES = 200

/** Even when the newest message alone exceeds the budget, keep this many. */
private const val DETACHED_TAIL_MIN_MESSAGES = 1

/**
 * [T-detached-tail-budget] Choose the verbatim tail by TOKENS, not by a fixed
 * message count. The old fixed 60 entries could carry ~240k tokens (60 × the
 * 16k-char spill limit) into a 128k window: the detached branch bypasses the
 * postAnchor budget, so nothing else bounded it.
 *
 * Newest-first accumulation; the newest entry is always kept even when it
 * alone exceeds the budget (the model must have its current turn).
 */
internal fun detachedTailSize(
    history: List<LLMMessage>,
    budgetTokens: Int,
    maxMessages: Int = DETACHED_TAIL_MAX_MESSAGES,
): Int {
    if (history.isEmpty()) return 0
    if (budgetTokens <= 0) return DETACHED_TAIL_MIN_MESSAGES
    // Keep 10% headroom: the legal round boundary can extend the tail a few
    // messages earlier than the raw count.
    val target = (budgetTokens.toLong() * 9 / 10).toInt().coerceAtLeast(1)
    var total = 0
    var count = 0
    var i = history.lastIndex
    while (i >= 0 && count < maxMessages) {
        val cost = WalkBack.estimateMessageTokens(history[i])
        if (count > 0 && total + cost > target) break
        total += cost
        count++
        i--
    }
    return count.coerceAtLeast(DETACHED_TAIL_MIN_MESSAGES).coerceAtMost(history.size)
}

/**
 * [T-detached-tail-budget] Tail size for the production budget: 35% of the
 * window minus the notice+digest reserve, same accounting as postAnchor.
 */
internal fun detachedTailSizeForWindow(
    history: List<LLMMessage>,
    windowTokens: Int?,
    config: com.openminis.app.agent.HarnessConfig = com.openminis.app.agent.HarnessConfig.current(),
): Int {
    val budget = PostAnchorTail.budgetTokensForWindow(windowTokens, config = config)
    // [T-harness-config] Read the SAME reserve object the postAnchor path
    // subtracts, so a config override cannot make the two paths disagree about
    // how much room the notice+digest may take.
    val tailBudget = config.effectiveTailBudget(budget)
    return detachedTailSize(history, tailBudget)
}

/**
 * [T-compact-detached-anchor][T-detached-tail-pairing] Summary + verbatim tail
 * for a marker whose anchor was evicted. The tail starts on a PAIRING-CLEAN
 * boundary (the same suffix invariant `ResidentWindow` uses), so a tool_result
 * is never cut away from its tool_use — the model otherwise sees a tool output
 * with no call and re-issues the same call next turn (the reported "repeats
 * itself and does no work" loop). The boundary may be an assistant turn; the
 * summary is then sent as its own user message before it.
 */
internal fun buildDetachedCompactHistory(
    summaryWrappedText: String,
    history: List<LLMMessage>,
    tailSize: Int,
    /** Bounded excerpt builder for the most recent dropped messages. */
    digest: ((List<LLMMessage>) -> String?)? = null,
    config: com.openminis.app.agent.HarnessConfig = com.openminis.app.agent.HarnessConfig.current(),
): List<LLMMessage> {
    if (history.isEmpty()) {
        return listOf(LLMMessage(role = LLMMessage.Role.USER, content = summaryWrappedText))
    }
    val rawCut = (history.size - tailSize).coerceAtLeast(0)
    // [T-detached-tail-pairing] A legal cut is one whose SUFFIX keeps every
    // tool_use/result pair intact. `safeTailStart` walked back to a real user
    // turn, which in a single-user autonomous run is index 0: the token
    // budget was silently defeated and the whole 300-round history shipped.
    // The legal-boundary table (same invariant ResidentWindow uses) stops at
    // the nearest assistant tool_use instead, and the summary is then sent as
    // its own user message before that assistant turn.
    val cut = legalTailStart(history, rawCut)
    val tail = history.subList(cut, history.size).toList()
    // [T-detached-drop-truth] The gap is NOT silently swallowed: the old
    // code kept the newest 60 and said nothing, so 3,000+ messages vanished
    // with no signal. The notice lives INSIDE the <context-summary> envelope
    // and carries a bounded digest of the newest dropped messages, which are
    // the ones the next step actually depends on.
    val effectiveDigest = digest ?: { dropped ->
        DroppedRoundDigest.render(dropped, DETACHED_DIGEST_HEADER, config)
    }
    val wrapped = if (cut > 0) {
        injectDetachedNotice(summaryWrappedText, cut, history, cut, effectiveDigest)
    } else {
        summaryWrappedText
    }
    val first = tail.firstOrNull()
        ?: return listOf(LLMMessage(role = LLMMessage.Role.USER, content = wrapped))
    val firstIsRealUser = first.role == LLMMessage.Role.USER &&
        first.contentParts.none {
            it is AgentContentPart.ToolResult || it is AgentContentPart.ToolUse
        }
    val out = ArrayList<LLMMessage>(tail.size + 1)
    if (firstIsRealUser) {
        out.add(prependUserText(first, wrapped))
        out.addAll(tail.subList(1, tail.size))
    } else {
        // Assistant/tool carrier first: the API requires a user first, so the
        // summary stands alone and the whole paired suffix follows.
        out.add(
            LLMMessage(
                role = LLMMessage.Role.USER,
                content = wrapped,
                contentParts = listOf(AgentContentPart.Text(wrapped)),
            ),
        )
        out.addAll(tail)
    }
    return out
}

/**
 * [T-detached-tail-pairing] Nearest cut at/below [rawCut] whose suffix is
 * pairing-clean. Returns 0 when no later cut is safe (single round).
 */
internal fun legalTailStart(history: List<LLMMessage>, rawCut: Int): Int {
    if (history.isEmpty()) return 0
    val cut = rawCut.coerceIn(0, history.lastIndex)
    val legal = com.openminis.app.ui.chat.retention.ResidentWindow.legalBoundaries(history)
    var i = cut
    while (i > 0 && !legal[i]) i--
    return if (legal[i]) i else 0
}

/** How many of the newest dropped messages the detached digest inspects. */
private const val DETACHED_DIGEST_WINDOW = 40

private const val DETACHED_DIGEST_HEADER =
    "Most recent dropped messages (excerpts; some may predate the summary boundary):"

/**
 * Put the detached-drop notice inside the summary envelope. Inserting before
 * `</context-summary>` keeps the whole block classified as background context
 * (the fold and previews strip that envelope); appending after it would make
 * the first real user turn look like a harness note and reorder it.
 */
internal fun injectDetachedNotice(
    summaryWrappedText: String,
    droppedCount: Int,
    history: List<LLMMessage>,
    cut: Int,
    digest: ((List<LLMMessage>) -> String?)?,
): String {
    val from = (cut - DETACHED_DIGEST_WINDOW).coerceAtLeast(0)
    val digestText = digest?.invoke(history.subList(from, cut))
        ?.takeIf { it.isNotBlank() }
    val notice = buildString {
        append("\n\n[detached compact boundary: ").append(droppedCount)
        append(" earlier messages are not in this request. The summary may not cover all ")
        append("of them; re-read a file or re-run a specific tool if you need exact output.]")
        if (digestText != null) append('\n').append(digestText)
    }
    val close = summaryWrappedText.lastIndexOf("</context-summary>")
    if (close < 0) return summaryWrappedText + notice
    return summaryWrappedText.substring(0, close) + notice + "\n" +
        summaryWrappedText.substring(close)
}
