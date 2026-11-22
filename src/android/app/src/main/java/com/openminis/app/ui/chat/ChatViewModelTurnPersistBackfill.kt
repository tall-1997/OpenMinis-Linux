package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.repository.AssistantDisplayUpdate
import com.openminis.app.data.repository.PendingTurnRow
import com.openminis.app.data.repository.appendTurnRows
import com.openminis.app.provider.selectableThinkingLevels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.openminis.app.data.model.ContextUsageAnchor
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.ModelAttributionSnapshot
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.service.ActiveRun

/**
 * 持久化本轮 assistant 消息并回填 agentHistory 的 dbMessageId（拆分自 runAgentLoopBody）。
 *
 * compact-marker 的边界解析依赖 dbMessageId。只将 DB id 和用量锚回填到
 * 本轮尚未持久化的 assistant，不能误挂到更早的轮次。
 */
internal suspend fun ChatViewModel.persistAgentTextTurn(
    run: ActiveRun,
    sid: String,
    epoch: Long,
    assistantId: String,
    parts: List<AgentContentPart.Text>,
    usage: LLMUsage?,
    reasoningContent: String?,
    requestedLevel: ThinkingLevel?,
    modelSnapshot: ModelAttributionSnapshot?,
    contextUsageAnchor: ContextUsageAnchor?,
    recoveryNote: String? = null,
    continuationPrefix: StreamReplyPrefix? = null,
    onCommitted: () -> Unit,
): String? = run.withPersistencePermit {
    if (parts.isEmpty() && recoveryNote == null && continuationPrefix == null) return@withPersistencePermit null
    withContext(NonCancellable + Dispatchers.IO) {
        fun ownsWrite() = ownsTranscript(sid, epoch, run)
        val assistant = agentHistory.lastOrNull()
        val anchor = turnContextUsageAnchor(usage, modelSnapshot, contextUsageAnchor)
        val thinking = requestedLevel?.let {
            resolveTurnThinkingUsage(it, !reasoningContent.isNullOrEmpty(), currentModel?.selectableThinkingLevels.orEmpty())
        }
        // A completed continuation replaces the cut-off row's visible parts: one row, one paragraph.
        val settledParts = continuationPrefix?.let { prefix ->
            val all = prefix.completedParts.map { AgentContentPart.Text(it.text, false) } +
                parts.map { AgentContentPart.Text(it.text, it.isProcessText) }
            // One contiguous answer = one text part, so copy/translate/preview see one paragraph.
            if (all.all { !it.isProcessText }) listOf(AgentContentPart.Text(all.joinToString("") { it.text }))
            else all
        }
        val rows = buildList {
            if (parts.isNotEmpty() && continuationPrefix == null) add(PendingTurnRow(
                role = "assistant", partsJson = buildAssistantPartsJson(parts, emptyMap()),
                tokenUsage = buildTurnTokenUsageJson(usage, run.streamRate.takeDurationMs(), thinking, anchor),
                reasoningContent = reasoningContent,
                modelSnapshot = modelSnapshot ?: anchor?.modelSnapshot ?: currentModelSnapshot(),
            ))
            recoveryNote?.let { add(PendingTurnRow("user", hostReminderPartsJson(JSONObject.quote(it)))) }
        }
        val displayUpdate = continuationPrefix?.let { prefix ->
            AssistantDisplayUpdate(prefix.rowId, buildAssistantPartsJson(settledParts.orEmpty(), emptyMap()))
        }
        val saved = chatRepository.appendTurnRows(sid, rows, displayUpdate, ::ownsWrite)
        // A merged continuation commits through the row update; it appends no new assistant row.
        if (saved.isEmpty() && displayUpdate == null) return@withContext null
        val insertedId = saved.firstOrNull { it.role == "assistant" }?.id
        if (insertedId != null) run.markAssistantTurnPersisted()
        withContext(Dispatchers.Main.immediate) {
            if (ownsTranscript(sid, epoch, run = null)) {
                if (continuationPrefix != null) {
                    // The continuation turn's own row never existed; fold it back into the settled row.
                    if (assistant?.role == LLMMessage.Role.ASSISTANT && assistant.dbMessageId == null) {
                        agentHistory.removeAt(agentHistory.lastIndex)
                    }
                    for (i in agentHistory.indices.reversed()) {
                        val entry = agentHistory[i]
                        if (entry.role != LLMMessage.Role.ASSISTANT || entry.dbMessageId != continuationPrefix.rowId) continue
                        agentHistory[i] = entry.copy(
                            content = settledParts.orEmpty().joinToString("") { it.text },
                            contentParts = settledParts.orEmpty(),
                        )
                        break
                    }
                    notePersistedUiRow(assistantId, continuationPrefix.rowId)
                } else if (insertedId != null) {
                    if (agentHistory.lastOrNull() === assistant) backfillPersistedAssistantTurn(agentHistory, insertedId, anchor, parts)
                    notePersistedUiRow(assistantId, insertedId)
                } else if (assistant != null && agentHistory.lastOrNull() === assistant) {
                    agentHistory.removeAt(agentHistory.lastIndex)
                }
                if (recoveryNote != null) {
                    val noteId = saved.last().id
                    appendBoundedHistory(LLMMessage(
                        LLMMessage.Role.USER, recoveryNote, contentParts = listOf(AgentContentPart.Text(recoveryNote)),
                        dbMessageId = noteId, isHostGenerated = true,
                    ))
                    notePersistedUiRow(null, noteId)
                }
                onCommitted()
            }
        }
        insertedId ?: continuationPrefix?.rowId
    }
}

