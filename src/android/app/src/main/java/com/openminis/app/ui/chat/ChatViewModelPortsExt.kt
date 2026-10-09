package com.openminis.app.ui.chat

import com.openminis.app.harness.runtime.ConversationPort
import com.openminis.app.harness.runtime.ToolExecutorPort
import com.openminis.app.harness.runtime.ToolOutcome

/**
 * [T-android-seam-extraction] 接缝二/三的宿主适配器。
 *
 * - [conversationPort]：盖在 appendBoundedHistory / agentHistory 上，有界语义
 *   由既有裁剪承担。恢复链（synthesizeUnsettledOperationIntents）已经从这道
 *   接缝消费——不是落地即死代码。
 * - [toolExecutorPort]：**每轮一个实例**，创建时捕获该轮的 UI 上下文（工具块
 *   列表 / assistantId / 当前文本）——UI 投影是接缝四（UiEventSink）的职责，
 *   在它落地前，轮次上下文由捕获参数过渡。完整管线（schema 校验 / 门禁 /
 *   输出策略 / 演化钩子 / 台账埋点）全部藏在 executeTool 后面。
 */
fun ChatViewModel.conversationPort(): ConversationPort = object : ConversationPort {
    override fun history(): List<com.openminis.app.data.model.LLMMessage> = agentHistory.toList()
    override fun append(message: com.openminis.app.data.model.LLMMessage) = appendBoundedHistory(message)
    override fun appendAll(messages: Collection<com.openminis.app.data.model.LLMMessage>) =
        appendBoundedHistoryAll(messages)
}

fun ChatViewModel.toolExecutorPort(
    toolBlocks: MutableList<AssistantBlock>,
    assistantId: String,
    currentText: String,
): ToolExecutorPort = object : ToolExecutorPort {
    override suspend fun execute(toolName: String, argsJson: String): ToolOutcome {
        val result = executeTool(toolName, argsJson, "seam-${toolName}-${assistantId}", toolBlocks, assistantId, currentText)
        return ToolOutcome(success = result.success, output = result.output)
    }
}
