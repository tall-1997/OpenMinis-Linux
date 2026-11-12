package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.service.ActiveRun

/**
 * 持久化本轮 assistant 消息并回填 agentHistory 的 dbMessageId（拆分自 runAgentLoopBody）。
 *
 * compact-marker 的边界解析依赖这个 dbMessageId：没有它，压缩后的分隔线无法
 * 锚定到正确的历史行。行为与原地实现一致。
 */
internal suspend fun ChatViewModel.persistAssistantTurnForRunAndBackfill(
    run: ActiveRun?,
    turnParts: List<AgentContentPart>,
    blockMeta: Map<String, AssistantBlock>,
    lastUsage: LLMUsage?,
    turnReasoningContent: String?,
    accumulatedText: String,
    agentHistory: MutableList<LLMMessage>,
): String? {
    val assistantDbId = persistAssistantTurnForRun(
        checkNotNull(run) { "Agent loop has no persistence owner" },
        turnParts, lastUsage, turnReasoningContent, blockMeta, accumulatedText,
    )
    if (assistantDbId != null) {
        val lastIdx = agentHistory.indexOfLast { it.role == LLMMessage.Role.ASSISTANT && it.dbMessageId == null }
        if (lastIdx >= 0) {
            agentHistory[lastIdx] = agentHistory[lastIdx].copy(dbMessageId = assistantDbId)
        }
    }
    return assistantDbId
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
