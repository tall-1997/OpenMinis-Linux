package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

/**
 * [T-compact-detached-anchor][T-compact-tail-round-boundary] Summary + verbatim
 * tail for a marker whose anchor was evicted. The tail MUST start on a complete
 * round: `takeLast(n)` peeled to the first USER message can start on the
 * tool-result CARRIER of a round whose tool_use was just cut away — the model
 * then sees a tool output with no call and re-issues the same call next turn
 * (the reported "repeats itself and does no work" loop). Codex/opencode compact
 * on turn boundaries for exactly this reason.
 */
internal fun buildDetachedCompactHistory(
    summaryWrappedText: String,
    history: List<LLMMessage>,
    tailSize: Int,
    /** Bounded excerpt builder for the most recent dropped messages. */
    digest: ((List<LLMMessage>) -> String?)? = { dropped ->
        DroppedRoundDigest.render(dropped, DETACHED_DIGEST_HEADER)
    },
): List<LLMMessage> {
    if (history.isEmpty()) {
        return listOf(LLMMessage(role = LLMMessage.Role.USER, content = summaryWrappedText))
    }
    val rawCut = (history.size - tailSize).coerceAtLeast(0)
    val cut = safeTailStart(history, rawCut)
    val tail = history.subList(cut, history.size).toList()
    // [T-detached-drop-truth] The gap is NOT silently swallowed: the old
    // code kept the newest 60 and said nothing, so 3,000+ messages vanished
    // with no signal. The notice lives INSIDE the <context-summary> envelope
    // (so HarnessNoteMarkers still classifies the first tail message as a
    // real user turn) and carries a bounded digest of the newest dropped
    // messages, which are the ones the next step actually depends on.
    val wrapped = if (cut > 0) {
        injectDetachedNotice(summaryWrappedText, cut, history, cut, digest)
    } else {
        summaryWrappedText
    }
    return attachCompactSummary(tail, wrapped)
}

/** A detached slice may start with an assistant/tool pair. Keep both halves. */
internal fun attachCompactSummary(history: List<LLMMessage>, summary: String): List<LLMMessage> {
    val first = history.firstOrNull()
    if (first?.role != LLMMessage.Role.USER || first.contentParts.any { it is AgentContentPart.ToolResult }) {
        return listOf(LLMMessage(role = LLMMessage.Role.USER, content = summary)) + history
    }
    return listOf(prependUserText(first, summary)) + history.drop(1)
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

/**
 * [T-compact-tail-round-boundary] Walk [cut] BACK to the nearest round
 * boundary: a USER message that does not carry a tool_result. Cutting
 * at an assistant message or at a tool-result carrier orphans one half
 * of a pair, so the walk skips both and lands on the user turn that
 * opened the round. Returns 0 when the whole history is one round.
 *
 * Pure and total: `cut` is clamped, `history[cut]` is never read past
 * the end, and a boundary at index 0 always terminates the walk.
 */
internal fun safeTailStart(history: List<LLMMessage>, cut: Int): Int {
    if (history.isEmpty()) return 0
    var i = cut.coerceIn(0, history.size - 1)
    while (i > 0) {
        val candidate = history[i]
        val isRoundStart = candidate.role == LLMMessage.Role.USER &&
            candidate.contentParts.none { it is AgentContentPart.ToolResult }
        if (isRoundStart) return i
        i--
    }
    return 0
}
