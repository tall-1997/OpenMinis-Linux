package com.openminis.app.ui.chat

import com.openminis.app.harness.runtime.ConversationPort
import com.openminis.app.harness.runtime.RoundStatus
import com.openminis.app.harness.runtime.ToolExecutorPort
import com.openminis.app.harness.runtime.ToolFinish
import com.openminis.app.harness.runtime.ToolOutcome
import com.openminis.app.harness.runtime.UiEventSink
import com.openminis.app.tools.ToolExecutionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [T-android-seam-extraction] 接缝二/三/四的宿主适配器。
 *
 * - [conversationPort]：盖在 appendBoundedHistory / agentHistory 上，有界语义
 *   由既有裁剪承担。恢复链与轮尾历史追加都从这道接缝消费。
 * - [toolExecutorPort]：**每轮一个实例**，捕获该轮的 UI 上下文（工具块列表 /
 *   assistantId / 当前文本）。完整管线（schema 校验 / 门禁 / 执行 / 输出策略 /
 *   演化钩子 / 台账埋点）全部藏在 executeTool 后面。
 * - [uiEventSink]：块投影 + 通知收尾。终局裁定与内容合并在 harness
 *   （ToolRoundOutcome），宿主只做投影。
 */
fun ChatViewModel.conversationPort(): ConversationPort = object : ConversationPort {
    override fun history(): List<com.openminis.app.data.model.LLMMessage> = agentHistory.toList()
    override fun append(message: com.openminis.app.data.model.LLMMessage) = appendBoundedHistory(message)
    override fun appendAll(messages: Collection<com.openminis.app.data.model.LLMMessage>) =
        appendBoundedHistoryAll(messages)
}

/** 宿主结果 → 接缝口径（字段搬运，图像字节透传不解释）。 */
internal fun ToolExecutionResult.toOutcome(): ToolOutcome = ToolOutcome(
    success = success,
    output = output,
    toolTitle = toolTitle,
    timedOut = timedOut,
    pageURL = pageURL,
    imageFilePath = imageFilePath,
    imageData = imageData,
    imageMimeType = imageMimeType,
    imageLinuxPath = imageLinuxPath,
)

fun ChatViewModel.toolExecutorPort(
    toolBlocks: MutableList<AssistantBlock>,
    assistantId: String,
    currentText: String,
): ToolExecutorPort = object : ToolExecutorPort {
    override suspend fun execute(toolCallId: String, toolName: String, argsJson: String): ToolOutcome {
        val result = executeTool(toolName, argsJson, toolCallId, toolBlocks, assistantId, currentText)
        return result.toOutcome()
    }
}

/**
 * [T-android-seam-extraction] 接缝四宿主实现。
 * 拒绝路径：块置 FAILED + 主线程刷新（截断拒绝此前不刷新，静默外观与 #119
 * 同款，已统一为立即刷新）。完成路径：块终局投影 + 通知进度条收尾——
 * 消息刷新留在轮尾（一轮一次，不在每个工具后刷）。
 */
fun ChatViewModel.uiEventSink(
    toolBlocks: MutableList<AssistantBlock>,
    assistantId: String,
    currentText: String,
): UiEventSink = object : UiEventSink {
    override suspend fun onToolCallRejected(toolCallId: String, uiMessage: String) {
        val idx = toolBlocks.indexOfFirst { it.id == toolCallId }
        if (idx < 0) return
        val elapsed = System.currentTimeMillis() - toolBlocks[idx].startTimeMs
        toolBlocks[idx] = toolBlocks[idx].copy(
            toolStatus = ToolBlockStatus.FAILED,
            content = uiMessage,
            durationMs = elapsed,
        )
        withContext(Dispatchers.Main) {
            updateAssistantMessage(assistantId, currentText, true, toolBlocks)
        }
    }

    override suspend fun onToolCallFinished(event: ToolFinish) {
        val idx = toolBlocks.indexOfFirst { it.id == event.toolCallId }
        if (idx < 0) return
        val block = toolBlocks[idx]
        val elapsed = System.currentTimeMillis() - block.startTimeMs
        toolBlocks[idx] = block.copy(
            toolStatus = event.status.toBlockStatus(),
            content = event.content,
            toolTitle = event.toolTitle.ifEmpty { block.toolTitle },
            durationMs = elapsed,
            browserURL = event.browserURL ?: block.browserURL,
            imageFilePath = event.imageFilePath ?: block.imageFilePath,
        )
        // [T-overlay-glyph-typed-outcome] 通知图标本反映真实终局而非文本嗅探。
        com.openminis.app.service.SessionActivityTracker.clearToolRunning(event.status.toServiceOutcome())
        android.util.Log.d(
            "ToolChain[VM]",
            "block[$idx] status→${event.status} title=${event.toolTitle} contentLen=${event.content.length}",
        )
    }
}

private fun RoundStatus.toBlockStatus(): ToolBlockStatus = when (this) {
    RoundStatus.SUCCESS -> ToolBlockStatus.SUCCESS
    RoundStatus.TIMEOUT -> ToolBlockStatus.TIMEOUT
    RoundStatus.FAILED -> ToolBlockStatus.FAILED
    RoundStatus.CANCELLED -> ToolBlockStatus.CANCELLED
}

private fun RoundStatus.toServiceOutcome(): com.openminis.app.service.ToolOutcome = when (this) {
    RoundStatus.SUCCESS -> com.openminis.app.service.ToolOutcome.Success
    RoundStatus.TIMEOUT -> com.openminis.app.service.ToolOutcome.Timeout
    RoundStatus.FAILED -> com.openminis.app.service.ToolOutcome.Error
    RoundStatus.CANCELLED -> com.openminis.app.service.ToolOutcome.Unknown
}
