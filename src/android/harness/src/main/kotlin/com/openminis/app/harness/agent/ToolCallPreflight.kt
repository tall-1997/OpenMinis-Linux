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

    /**
     * [T-truncated-args-visibility #119] 拒绝截断的**写**调用（file_write /
     * file_edit）。自动闭合未终结的 JSON 字符串与「模型在此处收尾」无法区分，
     * 半截 content 落盘 = 用户数据的静默损坏——比不写更糟。只读与 shell 工具
     * 保留修复后执行的行为。文案逐字保留：这是给模型的纠错指令（分片重发，
     * 别原样重发超大调用）。
     */
    fun rejectTruncatedWrite(
        toolCallId: String,
        toolName: String,
        repairStrategy: String,
        targetPath: String,
        params: Map<String, Any?>,
        detector: ToolLoopDetector,
    ): Rejection {
        val modelMessage = buildString {
            append("Error: This call was NOT executed. Its argument stream was truncated ")
            append("in transit (repair strategy: $repairStrategy), so the `content` ")
            append("your client sent was cut short and would have written an incomplete file")
            if (targetPath.isNotBlank()) append(" to $targetPath")
            append(". Nothing was written to disk — the target file is unchanged.\n\n")
            append("The most likely cause is the response hitting its output-token limit ")
            append("mid-argument. Re-issue this write in smaller pieces: write the first ")
            append("part, then append the rest with follow-up calls, rather than repeating ")
            append("the same oversized call.")
        }
        detector.record(
            toolName = toolName,
            params = params,
            result = null,
            errorMessage = modelMessage,
            toolCallId = toolCallId,
        )
        return Rejection(
            uiMessage = "Blocked: arguments were truncated in transit",
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
