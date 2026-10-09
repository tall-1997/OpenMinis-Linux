package com.openminis.app.harness.metrics

import com.openminis.app.harness.model.ChatUsage
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Adapted from taixu RunMetrics (GPL-3.0-or-later).
 * https://github.com/wkbin/taixu
 *
 * 单次 Agent 运行的过程指标采集（Harness 2.0 Phase 0 基线埋点）。
 *
 * 目的：在引入自主规划 / 自我反思等能力之前，先让"任务自主完成率、错误自恢复率、
 * 人工干预次数"这些目标指标可度量。每次运行结束时以结构化单行写入 Agent 日志，
 * 用于离线汇总真实基线。
 *
 * 指标定义：
 * - rounds：Provider 请求轮数
 * - toolCalls / toolFailures：工具调用总数与失败数（含校验被拒的调用）
 * - approvalRequests：触发用户审批的次数（人工干预的直接信号）
 * - streamRetries：限流 + 网络退避重试次数
 * - circuitBreakerTripped：是否触发连续失败熔断
 * - droppedToolCalls：超出单轮上限被丢弃的调用数
 * - budgetContinuations：轮次预算用尽后自动续跑的次数（等价于省下的人工点击）
 * - cacheHitRate：DeepSeek 等厂商 KV 前缀缓存命中率；高命中率意味着更低成本
 */
class RunMetrics(
    private val startedAt: Long,
) {
    private val rounds = AtomicInteger()
    private val toolCalls = AtomicInteger()
    private val toolFailures = AtomicInteger()
    private val approvalRequests = AtomicInteger()
    private val streamRetries = AtomicInteger()
    private val droppedToolCalls = AtomicInteger()
    private val steeringMessages = AtomicInteger()
    private val followUpMessages = AtomicInteger()
    private val maxConsecutiveFailuresSeen = AtomicInteger()
    private val budgetContinuations = AtomicInteger()
    @Volatile private var circuitBreakerTripped = false
    @Volatile var outcome: String = "unknown"
        private set

    // Cache-hit tracking: accumulated across all rounds for the session run.
    private val totalInputTokens = AtomicLong()
    private val totalCacheReadTokens = AtomicLong()

    // 输入估算/实际对照：估算低于实际即系统性低估，低估会把折叠线余量悄悄吃光（400/413 溢出）。
    // 与 totalInputTokens 在同一批轮次上累计，故 summary 里的比值可直接判读。
    private val estimatedInputTokens = AtomicLong()

    fun roundStarted() { rounds.incrementAndGet() }

    /** 已开账的 provider 轮数（operation 程序计数器的 round 编号同源）。 */
    val roundsSoFar: Int get() = rounds.get()
    fun toolCallRecorded(failed: Boolean) {
        toolCalls.incrementAndGet()
        if (failed) toolFailures.incrementAndGet()
    }
    fun toolCallsDropped(count: Int) { droppedToolCalls.addAndGet(count) }
    fun approvalRequested() { approvalRequests.incrementAndGet() }
    fun streamRetry() { streamRetries.incrementAndGet() }
    fun steeringInjected(count: Int) { steeringMessages.addAndGet(count) }
    fun followUpConsumed(count: Int) { followUpMessages.addAndGet(count) }
    fun consecutiveFailuresObserved(count: Int) {
        maxConsecutiveFailuresSeen.updateAndGet { current -> maxOf(current, count) }
    }
    fun budgetContinued() { budgetContinuations.incrementAndGet() }
    fun circuitBreaker() { circuitBreakerTripped = true }
    fun finish(result: String) { outcome = result }

    /**
     * Record per-turn provider usage for cache-hit rate aggregation.
     * Call this once per successful provider response (after streaming completes).
     */
    fun recordUsage(usage: ChatUsage) {
        if (usage.inputTokens > 0) {
            totalInputTokens.addAndGet(usage.inputTokens)
            totalCacheReadTokens.addAndGet(usage.cacheReadTokens)
        }
    }

    /**
     * 记录一轮请求发出前引擎自己估算的输入 token 数（含工具 schema）。
     * 调用点必须与 [recordUsage] 落在同一批成功轮次上，否则比值会被失败轮污染。
     */
    fun recordEstimatedInput(tokens: Int) {
        if (tokens > 0) estimatedInputTokens.addAndGet(tokens.toLong())
    }

    fun summary(): String = buildString {
        append("Rounds=").append(rounds.get())
        append(", ToolCalls=").append(toolCalls.get())
        append(", ToolFailures=").append(toolFailures.get())
        append(", ApprovalRequests=").append(approvalRequests.get())
        append(", StreamRetries=").append(streamRetries.get())
        append(", DroppedToolCalls=").append(droppedToolCalls.get())
        append(", Steering=").append(steeringMessages.get())
        append(", FollowUps=").append(followUpMessages.get())
        append(", MaxConsecutiveFailures=").append(maxConsecutiveFailuresSeen.get())
        append(", BudgetContinuations=").append(budgetContinuations.get())
        append(", CircuitBreaker=").append(circuitBreakerTripped)
        // Cache-hit rate: show percentage when we have meaningful data; "n/a" when no
        // provider usage was reported (e.g. all turns failed before streaming started).
        val inputToks = totalInputTokens.get()
        val cacheHitStr = if (inputToks > 0) {
            val hitPct = (totalCacheReadTokens.get() * 100L / inputToks).toInt()
            "$hitPct% (${totalCacheReadTokens.get()}/${inputToks} tokens)"
        } else {
            "n/a"
        }
        append(", CacheHit=").append(cacheHitStr)
        // EstAccuracy = 估算/实际；< 100% 即低估（越低越危险：折叠线余量被悄悄吃光后溢出）
        val estimatedToks = estimatedInputTokens.get()
        append(", EstInput=").append(estimatedToks)
        append(", EstAccuracy=").append(
            when {
                estimatedToks <= 0 -> "n/a"
                inputToks <= 0 -> "n/a (no usage)"
                else -> "${estimatedToks * 100L / inputToks}%"
            },
        )
        append(", Outcome=").append(outcome)
    }
}
