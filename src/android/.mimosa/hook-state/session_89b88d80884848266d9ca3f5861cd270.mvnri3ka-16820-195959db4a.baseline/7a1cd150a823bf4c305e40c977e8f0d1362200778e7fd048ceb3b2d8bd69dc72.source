package com.openminis.app.sandbox.kernel

/**
 * One bucket for every streaming session. A structural tool-line update does
 * not bypass it: that bypass is how 2,000 shell lines became 45,000 main-looper
 * tasks. Critical events (stream end) use a separate reserved lane so a flood
 * cannot swallow the turn boundary.
 */
object UIBus {
    // [T-line-budget-sync] Matched to PersistentShell's raised line budget —
    // 20 lines/s dropped whole output blocks while the byte budget allowed
    // 512 KiB/s. Dropped blocks still reach the final result via
    // StreamSink.snapshot(); only the live feed was starved.
    private val bucket = TokenBucket(ratePerSec = 250.0, burst = 500)
    private val critical = TokenBucket(ratePerSec = 4.0, burst = 4)

    fun admit(nowMs: Long, criticalEvent: Boolean): Boolean =
        if (criticalEvent) critical.tryTake(nowMs) else bucket.tryTake(nowMs)

    fun waitMs(nowMs: Long, criticalEvent: Boolean): Long =
        if (criticalEvent) critical.millisUntil(nowMs) else bucket.millisUntil(nowMs)
}
