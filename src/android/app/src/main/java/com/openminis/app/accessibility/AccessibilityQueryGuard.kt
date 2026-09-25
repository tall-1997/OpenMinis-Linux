package com.openminis.app.accessibility

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Isolates accessibility Binder queries from their caller. A timeout cannot
 * cancel the remote call itself, so late results are discarded and repeated
 * timeouts open a circuit instead of queueing more blocking work.
 */
internal object AccessibilityQueryGuard {
    private const val TIMEOUT_MS = 1_500L
    private const val FAILURES_BEFORE_OPEN = 3
    private const val OPEN_MS = 15_000L
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "a11y-query").apply { isDaemon = true }
    }
    private val failures = AtomicInteger()
    private val openedUntil = AtomicLong()

    fun <T> query(fallback: T, operation: Callable<T>): T {
        val now = System.currentTimeMillis()
        if (now < openedUntil.get()) return fallback
        val future = executor.submit(operation)
        return try {
            val value = future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            failures.set(0)
            value
        } catch (_: Exception) {
            future.cancel(true)
            if (failures.incrementAndGet() >= FAILURES_BEFORE_OPEN) {
                openedUntil.set(System.currentTimeMillis() + OPEN_MS)
                failures.set(0)
            }
            fallback
        }
    }
}
