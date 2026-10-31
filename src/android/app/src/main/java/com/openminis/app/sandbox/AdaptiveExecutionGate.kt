package com.openminis.app.sandbox

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Counts running commands, not cached shells. No permit is held while waiting. */
internal class AdaptiveExecutionGate {
    data class Limits(val total: Int, val heavy: Int)
    private class Ticket(val heavy: Boolean) { var blocked = true }
    private val lock = Any()
    private val queue = mutableListOf<Ticket>()
    private var running = 0
    private var heavyRunning = 0

    /**
     * [T-aptgate-event] Per-waiter wake channels. The old wait loop polled on
     * a fixed `delay(50)` tick, which spun every queued command 20x/second to
     * re-read limits/pressure. Now a waiter blocks on its own CONFLATED
     * channel and is woken the moment any command RELEASES its slot; the only
     * remaining timed wake is the pressure-probe cadence (kept at the same 1s
     * throttle as before — external state like memory pressure or a limits()
     * change is noticed on the next probe, so behaviour is identical except
     * that wake-ups arrive by notification instead of by polling).
     *
     * CONFLATED coalesces the burst of wakes a mass release produces into one
     * pending signal per waiter; one channel per waiter (instead of one shared
     * channel) means no waiter can consume another's wake and miss a slot.
     */
    private val waiters = mutableListOf<Channel<Unit>>()

    private fun notifyWaiters() {
        val snapshot = synchronized(lock) { waiters.toList() }
        for (w in snapshot) w.trySend(Unit)
    }

    suspend fun <T> run(
        heavy: Boolean,
        waitMs: Long,
        limits: () -> Limits,
        pressure: suspend () -> String?,
        onWaiting: (String) -> Unit,
        clock: () -> Long = { System.nanoTime() / 1_000_000 },
        block: suspend () -> T,
    ): T {
        val ticket = Ticket(heavy)
        val wake = Channel<Unit>(Channel.CONFLATED)
        // Identity matters: two same-class requests are distinct queue entries.
        synchronized(lock) { queue.add(ticket); waiters.add(wake) }
        val start = clock()
        var acquired = false
        var previous: String? = null
        var lastProbe: Long? = null
        var reason: String? = null
        try {
            while (true) {
                coroutineContext.ensureActive()
                val now = clock()
                check(waitMs <= 0 || lastProbe == null || now - start < waitMs) {
                    "排队超时，命令未启动（可在 设置→工具 限制 中调大队列超时）"
                }
                if (lastProbe == null || now - lastProbe >= 1_000L) {
                    reason = pressure()
                    lastProbe = now
                }
                val cap = limits()
                acquired = synchronized(lock) {
                    ticket.blocked = reason != null
                    val firstEligible = queue.firstOrNull {
                        !it.blocked && (!it.heavy || heavyRunning < cap.heavy)
                    }
                    if (reason == null && running < cap.total &&
                        (!heavy || heavyRunning < cap.heavy) && firstEligible === ticket) {
                        queue.removeAll { it === ticket }
                        running++
                        if (heavy) heavyRunning++
                        true
                    } else false
                }
                if (acquired) break
                // [T-aptqueue-copy] Same copy fix as SandboxResourceGate:
                // "command not started" read as a refusal while the command
                // is queued and starts as soon as capacity frees up.
                val message = "WAITING_RESOURCE: " + (reason ?: "重任务并发已满，命令排队等待资源，空闲后自动开始")
                if (message != previous) { onWaiting(message); previous = message }
                check(waitMs <= 0 || clock() - start < waitMs) { "排队超时，命令未启动: $message" }
                // [T-aptgate-event] Event-driven wait, replacing delay(50):
                // wake on a release notification, or — if none arrives — at the
                // earlier of (a) the next 1s pressure-probe window and (b) the
                // queue deadline, so the waitMs timeout stays exactly as sharp
                // as the old 50ms tick produced. trySend never blocks, so a
                // releaser can never be delayed by a waiter.
                val nowMs = clock()
                val untilProbe = 1_000L - (nowMs - (lastProbe ?: nowMs))
                val untilDeadline = if (waitMs > 0) start + waitMs - nowMs else Long.MAX_VALUE
                withTimeoutOrNull(minOf(untilProbe, untilDeadline).coerceAtLeast(0L)) {
                    wake.receive()
                }
            }
            onWaiting("RUNNING: resource admission granted")
            return block()
        } finally {
            val released: Boolean
            synchronized(lock) {
                queue.removeAll { it === ticket }
                waiters.remove(wake)
                released = acquired
                if (acquired) { running--; if (heavy) heavyRunning-- }
            }
            // A release is the one event that can unblock the head of the
            // queue — wake the survivors at once. (Waiters leaving via
            // timeout/cancel free no capacity, so they do not notify.)
            if (released) notifyWaiters()
            wake.close()
        }
    }
}
