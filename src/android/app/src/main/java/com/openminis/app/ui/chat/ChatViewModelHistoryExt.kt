package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.ToolPairing

/**
 * Validate tool rounds in wire order. A result is valid only when it follows
 * an unmatched use with the same id and name; set membership alone is unsafe
 * because duplicate ids, wrong names, and result-before-use all reach providers
 * as malformed requests.
 */
internal fun dropOrphanedToolParts(history: List<LLMMessage>): List<LLMMessage> {
    data class Use(
        val id: String,
        val name: String,
        val messageIndex: Int,
        val partIndex: Int,
    )
    val pending = ArrayList<Use>()
    val unmatchedUses = ArrayList<Use>()
    val droppedResults = HashSet<Pair<Int, Int>>()

    history.forEachIndexed { messageIndex, message ->
        message.contentParts.forEachIndexed { partIndex, part ->
            when (part) {
                is AgentContentPart.ToolUse -> {
                    val use = Use(part.id, part.name, messageIndex, partIndex)
                    pending += use
                    unmatchedUses += use
                }
                is AgentContentPart.ToolResult -> {
                    // [T-android-responses-tool-id-normalize] Compare pairing
                    // keys, not raw ids: the Responses path stores
                    // "<call_id>|<fc_id>" while a model switch, a DB reload or
                    // a relay can leave the plain call_id on the other half of
                    // the pair. A raw comparison calls that a mismatch, and the
                    // repair below then deletes a REAL result and fabricates an
                    // "interrupted" error for a tool that had worked. For ids
                    // without "|" (Chat Completions, Anthropic, Gemini) the key
                    // is the identity and nothing changes.
                    val matchIndex = pending.indexOfFirst {
                        ToolPairing.key(it.id) == ToolPairing.key(part.id) && it.name == part.name
                    }
                    if (matchIndex >= 0) {
                        val use = pending.removeAt(matchIndex)
                        unmatchedUses.remove(use)
                    } else {
                        // This includes result-before-use, wrong name, and a
                        // duplicate result after the occurrence was consumed.
                        droppedResults += messageIndex to partIndex
                    }
                }
                else -> Unit
            }
        }
    }

    // An assistant tail may legitimately be awaiting execution. Only those
    // exact final-message occurrences are exempt.
    //
    // [T-orphan-use-pairing] ZCode's contract: every visible tool call owns
    // exactly one result. A mid-history use whose result is gone (history
    // trimmed, stream aborted) is paired with a NEUTRAL interrupted result —
    // not deleted, and not an error: deleting it hid the call from the
    // transcript, and an error told the model the call failed so it re-issued
    // the same call every turn.
    val tailUses = if (history.lastOrNull()?.role == LLMMessage.Role.ASSISTANT) {
        history.last().contentParts.mapIndexedNotNull { partIndex, part ->
            (part as? AgentContentPart.ToolUse)?.let { it.id to it.name to partIndex }
        }.toSet()
    } else emptySet()
    val repairedUses = unmatchedUses.filterNot { use ->
        use.messageIndex == history.lastIndex &&
            (use.id to use.name to use.partIndex) in tailUses
    }
    if (droppedResults.isEmpty() && repairedUses.isEmpty()) return history

    AppLogger.warning(
        ChatViewModel.TAG,
        "[CompactDiag] ordered tool pairing repair: droppedResults=${droppedResults.size} " +
            "interruptedUses=${repairedUses.size} historyCount=${history.size}",
    )
    val repairedByMessage = repairedUses.groupBy { it.messageIndex }
    val cleaned = ArrayList<LLMMessage>(history.size + repairedUses.size)
    history.forEachIndexed { index, message ->
        val kept = message.contentParts.mapIndexedNotNull { partIndex, part ->
            if (part is AgentContentPart.ToolResult && (index to partIndex) in droppedResults) null else part
        }
        if (kept.isNotEmpty() || message.contentParts.isEmpty()) {
            cleaned += if (kept.size == message.contentParts.size) message else message.copy(contentParts = kept)
        }
        val repairs = repairedByMessage[index].orEmpty()
        if (repairs.isNotEmpty()) {
            cleaned += LLMMessage(
                role = LLMMessage.Role.USER,
                content = "",
                contentParts = repairs.map {
                    AgentContentPart.ToolResult(
                        id = it.id,
                        name = it.name,
                        content = "[Tool execution was interrupted before resume]",
                        isError = false,
                    )
                },
            )
        }
    }
    return cleaned
}
