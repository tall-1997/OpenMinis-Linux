package com.openminis.app.goal

/**
 * [T-drift-guard] Host-side pacing for the periodic "are we still on task?"
 * check. Pure: no I/O, no Android types; the caller owns delivery.
 *
 * The counter comes from the host-maintained WorkingMemoryLedger (one entry
 * per tool round), so the guard cannot be starved by the model forgetting to
 * count. Buckets are stable: 10..19 -> 10, 20..29 -> 20, ...
 *
 * [T-drift-guard-config] The cadence is policy, not a constant: the shipped
 * default (10) is mirrored in [HarnessConfig.driftGuardEveryN] and callers
 * pass the resolved value through; these functions take it as a parameter so
 * a per-model override changes the cadence without touching this file.
 */
object DriftGuard {
    /** Shipped default; must stay byte-identical to HarnessConfig.driftGuardEveryN. */
    const val EVERY_TOOL_CALLS = 10

    fun bucketFor(toolCalls: Int, everyToolCalls: Int = EVERY_TOOL_CALLS): Int {
        require(everyToolCalls > 0) { "everyToolCalls must be positive (was $everyToolCalls)" }
        if (toolCalls < everyToolCalls) return 0
        return (toolCalls / everyToolCalls) * everyToolCalls
    }

    fun markerFor(bucket: Int): String = "<!-- drift-check:$bucket -->"

    fun buildNote(marker: String, goalPrompt: String, bucket: Int, everyToolCalls: Int = EVERY_TOOL_CALLS): String = buildString {
        appendLine(marker)
        appendLine("## Drift check — $bucket tool calls")
        appendLine("Before the next tool call, answer these three lines:")
        appendLine("1) Objective: restate the active goal in one sentence.")
        appendLine("2) Last actions: what the last $everyToolCalls tool calls actually produced.")
        appendLine("3) Verdict: on-track | drifting | blocked. If drifting, call update_goal(op=rollback) to restore the newest checkpoint, then update the plan and next_step before the next tool call.")
        appendLine()
        append(goalPrompt)
    }
}
