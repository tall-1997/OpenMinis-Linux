package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

/**
 * [T-user-text-prefix-parts-sync] Prepend [prefix] to a USER message's text
 * payload, updating BOTH `content` and `contentParts`.
 *
 * Every provider wire builder serializes `contentParts` whenever it is
 * non-empty and never reads `content` on that path (OpenAIRequestBodies user
 * branch, Anthropic content-blocks branch, Gemini parts branch). Real user
 * turns always carry parts ([ChatViewModelSendExt] fresh sends, queue
 * inject, steering, tool-result carriers), so editing `content` alone
 * silently drops the injected text from the wire while token accounting
 * still counts it — the compaction-summary class of bug. [HistoryDigest]
 * already followed the both-fields convention; this helper makes it the
 * single implementation for every injection site (v2 compact marker,
 * detached-anchor tail, harness-note fold, dropped-rounds notice).
 *
 * Image and tool parts are preserved in order; the prefix text is prepended
 * so providers render it before the original payload.
 */
internal fun prependUserText(msg: LLMMessage, prefix: String): LLMMessage {
    if (prefix.isEmpty()) return msg
    val joined = if (msg.content.isBlank()) prefix else prefix + "\n\n" + msg.content
    val parts = msg.contentParts
    return if (parts.isEmpty()) {
        msg.copy(content = joined)
    } else {
        // [T-anthropic-toolresult-first] Anthropic requires tool_result blocks
        // FIRST in a user message's content array — prepending the injected
        // text ahead of them 400s every mid-round compact whose anchor lands
        // on a tool-result carrier (the common in-loop case). The three wire
        // builders serialize parts in order with no provider-side reordering
        // for user rows, so on a carrier the text APPENDS after the tool
        // parts (the HistoryDigest/appendUserText shape, valid on all three
        // providers); on a plain text-parts message it prepends as intended.
        val hasToolResult = parts.any { it is AgentContentPart.ToolResult }
        if (hasToolResult) {
            msg.copy(
                content = joined,
                contentParts = parts + AgentContentPart.Text(prefix),
            )
        } else {
            msg.copy(
                content = joined,
                contentParts = listOf(AgentContentPart.Text(prefix)) + parts,
            )
        }
    }
}

/**
 * [T-user-text-prefix-parts-sync] Append [extra] to a USER message's text
 * payload, mirroring [prependUserText]: both `content` and `contentParts`
 * are updated so the appended text actually reaches the wire on
 * parts-carrying messages (harness-note fold-forward onto a real user turn
 * or a tool-result carrier).
 */
internal fun appendUserText(msg: LLMMessage, extra: String): LLMMessage {
    if (extra.isEmpty()) return msg
    val parts = msg.contentParts
    return if (parts.isEmpty()) {
        msg.copy(content = msg.content + "\n\n" + extra)
    } else {
        msg.copy(
            content = msg.content + "\n\n" + extra,
            contentParts = parts + AgentContentPart.Text(extra),
        )
    }
}