/** Call before appending the next tool-result or nudge row. Never search older turns. */
internal fun backfillPersistedAssistantTurn(
    history: MutableList<LLMMessage>,
    assistantDbId: String,
    contextUsageAnchor: ContextUsageAnchor? = null,
) = backfillPersistedAssistantTurn(history, assistantDbId, contextUsageAnchor, persistedParts = null)

internal fun backfillPersistedAssistantTurn(
    history: MutableList<LLMMessage>,
    assistantDbId: String,
    contextUsageAnchor: ContextUsageAnchor? = null,
    persistedParts: List<AgentContentPart>?,
) {
    val assistant = history.lastOrNull() ?: return
    if (assistant.role != LLMMessage.Role.ASSISTANT || assistant.dbMessageId != null) return
    history[history.lastIndex] = assistant.copy(
        contentParts = persistedParts?.let { assistant.replayParts(it) } ?: assistant.contentParts,
        dbMessageId = assistantDbId,
        contextUsageAnchor = contextUsageAnchor,
    )
}

private fun LLMMessage.replayParts(persistedParts: List<AgentContentPart>): List<AgentContentPart> {
    val stored = persistedParts.filter { it is AgentContentPart.Text || it is AgentContentPart.ToolUse && it.name.isNotBlank() }
    val liveTextParts = contentParts.filterIsInstance<AgentContentPart.Text>()
    val liveText = if (contentParts.isEmpty()) content
        else liveTextParts.singleOrNull()?.text ?: liveTextParts.joinToString("") { it.text }
    var consumed = 0
    val sameText = stored.filterIsInstance<AgentContentPart.Text>().all { part ->
        if (!liveText.regionMatches(consumed, part.text, 0, part.text.length)) return@all false
        consumed += part.text.length
        true
    } && consumed == liveText.length
    val liveMedia = contentParts.any { it is AgentContentPart.ImageData || it is AgentContentPart.ToolResult } ||
        (contentParts.isEmpty() && (imageParts.isNotEmpty() || audioParts.isNotEmpty()))
    if (sameText && !liveMedia) return stored

    // A generated image link may exist only in the live text, not in the text blocks.
    // Preserve the live representation in this case, but still replace calls with repaired snapshots.
    val repaired = stored.filterIsInstance<AgentContentPart.ToolUse>().associateBy { it.id }
    return contentParts.map { if (it is AgentContentPart.ToolUse) repaired[it.id] ?: it else it }
}

/**
 * 把本轮的 parts 预览推到会话列表（拆分自 runAgentLoopBody 的内联 run 块）。
 */
internal suspend fun ChatViewModel.pushLiveSessionPreview(
    allToolBlocks: List<AssistantBlock>,
    turnStartBlockIndex: Int,
    toolInputMap: Map<String, String>,
) {
    val livePreviewParts = buildTurnParts(allToolBlocks, turnStartBlockIndex, toolInputMap)
    val liveMeta = allToolBlocks.filter { it.kind == "tool_use" }.associateBy { it.id }
    if (livePreviewParts.isNotEmpty()) {
        chatRepository.updateSessionPreview(
            realSessionId.ifEmpty { sessionId },
            buildAssistantPartsJson(livePreviewParts, liveMeta),
        )
    }
}
