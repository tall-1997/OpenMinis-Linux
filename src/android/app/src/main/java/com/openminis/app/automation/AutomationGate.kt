package com.openminis.app.automation

import java.util.concurrent.ConcurrentHashMap

/**
 * [T-android-automation-rules] Fire-admission gate shared by every rule:
 *
 *  - 60 s per-rule debounce — a notification burst (message + group summary +
 *    app update) or a Wi-Fi flap must not run the same rule twice. Debounced
 *    attempts are dropped silently (no run row): they are state flapping, not
 *    firings, and recording them would flood the run log without bound.
 *  - 24 h rolling cap of 50 actionable runs per rule — a rule whose trigger
 *    loops back into itself (e.g. an automation firing an agent that posts a
 *    notification the same rule listens for) is stopped here. Over-cap
 *    attempts ARE recorded as `skipped_rate_limit` runs so the user can see
 *    why the rule went quiet (one row per debounce window at most, because
 *    the failed attempt keeps the slot); skipped rows do not count toward the
 *    cap itself, so the cap tracks real firings only.
 *
 * Pure JVM with an injected clock so the debounce window and the rolling
 * 24 h window are testable without Android.
 */
class AutomationGate(
    private val clock: () -> Long,
    private val recentActionableRuns: (ruleId: String, sinceMs: Long) -> Int,
) {

    /** Last CLAIMED fire per rule — set when an attempt passes the debounce. */
    private val lastFired = ConcurrentHashMap<String, Long>()

    data class Decision(
        val allowed: Boolean,
        /** "debounced" | "rate_limited" | null when allowed. */
        val reason: String? = null,
    )

    /**
     * Atomically claim the rule's debounce slot (and check the cap). Callers
     * must fire exactly when this returns allowed=true — the claim is the
     * debounce memory, so two concurrent events cannot both pass.
     */
    fun tryAcquire(
        ruleId: String,
        debounceMs: Long = DEBOUNCE_MS,
        cap: Int = DAILY_CAP,
        windowMs: Long = WINDOW_MS,
    ): Decision {
        val now = clock()
        // compute() is atomic per key, and the freshClaim flag is captured in
        // the same critical section: a notification burst landing on two
        // threads (or two events with identical timestamps) sees one of them
        // win the "now" slot, the other re-uses it and is dropped.
        var freshClaim = false
        lastFired.compute(ruleId) { _, prev ->
            if (prev == null || now - prev >= debounceMs) {
                freshClaim = true
                now
            } else {
                freshClaim = false
                prev
            }
        }
        if (!freshClaim) return Decision(false, "debounced")
        if (recentActionableRuns(ruleId, now - windowMs) >= cap) {
            // Keep the claim: the next attempt inside the debounce window is
            // dropped as debounced, which bounds the skipped-run flood from a
            // trigger storm while capped.
            return Decision(false, "rate_limited")
        }
        return Decision(true, null)
    }

    /** Drop the debounce memory (rule deleted or its spec replaced). */
    fun forget(ruleId: String) {
        lastFired.remove(ruleId)
    }

    companion object {
        const val DEBOUNCE_MS = 60_000L
        const val DAILY_CAP = 50
        const val WINDOW_MS = 24 * 60 * 60_000L
    }
}
