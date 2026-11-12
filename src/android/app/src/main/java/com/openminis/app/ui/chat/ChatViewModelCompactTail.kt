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
): List<LLMMessage> {
    if (history.isEmpty()) {
        return listOf(LLMMessage(role = LLMMessage.Role.USER, content = summaryWrappedText))
    }
    val rawCut = (history.size - tailSize).coerceAtLeast(0)
    val cut = safeTailStart(history, rawCut)
    val tail = history.subList(cut, history.size).toList()
    val start = tail.indexOfFirst { it.role == LLMMessage.Role.USER }
    if (start < 0) {
        return tail + LLMMessage(role = LLMMessage.Role.USER, content = summaryWrappedText)
    }
    val out = ArrayList<LLMMessage>(tail.size - start + 1)
    val first = tail[start]
    val firstText = if (first.content.isBlank()) {
        summaryWrappedText
    } else {
        summaryWrappedText + "\n\n" + first.content
    }
    out.add(first.copy(content = firstText))
    out.addAll(tail.subList(start + 1, tail.size))
    return out
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
