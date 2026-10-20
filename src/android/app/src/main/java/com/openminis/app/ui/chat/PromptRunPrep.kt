package com.openminis.app.ui.chat

import com.openminis.app.agent.PersonaPromptLogic
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.logging.AppLogger
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-prompt-cache-turn-context] Prepare the outbound prompt for one agent run.
 *
 * Two things have to happen before the first request:
 *  1. `agentHistory` is rebuilt from Room (the transcript authority). See
 *     [prepareRunHistory] for the stop-cleanup ordering and the
 *     historyAlreadyRebuilt skip.
 *  2. The per-turn context is computed ONCE. It is written as a hidden
 *     `context` row immediately after the latest user message, and appended to
 *     `agentHistory` in memory, so the request that goes out now and the
 *     history rebuilt from Room on the next turn are byte-identical
 *     (DSH-style durable injection). It deliberately does NOT go into the
 *     system prompt: a per-turn block there sits before the whole conversation
 *     and ends every provider's prefix cache at that byte. [applyTurnContext]
 *     is only the fallback when the row cannot be persisted (old sessions,
 *     internal goal runs, DB failure).
 *
 * Also emits the [T-prompt-cache-diag] fingerprint and the context-assembly
 * snapshot. Extracted from runAgentLoop so the oversized-file ratchet stays
 * satisfied.
 */
internal suspend fun ChatViewModel.preparePromptForRun(
    historyAlreadyRebuilt: Boolean,
    systemPrompt: String?,
) {
    prepareRunHistory(historyAlreadyRebuilt)
    val contextText = buildTurnContextFragment()
    val lastUserIndex = agentHistory.indexOfLast { PersonaPromptLogic.isUserTextTurn(it) }
    // A persisted context row sits after the user turn and maps to USER on
    // rebuild. When it is already there, injecting again would duplicate it.
    val hasPersistedContext = lastUserIndex >= 0 && agentHistory.drop(lastUserIndex + 1).any { msg ->
        msg.content.contains(TURN_CONTEXT_OPEN) ||
            msg.contentParts.any { it is AgentContentPart.Text && it.text.contains(TURN_CONTEXT_OPEN) }
    }
    val persisted = when {
        contextText == null || hasPersistedContext -> hasPersistedContext
        else -> contextText != null && persistTurnContext(contextText, lastUserIndex)
    }
    // Persisted context already lives in agentHistory; applyTurnContext skips
    // it when a context row follows the user turn. Keeping it here also covers
    // a queued prompt injected mid-run (its turn has no persisted row).
    promptCache.turnContext = contextText

    // [T-prompt-cache-diag] One line per user turn: identical fingerprints
    // across turns mean system + tools are cache-stable. Compare these when a
    // hit-rate report looks low; a changing value names the culprit region.
    AppLogger.info(
        ChatViewModel.TAG_STREAM,
        "[CacheDiag] fingerprint=${promptCacheFingerprint(systemPrompt, agentTools)} " +
            "systemChars=${systemPrompt?.length ?: 0} tools=${agentTools.size} " +
            "turnCtxChars=${contextText?.length ?: 0} history=${agentHistory.size} " +
            "persisted=$persisted",
    )
    runCatching {
        com.openminis.app.agent.ContextAssemblySnapshot.capture(
            buildString {
                append(systemPrompt.orEmpty())
                contextText?.let {
                    append("\n\n[per-turn context — attached to the latest user message]\n")
                    append(it)
                }
            },
            activeSessionId,
            context,
        )
    }
}

/**
 * Persist the wrapped context as its own hidden `context` row and mirror it
 * into the in-memory history, so the very first request already contains the
 * same bytes the next turn will rebuild. Returns false when the row cannot be
 * written (caller falls back to request-side injection).
 */
private suspend fun ChatViewModel.persistTurnContext(
    contextText: String,
    lastUserIndex: Int,
): Boolean {
    val user = agentHistory.getOrNull(lastUserIndex) ?: return false
    if (user.dbMessageId == null || activeSessionId.isBlank()) return false
    val wrapped = "$TURN_CONTEXT_OPEN\n$contextText\n$TURN_CONTEXT_CLOSE"
    val parts = JSONArray()
        .put(JSONObject().put("type", "context").put("value", wrapped))
        .toString()
    val row = runCatching {
        chatRepository.appendMessage(activeSessionId, "context", parts)
    }.getOrElse {
        AppLogger.warning(
            ChatViewModel.TAG_STREAM,
            "[CacheDiag] context row persist failed; falling back to request-side tail: ${it.message}",
        )
        return false
    }
    appendBoundedHistory(
        LLMMessage(
            role = LLMMessage.Role.USER,
            content = wrapped,
            contentParts = listOf(AgentContentPart.Text(wrapped)),
            dbMessageId = row.id,
        ),
    )
    return true
}

/**
 * [T-prompt-cache-turn-context] A context row belongs immediately after its
 * user turn; head trimming must not leave it as the first message of the
 * window. Drop any leading hidden context rows and account for them like any
 * other trimmed row.
 */
internal fun ChatViewModel.dropOrphanedContextHead(dropped: MutableList<LLMMessage>) {
    while (true) {
        val first = agentHistory.firstOrNull() ?: return
        if (TURN_CONTEXT_OPEN !in first.content) return
        dropped += agentHistory.removeAt(0)
        llmHistoryStartOffset += 1
    }
}
