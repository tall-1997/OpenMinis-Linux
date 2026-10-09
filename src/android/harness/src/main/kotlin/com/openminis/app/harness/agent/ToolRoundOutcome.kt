package com.openminis.app.harness.agent

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.harness.runtime.RoundStatus

/**
 * [T-android-seam-extraction] 循环片段二：工具轮结果处理（纯逻辑半边）。
 *
 * 主循环每个工具调用落定后的四件纯事——终局裁定、块内容合并、截断注记、
 * 结果部件与历史消息构造——原样迁进 :harness。宿主半边（块投影 / 通知 /
 * 落盘 spill / Room 持久化）走 UiEventSink 与各持久化路径。
 *
 * 语义逐条保留，含三处历史决策：
 * - shell_execute 尾部 80 行裁剪（T263：file_read 等首行横幅工具不裁）；
 * - 直播内容与结果内容取长者（流式块可能比被截断的结果多）；
 * - 截断修复过的成功调用判 FAILED（#119：静默的成功外观就是报的 bug）。
 */
object ToolRoundOutcome {

    /** 终局裁定：取消 > 截断修复 > 成败 > 超时。 */
    fun decideStatus(
        success: Boolean,
        timedOut: Boolean,
        truncationRepaired: Boolean,
        cancelled: Boolean,
    ): RoundStatus = when {
        cancelled -> RoundStatus.CANCELLED
        success && truncationRepaired -> RoundStatus.FAILED
        success -> RoundStatus.SUCCESS
        timedOut -> RoundStatus.TIMEOUT
        else -> RoundStatus.FAILED
    }

    /**
     * 块内容合并：shell_execute 只留尾部 80 行；直播内容与结果内容取长者；
     * 有子代理波时块上只留分发摘要（详情在子卡片）。
     */
    fun blockContent(toolName: String, resultOutput: String, liveContent: String, childCount: Int): String {
        val resultContent = if (toolName == "shell_execute") {
            resultOutput.lines().takeLast(80).joinToString("\n")
        } else {
            resultOutput
        }
        val merged = if (liveContent.length > resultContent.length) liveContent else resultContent
        return if (childCount > 0) "已分发 $childCount 个子代理，点开各自卡片查看当前运行。" else merged
    }

    /** 截断修复注记：告诉模型实际跑的参数可能不完整（写进 tool_result）。 */
    fun withTruncationNote(output: String, repairTag: String?): String =
        if (repairTag == null) output
        else output + "\n\n<system-reminder>The argument stream for this call was " +
            "truncated in transit and auto-closed by the client (repair strategy: " +
            "$repairTag) before execution. The arguments actually used may be " +
            "incomplete — verify the result and re-issue the call with complete " +
            "arguments if anything is missing.</system-reminder>"

    /** tool_result 部件（图像三元组透传，harness 不解释字节）。 */
    fun toolResultPart(
        callId: String,
        toolName: String,
        output: String,
        isError: Boolean,
        imageData: ByteArray? = null,
        imageMimeType: String? = null,
        imageLinuxPath: String? = null,
    ): AgentContentPart.ToolResult = AgentContentPart.ToolResult(
        id = callId,
        name = toolName,
        content = output,
        isError = isError,
        imageData = imageData,
        imageMimeType = imageMimeType,
        imageLinuxPath = imageLinuxPath,
    )

    /** 轮尾历史消息：tool_result 以 user 角色进历史（镜像 iOS）。 */
    fun toolResultMessage(parts: List<AgentContentPart>, dbMessageId: String?): LLMMessage =
        LLMMessage(
            role = LLMMessage.Role.USER,
            content = "",
            contentParts = parts,
            dbMessageId = dbMessageId,
        )
}
