package com.openminis.app.ui.chat

import com.openminis.app.harness.ToolCall
import com.openminis.app.harness.ToolResult
import com.openminis.app.harness.effects.ToolReplayPolicy
import com.openminis.app.harness.metrics.RunMetrics
import com.openminis.app.logging.AppLogger
import com.openminis.app.operation.OperationBridge
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * [T-run-metrics-wiring] / [T-operation-wiring] 循环埋点与运行程序计数器转移的壳。
 *
 * 循环体（ChatViewModelAgentLoopExt）在尺寸棘轮上限，所有挂点收成单行调用收进
 * 本文件。RunMetrics 管离线基线单行日志；OperationCoordinator 管**持久化**程序
 * 计数器——进程死亡后恢复链靠它知道崩溃前一刻运行到哪一跳（见 OperationBridge
 * 的 unsettledIntents）。两者共用同一组挂点，互不替代。
 *
 * 未埋的计数器（approvalRequests / droppedToolCalls / budgetContinuations /
 * circuitBreaker）保持 0：审批与熔断的埋点分属 gate 与熔断器自己的批次。
 */

internal suspend fun ChatViewModel.beginRunMetrics() {
    currentRunMetrics = RunMetrics(System.currentTimeMillis())
    currentOperationId = OperationBridge.beginRun(context.applicationContext, activeSessionId)
}

internal suspend fun ChatViewModel.noteRunRound() {
    val metrics = currentRunMetrics ?: return
    metrics.roundStarted()
    currentOperationId?.let { op ->
        OperationBridge.providerIntent(context.applicationContext, op, metrics.roundsSoFar)
    }
}

internal fun ChatViewModel.noteRunToolCall(failed: Boolean) {
    currentRunMetrics?.toolCallRecorded(failed)
}

/** 工具执行前：整条 ToolCall 进台账（恢复期能还原完整调用而不只是参数）。 */
internal suspend fun ChatViewModel.noteRunToolIntent(name: String, argsJson: String, toolId: String) {
    val op = currentOperationId ?: return
    val args = runCatching { Json.parseToJsonElement(argsJson) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
    val call = ToolCall(
        id = toolId,
        createdAt = System.currentTimeMillis(),
        tool = harnessToolFor(name),
        args = args,
        rawToolName = name,
    )
    OperationBridge.toolIntent(context.applicationContext, op, call, ToolReplayPolicy.forTool(call.tool, name), currentRunMetrics?.roundsSoFar ?: 0)
}

/** 工具落定后：结果进台账（输出截 2k 入影子账，全文在 Room 与 spill 里）。 */
internal suspend fun ChatViewModel.noteRunToolSettled(name: String, toolId: String, result: com.openminis.app.harness.runtime.ToolOutcome) {
    val op = currentOperationId ?: return
    OperationBridge.toolSettled(
        context.applicationContext,
        op,
        ToolResult(
            id = "$toolId:result",
            createdAt = System.currentTimeMillis(),
            toolCallId = toolId,
            success = result.success,
            output = result.output.take(2_000),
        ),
        currentRunMetrics?.roundsSoFar ?: 0,
        name,
    )
}

internal fun ChatViewModel.noteRunRetry() {
    currentRunMetrics?.streamRetry()
}

/** 取消 / 后台挂起：操作行转 suspended，恢复期据此判「上次运行未完成」。 */
internal fun ChatViewModel.noteRunSuspended(reason: String) {
    val op = currentOperationId ?: return
    val sid = activeSessionId
    viewModelScope.launch { OperationBridge.suspendRun(context.applicationContext, op, reason) }
    currentOperationId = null
    AppLogger.info(ChatViewModel.TAG_STREAM, "operation suspended: $reason (sid=$sid)")
}

internal fun ChatViewModel.noteRunUsage(usage: com.openminis.app.data.model.LLMUsage?) {
    val metrics = currentRunMetrics ?: return
    usage?.let {
        metrics.recordUsage(
            com.openminis.app.harness.model.ChatUsage(
                inputTokens = it.inputTokens.toLong(),
                outputTokens = it.outputTokens.toLong(),
                cacheReadTokens = (it.cacheReadInputTokens ?: 0).toLong(),
                cacheWriteTokens = (it.cacheCreationInputTokens ?: 0).toLong(),
            ),
        )
    }
    val sid = activeSessionId
    val op = currentOperationId ?: return
    viewModelScope.launch { OperationBridge.providerSettled(context.applicationContext, sid, op, usage, metrics.roundsSoFar) }
}

internal fun ChatViewModel.endRunMetrics(outcome: String) {
    val metrics = currentRunMetrics
    if (metrics != null) {
        metrics.finish(outcome)
        AppLogger.info(ChatViewModel.TAG_STREAM, "RunMetrics ${metrics.summary()}")
        currentRunMetrics = null
    }
    val op = currentOperationId ?: return
    val sid = activeSessionId
    viewModelScope.launch { OperationBridge.finishRun(context.applicationContext, sid, outcome) }
    currentOperationId = null
}
