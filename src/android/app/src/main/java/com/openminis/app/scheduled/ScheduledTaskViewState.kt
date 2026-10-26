package com.openminis.app.scheduled

/**
 * [T-scheduled-card-status-line] Display-only state for the task-list card
 * (ScheduledTasksScreen). Pure JVM so the "which badge does this card show"
 * branch is unit-testable without Android.
 *
 * The card used to derive everything inline and showed nothing once a task
 * stopped having a next occurrence: a fired ONCE task (disabled by
 * [ScheduledTaskManager.rescheduleNext]) and a repeating task whose end date
 * passed both rendered a bare "Once 09:00"-style line with the next-fire
 * segment empty, and the run count was invisible everywhere. [viewState]
 * makes the three outcomes explicit instead of implicit-by-missing-string.
 */
data class ScheduledTaskViewState(
    /** Recorded executions so far ([ScheduledTask.runHistory].size). */
    val runCount: Int,
    /** Next wall-clock firing, or null when the task will not fire again. */
    val nextFireMs: Long?,
    /**
     * True when the task has run its course: a ONCE task that already fired,
     * or a task whose active window (or CUSTOM day set) leaves no next slot.
     * Rendered as a terminal badge instead of an empty next-fire segment.
     */
    val ended: Boolean,
    /** True when the user disabled a task that has not run to completion. */
    val paused: Boolean,
) {
    val isActive: Boolean get() = nextFireMs != null
}

/**
 * Derive the card state for [task] at [now]. Rule order matters:
 *
 *  1. [ScheduledTask.nextTriggerMs] already folds in `enabled` — null for a
 *     disabled task — so a null next is ambiguous and gets classified below.
 *  2. A disabled ONCE task with [ScheduledTask.lastFiredAt] ran to
 *     completion (rescheduleNext disables it right after the single fire).
 *  3. An ENABLED task with no next slot is past its end date or lost its
 *     CUSTOM day set — terminal, shown as ended rather than silently dead.
 *  4. Everything else disabled is a user-paused task.
 */
fun ScheduledTask.viewState(now: Long = System.currentTimeMillis()): ScheduledTaskViewState {
    val next = nextTriggerMs(now)
    val ended = when {
        next != null -> false
        repeatMode == ScheduledRepeatMode.ONCE && lastFiredAt != null -> true
        enabled -> true
        else -> false
    }
    return ScheduledTaskViewState(
        runCount = runHistory.size,
        nextFireMs = next,
        ended = ended,
        paused = !enabled && !ended,
    )
}
