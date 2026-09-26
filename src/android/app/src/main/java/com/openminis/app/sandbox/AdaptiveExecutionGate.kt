package com.openminis.app.sandbox

import kotlinx.coroutines.delay
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
        // Identity matters: two same-class requests are distinct queue entries.
        synchronized(lock) { queue.add(ticket) }
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
                    "Execution queue timed out; command was not started"
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
                val message = "WAITING_RESOURCE: " + (reason ?: "heavy task capacity unavailable; command not started")
                if (message != previous) { onWaiting(message); previous = message }
                check(waitMs <= 0 || clock() - start < waitMs) { "Execution queue timed out; command was not started: $message" }
                delay(50)
            }
            onWaiting("RUNNING: resource admission granted")
            return block()
        } finally {
            synchronized(lock) {
                queue.removeAll { it === ticket }
                if (acquired) { running--; if (heavy) heavyRunning-- }
            }
        }
    }
}
