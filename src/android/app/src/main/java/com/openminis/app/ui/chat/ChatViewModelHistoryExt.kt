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
    // [T-orphan-use-removal] A mid-history use with NO result is not a failed
    // tool call: it is the surviving half of a pair that history trimming cut
    // apart. The old repair fabricated an "interrupted by an unexpected error"
    // result for it, so the model read a failed call and re-issued the SAME
    // call on the next turn — every request rebuilt the same slice, the same
    // fabrication reappeared, and the run looped forever (the reported
    // "repeats itself and does no work"). Remove the orphaned use instead:
    // the provider sees a clean, fully paired transcript, and the model has no
    // failed call to retry.
    val tailUses = if (history.lastOrNull()?.role == LLMMessage.Role.ASSISTANT) {
        history.last().contentParts.mapIndexedNotNull { partIndex, part ->
            (part as? AgentContentPart.ToolUse)?.let { it.id to it.name to partIndex }
        }.toSet()
    } else emptySet()
    val removableUses = unmatchedUses.filterNot { use ->
        use.messageIndex == history.lastIndex &&
            (use.id to use.name to use.partIndex) in tailUses
    }
    if (droppedResults.isEmpty() && removableUses.isEmpty()) return history

    AppLogger.warning(
        ChatViewModel.TAG,
        "[CompactDiag] ordered tool pairing repair: droppedResults=${droppedResults.size} " +
            "removedOrphanUses=${removableUses.size} historyCount=${history.size}",
    )
    val removedByMessage = removableUses.groupBy { it.messageIndex }
    val cleaned = ArrayList<LLMMessage>(history.size)
    history.forEachIndexed { index, message ->
        val removedHere = removedByMessage[index].orEmpty()
        val kept = message.contentParts.mapIndexedNotNull { partIndex, part ->
            when {
                part is AgentContentPart.ToolResult && (index to partIndex) in droppedResults -> null
                // Pairing-key comparison, same rule the matcher above uses: a
                // Responses-style "<call_id>|<fc_id>" use must be removed by
                // the same key its result was matched on.
                part is AgentContentPart.ToolUse && removedHere.any {
                    ToolPairing.key(it.id) == ToolPairing.key(part.id) && it.name == part.name
                } -> null
                else -> part
            }
        }
        if (kept.isNotEmpty() || message.contentParts.isEmpty()) {
            cleaned += if (kept.size == message.contentParts.size) message else message.copy(contentParts = kept)
        }
    }
    return cleaned
}
