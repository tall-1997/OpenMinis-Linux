package com.openminis.app.tools

import com.openminis.app.harness.effects.RetryPolicy
import kotlinx.coroutines.delay

/**
 * [T-recovery-layer] P0-3 统一重试层的重试半边：把 harness 的 [RetryPolicy]
 * 接进工具派发的瞬态失败路径。
 *
 * 只重试**瞬态**失败（网络错 / 超时）：参数错、权限错、not-found 重试一万次
 * 也是同一个错，弹回模型自我纠正才是正路。退避按 policy 的指数序列
 * （NETWORK_DEFAULT = 5 次重试、1.5s 起翻倍），延迟走 kotlinx delay，可被
 * runTest 虚拟时间跳过。
 *
 * provider 层的 HTTP 重试（HttpRetryAfter / StallResume）管的是连接与流；
 * 这里管的是「工具语义层面的瞬态失败」，两层不重叠：provider 重试发生在
 * 一次模型调用内部，本层发生在一次工具执行内部。
 */
object ToolRetry {

    fun isTransient(result: ToolExecutionResult): Boolean =
        !result.success &&
            (result.errorCode == ToolErrorCode.NETWORK_ERROR || result.errorCode == ToolErrorCode.TIMEOUT)

    suspend fun run(
        policy: RetryPolicy = RetryPolicy.NETWORK_DEFAULT,
        isTransient: (ToolExecutionResult) -> Boolean = ::isTransient,
        block: suspend () -> ToolExecutionResult,
    ): ToolExecutionResult {
        var attempt = 1
        var last = block()
        while (isTransient(last) && attempt < policy.maxAttempts) {
            delay(policy.delayForRetry(attempt))
            attempt++
            last = block()
        }
        return last
    }
}
