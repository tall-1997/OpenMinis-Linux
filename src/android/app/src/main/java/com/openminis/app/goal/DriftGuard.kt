package com.openminis.app.goal

/**
 * [T-drift-guard] Host-side pacing for the periodic "are we still on task?"
 * check. Pure: no I/O, no Android types; the caller owns delivery.
 *
 * The counter comes from the host-maintained WorkingMemoryLedger (one entry
 * per tool round), so the guard cannot be starved by the model forgetting to
 * count. Buckets are stable: 10..19 -> 10, 20..29 -> 20, ...
 */
object DriftGuard {
    const val EVERY_TOOL_CALLS = 10
    const val MIN_TOOL_CALLS = 10

    fun bucketFor(toolCalls: Int): Int {
        if (toolCalls < MIN_TOOL_CALLS) return 0
        return (toolCalls / EVERY_TOOL_CALLS) * EVERY_TOOL_CALLS
    }

    fun markerFor(bucket: Int): String = "<!-- drift-check:$bucket -->"

    fun buildNote(marker: String, goalPrompt: String, bucket: Int): String = buildString {
        appendLine(marker)
        appendLine("## Drift check — $bucket tool calls")
        appendLine("Before the next tool call, answer these three lines:")
        appendLine("1) Objective: restate the active goal in one sentence.")
        appendLine("2) Last actions: what the last $EVERY_TOOL_CALLS tool calls actually produced.")
        appendLine("3) Verdict: on-track | drifting | blocked. If drifting, call update_goal(op=rollback) to restore the newest checkpoint, then update the plan and next_step before the next tool call.")
        appendLine()
        append(goalPrompt)
    }
}
