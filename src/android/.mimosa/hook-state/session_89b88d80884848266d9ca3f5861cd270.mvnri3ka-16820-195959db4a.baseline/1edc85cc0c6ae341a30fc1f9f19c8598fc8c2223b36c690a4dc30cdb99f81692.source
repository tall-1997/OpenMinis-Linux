package com.openminis.app.sandbox.kernel.remediation

import java.util.concurrent.atomic.AtomicLong

/**
 * Written by the hang watchdog, read by [RemediationLoop]. The main looper is
 * not on this path: a flag that is only collected on the frozen thread cannot
 * take effect during the freeze.
 */
object StallSignal {
    private val gapMs = AtomicLong(0L)
    private val observedAt = AtomicLong(0L)

    fun observe(sinceHeartbeatMs: Long, nowMs: Long = System.currentTimeMillis()) {
        gapMs.set(sinceHeartbeatMs.coerceAtLeast(0L))
        observedAt.set(nowMs)
    }

    fun gap(): Long = gapMs.get()

    fun clear() {
        gapMs.set(0L)
    }
}
