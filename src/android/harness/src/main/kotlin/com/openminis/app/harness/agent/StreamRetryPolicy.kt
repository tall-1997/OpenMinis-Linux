package com.openminis.app.harness.agent

import com.openminis.app.data.model.FallbackStrategy
import com.openminis.app.data.model.LLMError

/**
 * [T-loop-retry-cut] 流轮次的重试/回退决策链（纯半边）。
 *
 * 原来整条链内联在 ChatViewModelAgentLoopExt 的 catch 块里（~120 行判定与
 * UI 编排交错）——判定规则（哪些错可同 provider 重试、429 何时跳过直换端点、
 * 永久容量永不重试、超时只在 CONNECT 相位重试）是纯逻辑，搬到这里可测；
 * catch 块只留 UI 编排（倒计时/内联错误/块回滚/provider 切换副作用）。
 *
 * 决策表（与原实现逐条对齐）：
 * - 瞬态 = Network/Transient/RateLimited/5xx/CONNECT 超时，且非永久容量
 * - 429 且还有组员 → 跳过同 provider 重试直接换端点（60s 中继窗口内别把
 *   同一把 key 锤满 1/2/4/8/16s 阶梯）；429 是最后一个候选时保留至多一次
 * - 永久容量（quota/无渠道）→ 永不同 provider 重试
 * - READ/TTFB 超时 → 不算瞬态，直落回退（isFallbackable 已含 Timeout）
 * - 回退 = always 策略 || isFallbackable || 429 || 5xx || 永久容量
 * - 同桶候选（同 callGateKey）跳过并丢弃——换端点才有意义，同 key 换壳无用
 */
object StreamRetryPolicy {

    /** 5xx 状态码出现在 ProviderError.detail 的形态（原 ChatViewModel.HTTP_5XX_STATUS_RE）。 */
    val HTTP_5XX_STATUS_RE = Regex("""\[5\d{2}\]""")

    /** 同 provider 重试的退避阶梯（原 ChatViewModel.AUTO_RETRY_DELAYS_SEC）。 */
    val AUTO_RETRY_DELAYS_SEC: IntArray = HttpRetryAfter.DELAYS_SEC

    /** 宿主把异常折进来的分类形状——判定规则全在这，宿主只搬运。 */
    data class ErrorClass(
        val isRateLimit: Boolean,
        val is5xx: Boolean,
        val isPermanentCapacity: Boolean,
        val isTimeoutConnect: Boolean,
        val isNetworkOrTransient: Boolean,
        val isFallbackable: Boolean,
        val retryAfterSeconds: Int?,
        val message: String?,
        val detail: String,
    ) {
        val isTransient: Boolean
            get() = (isNetworkOrTransient || isRateLimit || is5xx || isTimeoutConnect) && !isPermanentCapacity
    }

    /** 把异常按规则分类（LLMError 在 core:model，harness 直判）。 */
    fun classify(error: Throwable): ErrorClass {
        val llm = error as? LLMError
        val providerDetail = (llm as? LLMError.ProviderError)?.detail
        return ErrorClass(
            isRateLimit = llm is LLMError.RateLimited,
            is5xx = providerDetail != null && HTTP_5XX_STATUS_RE.containsMatchIn(providerDetail),
            isPermanentCapacity = providerDetail != null && (
                providerDetail.contains("[429]") || HttpRetryAfter.isPermanentCapacityBody(providerDetail)
                ),
            isTimeoutConnect = llm is LLMError.Timeout && llm.phase == LLMError.Timeout.TimeoutPhase.CONNECT,
            isNetworkOrTransient = llm is LLMError.NetworkError || llm is LLMError.TransientError,
            isFallbackable = llm?.isFallbackable == true,
            retryAfterSeconds = (llm as? LLMError.RateLimited)?.retryAfterSeconds,
            message = llm?.message ?: error.message,
            detail = providerDetail ?: "",
        )
    }

    /** catch 块的三分支决策。 */
    sealed interface Decision {
        /** 同 provider 重试：退避秒数 + 本次 attempt 序号（1 起）。 */
        data class RetrySameProvider(val delaySec: Int, val attempt: Int) : Decision

        /** 尝试回退到下一个组员（是否真有候选由 [nextCandidate] 决定）。 */
        data class TryFallback(val reason: String) : Decision

        /** 重抛（重试耗尽且无可用回退）。 */
        object Rethrow : Decision
    }

    fun decide(
        error: ErrorClass,
        retryAttempt: Int,
        maxRetries: Int,
        remainingFallbacks: Int,
        strategy: FallbackStrategy,
    ): Decision {
        val skipSameProviderRetry = (error.isRateLimit && remainingFallbacks > 0) || error.isPermanentCapacity
        val sameProviderBudget = if (error.isRateLimit) minOf(maxRetries, 1) else maxRetries
        if (error.isTransient && !skipSameProviderRetry && sameProviderBudget > 0 && retryAttempt < sameProviderBudget) {
            val delaySec = HttpRetryAfter.delaySeconds(
                retryAttempt, error.retryAfterSeconds, AUTO_RETRY_DELAYS_SEC,
            )
            return Decision.RetrySameProvider(delaySec, retryAttempt + 1)
        }
        val shouldFallback = strategy == FallbackStrategy.always ||
            error.isFallbackable || error.isRateLimit || error.is5xx || error.isPermanentCapacity
        if (shouldFallback && remainingFallbacks > 0) {
            return Decision.TryFallback(fallbackReason(error))
        }
        return Decision.Rethrow
    }

    /** 回退轨迹里的原因行（原 catch 块的 when 分支）。 */
    fun fallbackReason(error: ErrorClass): String = when {
        error.isRateLimit -> error.message ?: "Rate limited"
        error.detail.isNotEmpty() -> error.detail
        else -> error.message ?: "Error"
    }

    /**
     * 该错误是否应走回退路径（不看剩余候选数）——终局路径用它决定要不要
     * 建轨迹：单成员组 429 时没有候选可换，但仍应把跳过的组员列出来。
     */
    fun shouldFallback(error: ErrorClass, strategy: FallbackStrategy): Boolean =
        strategy == FallbackStrategy.always ||
            error.isFallbackable || error.isRateLimit || error.is5xx || error.isPermanentCapacity

    /**
     * 从剩余候选里取下一个**不同桶**的候选；同桶（同 callGateKey）候选
     * 被丢弃——与原 while-removeFirstOrNull 循环行为一致。桶比较镜像
     * ProviderKeyGate.sameBucket（空桶视为不同桶）。
     */
    fun <T> nextCandidate(
        remaining: MutableList<T>,
        currentBucket: String?,
        bucketOf: (T) -> String?,
    ): T? {
        var candidate = remaining.removeFirstOrNull()
        while (candidate != null && sameBucket(bucketOf(candidate), currentBucket)) {
            candidate = remaining.removeFirstOrNull()
        }
        return candidate
    }

    fun sameBucket(a: String?, b: String?): Boolean = !a.isNullOrBlank() && a == b
}
