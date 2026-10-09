package com.openminis.app.ui.chat

import com.openminis.app.harness.metrics.RunMetrics
import com.openminis.app.logging.AppLogger

/**
 * [T-run-metrics-wiring] 把 harness 的 [RunMetrics] 接进 agent 循环的埋点壳。
 *
 * 循环体（ChatViewModelAgentLoopExt）已在尺寸棘轮上限，所有埋点收成单行调用
 * 收进本文件：loop 入口建实例、每轮 roundStarted、工具调用成败、限流退避重试、
 * 每轮 provider usage、loop 收尾 finish + 结构化单行日志（taixu 的设计目的：
 * 离线汇总「任务自主完成率 / 错误自恢复率 / 人工干预次数」的真实基线）。
 *
 * 实例挂在 [ChatViewModel.currentRunMetrics]（@Volatile）：executeTool 在另一个
 * ext 文件里也要累加，经 VM 字段是唯一不穿透函数签名的路径。子代理 lane 复用
 * runAgentLoop，因此 lane 的运行同样被度量。
 *
 * 未埋的计数器（approvalRequests / droppedToolCalls / budgetContinuations /
 * circuitBreaker）保持 0：审批与熔断的埋点分属 gate 与熔断器自己的批次，不在
 * 本接线里顺手改安全面代码。summary 里它们以 0 出现，读日志时须知口径。
 */

internal fun ChatViewModel.beginRunMetrics() {
    currentRunMetrics = RunMetrics(System.currentTimeMillis())
}

internal fun ChatViewModel.noteRunRound() {
    currentRunMetrics?.roundStarted()
}

internal fun ChatViewModel.noteRunToolCall(failed: Boolean) {
    currentRunMetrics?.toolCallRecorded(failed)
}

internal fun ChatViewModel.noteRunRetry() {
    currentRunMetrics?.streamRetry()
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
}

internal fun ChatViewModel.endRunMetrics(outcome: String) {
    val metrics = currentRunMetrics ?: return
    metrics.finish(outcome)
    AppLogger.info(ChatViewModel.TAG_STREAM, "RunMetrics ${metrics.summary()}")
    currentRunMetrics = null
}
