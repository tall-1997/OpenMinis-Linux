package com.openminis.app.harness.agent

import com.openminis.app.data.model.AgentContentPart

/**
 * [T-android-seam-extraction] 循环片段一：工具调用的执行前拒绝。
 *
 * 主循环里两条拒绝路径（preflight 校验拒 / 截断拒）的纯逻辑半边——消息构造、
 * 检测器记账、错误结果部件——原样迁进 :harness。宿主半边（块置 FAILED +
 * 消息刷新）走 [com.openminis.app.harness.runtime.UiEventSink]。
 *
 * 文案逐字保留：modelMessage 是给模型的纠错指令（空参数重发 / 截断后重读上下文
 * 重发），动一个词都可能改变模型的自愈行为；uiMessage 是块上短文案。
 */
object ToolCallPreflight {

    data class Rejection(
        val uiMessage: String,
        val modelMessage: String,
        val toolResultPart: AgentContentPart.ToolResult,
    )

    /** 参数校验失败：模型发了空参数或缺必填字段的调用。 */
    fun rejectInvalid(
        toolCallId: String,
        toolName: String,
        validationError: String,
        params: Map<String, Any?>,
        detector: ToolLoopDetector,
    ): Rejection {
        val uiMessage = "Blocked invalid tool call"
        val modelMessage = "Error: Tool call rejected before execution. $validationError " +
            "The arguments your client sent were empty or missing required fields — " +
            "re-issue the call with all required parameters filled in. " +
            "Do not retry with the same empty arguments."
        detector.record(
            toolName = toolName,
            params = params,
            result = null,
            errorMessage = modelMessage,
            toolCallId = toolCallId,
        )
        return Rejection(
            uiMessage = uiMessage,
            modelMessage = modelMessage,
            toolResultPart = AgentContentPart.ToolResult(
                id = toolCallId,
                name = toolName,
                content = modelMessage,
                isError = true,
            ),
        )
    }

    /** 流被长度截断（finish_reason=length/max_tokens）：参数极可能是半截 JSON。 */
    fun rejectTruncated(
        toolCallId: String,
        toolName: String,
        finishReason: String?,
        params: Map<String, Any?>,
        detector: ToolLoopDetector,
    ): Rejection {
        val modelMessage = "Error: This tool call was rejected because the " +
            "model output was truncated (finish_reason=$finishReason). " +
            "The arguments are likely incomplete. Re-read any relevant " +
            "context and re-issue the call with complete arguments."
        detector.record(
            toolName = toolName,
            params = params,
            result = null,
            errorMessage = modelMessage,
            toolCallId = toolCallId,
        )
        return Rejection(
            uiMessage = modelMessage,
            modelMessage = modelMessage,
            toolResultPart = AgentContentPart.ToolResult(
                id = toolCallId,
                name = toolName,
                content = modelMessage,
                isError = true,
            ),
        )
    }
}
