package com.openminis.app.ui.chat

import com.openminis.app.agent.HarnessConfig
import com.openminis.app.data.model.LLMMessage

/** Hard cap on candidate entries before extending to a legal tool boundary. */
internal const val DETACHED_TAIL_MAX_MESSAGES = 200

internal fun detachedTailSize(
    history: List<LLMMessage>,
    budgetTokens: Int,
    maxMessages: Int = DETACHED_TAIL_MAX_MESSAGES,
): Int {
    if (history.isEmpty()) return 0
    // Leave headroom for extending the tail to the start of a paired step.
    val target = (budgetTokens.toLong() * 9 / 10).coerceAtLeast(1L)
    var total = 0L
    var count = 0
    var i = history.lastIndex
    while (i >= 0 && count < maxMessages.coerceAtLeast(1)) {
        val cost = WalkBack.estimateMessageTokens(history[i])
        if (count > 0 && total + cost > target) break
        total += cost
        count++
        i--
    }
    return count
}

internal fun detachedTailSizeForWindow(
    history: List<LLMMessage>,
    windowTokens: Int?,
    config: HarnessConfig = HarnessConfig.current(),
): Int {
    val budget = PostAnchorTail.budgetTokensForWindow(windowTokens, config = config)
    return detachedTailSize(history, config.effectiveTailBudget(budget))
}

/** Unknown coverage is never used to discard resident rows on the unbudgeted path. */
internal fun detachedTailStartForWindow(
    history: List<LLMMessage>,
    windowTokens: Int?,
    applyTailBudget: Boolean,
    config: HarnessConfig,
): Int {
    if (!applyTailBudget || history.isEmpty()) return 0
    val budget = PostAnchorTail.budgetTokensForWindow(windowTokens, config = config)
    val tailBudget = config.effectiveTailBudget(budget)
    val rawCut = history.size - detachedTailSize(history, tailBudget)
    if (rawCut == 0) return 0
    val legal = ToolPairingCore.legalCuts(history)
    fun canStartAt(index: Int): Boolean = legal[index] &&
        (index == 0 || !HarnessNoteMarkers.isUserHarnessNote(history[index]))
    val earlier = (rawCut downTo 0).firstOrNull(::canStartAt)
    if (earlier == rawCut) return rawCut
    // Do not revive an oversized old step if a newer complete suffix fits.
    // If this is the newest step, retain it whole even when it cannot fit.
    if (earlier != null && history.size - earlier <= DETACHED_TAIL_MAX_MESSAGES) {
        var total = 0L
        for (i in earlier until history.size) {
            total += WalkBack.estimateMessageTokens(history[i])
            if (total > tailBudget) break
        }
        if (total <= tailBudget) return earlier
    }
    return (rawCut + 1 until history.size).firstOrNull(::canStartAt) ?: earlier ?: 0
}

/** Summary plus a verbatim, pairing-safe suffix for an evicted anchor. */
internal fun buildDetachedCompactHistory(
    summaryWrappedText: String,
    history: List<LLMMessage>,
    tailSize: Int,
    digest: ((List<LLMMessage>) -> String?)? = null,
    config: HarnessConfig = HarnessConfig.current(),
): List<LLMMessage> = assembleDetachedCompactHistory(
    summaryWrappedText, history, legalTailStart(history, history.size - tailSize.coerceAtLeast(0)), config, digest = digest,
)

internal fun assembleDetachedCompactHistory(
    summaryWrappedText: String,
    history: List<LLMMessage>,
    cut: Int,
    config: HarnessConfig,
    recoveredNote: LLMMessage? = null,
    digest: ((List<LLMMessage>) -> String?)? = null,
): List<LLMMessage> {
    val tail = history.subList(cut, history.size)
    val wrapped = if (cut == 0) summaryWrappedText else injectDetachedNotice(
        summaryWrappedText, cut, history, cut, digest ?: { dropped ->
            DroppedRoundDigest.render(dropped, DETACHED_DIGEST_HEADER, config)
        }, config,
    )
    val attached = attachCompactSummary(tail, wrapped)
    // Fold recovered state after the summary and before any retained tool step.
    return if (recoveredNote == null) attached else
        listOf(appendUserText(attached.first(), recoveredNote.content.trimStart())) + attached.drop(1)
}

/** A detached or autonomous slice may start with an assistant/tool pair. Keep both halves. */
internal fun attachCompactSummary(history: List<LLMMessage>, summary: String): List<LLMMessage> {
    val first = history.firstOrNull()
    val out = ArrayList<LLMMessage>(history.size + 1)
    if (first != null && first.canCarryCompactSummary()) {
        out.add(prependUserText(first, summary))
        out.addAll(history.subList(1, history.size))
    } else {
        out.add(compactSummaryMessage(summary))
        out.addAll(history)
    }
    return out
}

internal fun legalTailStart(history: List<LLMMessage>, rawCut: Int): Int {
    if (rawCut <= 0 || history.isEmpty()) return 0
    return ToolPairingCore.lastLegalCutAtOrBefore(history, rawCut.coerceAtMost(history.lastIndex)) ?: 0
}

private const val DETACHED_DIGEST_WINDOW = 40
private const val DETACHED_DIGEST_HEADER =
    "Most recent dropped messages (excerpts; some may predate the summary boundary):"

/** Keep the gap notice inside the summary envelope, not on the real user turn. */
internal fun injectDetachedNotice(
    summaryWrappedText: String,
    droppedCount: Int,
    history: List<LLMMessage>,
    cut: Int,
    digest: ((List<LLMMessage>) -> String?)?,
    config: HarnessConfig = HarnessConfig.current(),
): String {
    val from = (cut - DETACHED_DIGEST_WINDOW).coerceAtLeast(0)
    val base = "[detached compact boundary: $droppedCount earlier messages are not in this request. " +
        "The summary may not cover all of them; re-read a file or re-run a specific tool if you need exact output.]"
    val notice = PostAnchorTail.withBudgetedDigest(base, history.subList(from, cut), config) { dropped ->
        digest?.invoke(dropped)?.let(::escapeCompactEnvelopeText)
    }
    val close = summaryWrappedText.lastIndexOf("</context-summary>")
    if (close < 0) return "$summaryWrappedText\n\n$notice"
    return summaryWrappedText.substring(0, close) + "\n\n$notice\n" + summaryWrappedText.substring(close)
}
