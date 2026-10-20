package com.openminis.app.ui.chat

import com.openminis.app.agent.PersonaPromptLogic
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

/**
 * [T-prompt-cache-turn-context] Attach the per-turn context to the latest
 * user-text turn at request time.
 *
 * Why this exists: prompt caching is prefix-based. A per-turn block inside
 * the `system` message makes the first differing byte land BEFORE the whole
 * conversation, so
 *  - OpenAI / DeepSeek automatic prefix caching re-ingests every message, and
 *  - Anthropic's message-level `cache_control` breakpoints sit on prefixes
 *    that include the changed system block, so they can never hit across
 *    turns (only the tool + stable-system breakpoints do).
 *
 * Moving the block into the latest user message puts it at the tail: the
 * previous turn's request remains a prefix of this one up to that message, so
 * the system prompt, tools and the older conversation all hit.
 *
 * Request-side only: the block is never persisted, so the next turn's rebuilt
 * history is append-only up to the previous user turn (one turn's tail is
 * re-ingested, which is the price of not duplicating context rows in the DB).
 */
internal const val TURN_CONTEXT_OPEN = "<system-reminder id=\"turn-context\">"
internal const val TURN_CONTEXT_CLOSE = "</system-reminder>"

/**
 * Pure, testable injection. Appends [context] to the LAST user-text turn
 * (tool-result-only user rows are skipped — the context belongs next to the
 * instruction, not next to a tool payload). No-op when [context] is blank,
 * when there is no user-text turn, or when the marker is already present
 * (idempotent, so a caller that re-runs it cannot double-append).
 */
internal fun applyTurnContext(history: List<LLMMessage>, context: String?): List<LLMMessage> {
    if (context.isNullOrBlank()) return history
    val index = history.indexOfLast { PersonaPromptLogic.isUserTextTurn(it) }
    if (index < 0) return history
    val message = history[index]
    // A persisted `context` row directly after the user turn already carries
    // the same bytes; appending again would duplicate it.
    val persisted = history.drop(index + 1).any { msg ->
        msg.content.contains(TURN_CONTEXT_OPEN) ||
            msg.contentParts.any { it is AgentContentPart.Text && it.text.contains(TURN_CONTEXT_OPEN) }
    }
    if (persisted || message.content.contains(TURN_CONTEXT_OPEN) ||
        message.contentParts.any { it is AgentContentPart.Text && it.text.contains(TURN_CONTEXT_OPEN) }
    ) {
        return history
    }
    val block = "\n\n$TURN_CONTEXT_OPEN\n$context\n$TURN_CONTEXT_CLOSE"
    val newContent = message.content + block
    val newParts = if (message.contentParts.isEmpty()) {
        listOf(AgentContentPart.Text(newContent))
    } else {
        message.contentParts + AgentContentPart.Text(block)
    }
    return history.toMutableList().also {
        it[index] = message.copy(content = newContent, contentParts = newParts)
    }
}
