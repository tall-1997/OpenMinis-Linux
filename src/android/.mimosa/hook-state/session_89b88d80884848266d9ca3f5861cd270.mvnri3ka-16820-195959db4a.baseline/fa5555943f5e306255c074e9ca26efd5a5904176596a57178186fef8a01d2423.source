package com.openminis.app.sandbox.kernel

/**
 * Process-wide permit. Not per session: five sessions at "20/s each" is the
 * flood that froze the main looper.
 */
class TokenBucket(
    private val ratePerSec: Double,
    private val burst: Int,
) {
    private var tokens = burst.toDouble()
    private var lastMs = 0L

    @Synchronized
    fun tryTake(nowMs: Long, n: Int = 1): Boolean {
        refill(nowMs)
        if (tokens < n) return false
        tokens -= n
        return true
    }

    @Synchronized
    fun millisUntil(nowMs: Long, n: Int = 1): Long {
        refill(nowMs)
        if (tokens >= n) return 0L
        val missing = n - tokens
        return (missing / ratePerSec * 1000.0).toLong().coerceAtLeast(1L)
    }

    private fun refill(nowMs: Long) {
        if (lastMs == 0L) {
            lastMs = nowMs
            return
        }
        val elapsed = (nowMs - lastMs).coerceAtLeast(0L)
        lastMs = nowMs
        tokens = (tokens + elapsed * ratePerSec / 1000.0).coerceAtMost(burst.toDouble())
    }
}
