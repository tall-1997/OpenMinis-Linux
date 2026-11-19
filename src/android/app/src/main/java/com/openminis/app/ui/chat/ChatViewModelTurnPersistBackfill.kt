package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
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
internal suspend fun ChatViewModel.persistAssistantTurnForRunAndBackfill(
    run: ActiveRun?,
    turnParts: List<AgentContentPart>,
    blockMeta: Map<String, AssistantBlock>,
    lastUsage: LLMUsage?,
    turnReasoningContent: String?,
    accumulatedText: String,
    agentHistory: MutableList<LLMMessage>,
    // [T-thinking-dual-ledger] passthrough — see persistAssistantTurnForRun.
    requestedLevel: ThinkingLevel? = null,
    modelSnapshot: ModelAttributionSnapshot? = null,
    contextUsageAnchor: ContextUsageAnchor? = null,
): String? {
    val assistant = agentHistory.lastOrNull()
    val anchor = turnContextUsageAnchor(lastUsage, modelSnapshot, contextUsageAnchor)
    val assistantDbId = persistAssistantTurnForRun(
        checkNotNull(run) { "Agent loop has no persistence owner" },
        turnParts, lastUsage, turnReasoningContent, blockMeta, accumulatedText,
        requestedLevel = requestedLevel,
        modelSnapshot = modelSnapshot,
        contextUsageAnchor = anchor,
    )
    if (assistantDbId != null && agentHistory.lastOrNull() === assistant) {
        backfillPersistedAssistantTurn(agentHistory, assistantDbId, anchor, turnParts)
    }
    return assistantDbId
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
