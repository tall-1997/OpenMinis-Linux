package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.ToolPairing

/**
 * [T-orphan-use-report] What one pairing repair had to change.
 *
 * WHY this type exists: `0.3` asked for `dropOrphanedToolParts` to REPORT the
 * damage instead of silently fixing it. The repair itself must stay — deleting
 * a call or fabricating an error both made the model re-issue the call every
 * turn — but a repair that only logs is invisible after the fact. The counts
 * ride back to the caller, which writes them to the session journal, so "this
 * request was served from a mutated history" survives the process.
 */
/**
 * [T-pairing-writeback] One interrupted tool call whose neutral result can be
 * persisted: the DB row id of the assistant message owning the use, plus the
 * use identity. Written back once, so the projection's per-turn neutral
 * injection disappears from every later turn.
 */
internal data class InterruptedUseRef(
    val assistantRowId: String,
    val toolUseId: String,
    val toolName: String,
)

internal data class PairingRepair(
    val history: List<LLMMessage>,
    val droppedResults: Int,
    val interruptedUses: Int,
    /**
     * [T-pairing-repair-names] `tool/name` of each dropped result (bounded).
     * The count alone is not actionable: 15 dropped in one compaction round
     * (2026-11-22 10:06) reads the same whether it is one parallel batch whose
     * calls were trimmed, or a pairing bug dropping real results. The names say
     * which.
     */
    val droppedNames: List<String> = emptyList(),
    /** [T-pairing-writeback] The interrupted calls this repair patched with a
     *  neutral result — the write-back's material. Empty unless interruptedUses > 0. */
    val interruptedUseRefs: List<InterruptedUseRef> = emptyList(),
) {
    val changed: Boolean get() = droppedResults > 0 || interruptedUses > 0
}

/**
 * Validate tool rounds in wire order. A result is valid only when it follows
 * an unmatched use with the same id and name; set membership alone is unsafe
 * because duplicate ids, wrong names, and result-before-use all reach providers
 * as malformed requests.
 */
internal fun dropOrphanedToolParts(history: List<LLMMessage>): List<LLMMessage> =
    repairToolPairing(history).history

/** [T-orphan-use-report] The same sweep, keeping what it had to change. */
// [T-pairing-repair-log-once] Last reported repair shape. Top-level because
// repairToolPairing is a top-level function; @Volatile because request
// projection can run on a coroutine.
@Volatile private var lastRepairShape: String? = null

internal fun repairToolPairing(history: List<LLMMessage>): PairingRepair {
    data class Use(
        val id: String,
        val name: String,
        val messageIndex: Int,
        val partIndex: Int,
    )
    val pending = ArrayList<Use>()
    val unmatchedUses = ArrayList<Use>()
    val droppedResults = HashSet<Pair<Int, Int>>()
    // [T-pairing-repair-names] Bounded so a huge trim cannot grow the log.
    val droppedNames = ArrayList<String>()

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
                        // [T-pairing-repair-names] Digest, never the raw id: it comes
                        // from an external tool_call response and the journal contract
                        // is digests-only (SessionJournal: not a second transcript).
                        // Tool NAME is safe (closed enum) and is what makes the count
                        // diagnosable; the digest disambiguates duplicates.
                        val tag = part.name + "/" +
                            com.openminis.app.util.Digest.sha256Hex(part.id).take(12)
                        if (droppedNames.size < 20 && tag !in droppedNames) {
                            droppedNames += tag
                        }
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
    if (droppedResults.isEmpty() && repairedUses.isEmpty()) {
        return PairingRepair(history, droppedResults = 0, interruptedUses = 0)
    }

    // [T-pairing-repair-log-once] 63 of the 204 WARNs in one day of the 2026-11-22
    // field log were this exact line with an unchanged count (interruptedUses=3),
    // which buried the real signals. Warn only when the SHAPE changes.
    val shape = "${droppedResults.size}/${repairedUses.size}" +
        (if (droppedNames.isEmpty()) "" else "/" + droppedNames.joinToString(","))
    val message = "[CompactDiag] ordered tool pairing repair: " +
        "droppedResults=${droppedResults.size} interruptedUses=${repairedUses.size} " +
        "historyCount=${history.size}" +
        (if (droppedNames.isEmpty()) "" else " dropped=${droppedNames.joinToString(",")}")
    if (lastRepairShape != shape) {
        lastRepairShape = shape
        AppLogger.warning(ChatViewModel.TAG, message)
    } else {
        AppLogger.info(ChatViewModel.TAG, message)
    }
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
    // [T-orphan-use-report] The counts ride back to the caller: the repair
    // stays silent for the MODEL (an error made it re-issue the call) but must
    // not be silent for the operator, or "this request was served from a
    // mutated history" becomes undiagnosable after the fact.
    // [T-pairing-writeback] The DB row id rides along so the caller can
    // persist the neutral result next to the use — after that this repair
    // never fires again for the same rows.
    val interruptedUseRefs = repairedUses.mapNotNull { use ->
        history.getOrNull(use.messageIndex)?.dbMessageId?.let { rowId ->
            InterruptedUseRef(assistantRowId = rowId, toolUseId = use.id, toolName = use.name)
        }
    }
    return PairingRepair(
        history = cleaned,
        droppedResults = droppedResults.size,
        interruptedUses = repairedUses.size,
        droppedNames = droppedNames,
        interruptedUseRefs = interruptedUseRefs,
    )
}
