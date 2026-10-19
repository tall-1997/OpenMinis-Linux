package com.openminis.app.scheduled

import android.content.Context
import com.openminis.app.logging.AppLogger

/**
 * [T-android-scheduled-triggers] Fires [ScheduledTriggerKind.ON_COMPLETION]
 * tasks: a one-shot that runs when the scheduled task named by
 * [ScheduledTask.onCompletionOf] finishes its whole schedule, with
 * `{{result}}` replaced by that run's result.
 *
 * The upstream here is another SCHEDULED TASK. Upstream iOS also waits on
 * background agent jobs; that half needs `AgentJobRegistry`, which this fork
 * has not ported yet (it arrives with the helper-console batch). Adding the
 * listener before the registry exists would be a collector that can never
 * emit, so it is deliberately absent: when the registry lands, it calls
 * [onUpstreamFinished] from its completion callback and nothing here changes.
 *
 * Why "the whole schedule" and not "every run": an interval task with
 * `--count 6` notifies once, when its sixth check finishes. Firing the
 * dependent after check 1 would run it on a partial result six times over.
 * `scheduleFinished` is the single predicate that decides this.
 */
object ScheduledCompletionTriggers {

    private const val TAG = "ScheduledCompletion"

    /** Cap on the upstream result pasted into `{{result}}`. */
    const val RESULT_CAP_CHARS = 8_000

    /** Enabled on-completion tasks waiting for [upstreamId]. */
    internal fun dependents(tasks: List<ScheduledTask>, upstreamId: String): List<ScheduledTask> =
        tasks.filter {
            it.enabled && it.triggerKind == ScheduledTriggerKind.ON_COMPLETION &&
                it.onCompletionOf == upstreamId
        }

    /**
     * Whether [task]'s schedule has no fire left, read after one of its runs.
     * Pure, for tests.
     *  - AFTER / ON_COMPLETION / a calendar ONCE: their one fire was this run.
     *  - INTERVAL: only with a max count, once it is used up (counted at the
     *    alarm, so a run finishing after the LAST fire sees 0 left).
     *  - Repeating calendar: only when an end date leaves no next slot.
     * A deleted task is not "finished": nothing waiting on it fires.
     */
    internal fun scheduleFinished(task: ScheduledTask?, now: Long): Boolean {
        if (task == null) return false
        return when (task.triggerKind) {
            ScheduledTriggerKind.AFTER, ScheduledTriggerKind.ON_COMPLETION -> true
            ScheduledTriggerKind.INTERVAL -> task.remainingFires == 0
            ScheduledTriggerKind.CALENDAR ->
                task.repeatMode == ScheduledRepeatMode.ONCE ||
                    (task.endDateMs != null && task.copy(enabled = true).nextTriggerMs(now) == null)
        }
    }

    /** The prompt with `{{result}}` replaced; the result is capped. */
    internal fun substitute(prompt: String, result: String?): String =
        prompt.replace(ScheduledTask.RESULT_PLACEHOLDER, (result ?: "").take(RESULT_CAP_CHARS))

    /**
     * A scheduled run finished for [taskId] with [result]. Releases the tasks
     * waiting on it only when its SCHEDULE is over — see [scheduleFinished].
     * Called by [ScheduledAgentRunner] for alarm-driven runs; a manual "Run
     * now" must not release dependents, which is why the runner passes
     * `scheduledFire = true` only from the receiver.
     */
    suspend fun onScheduledRunFinished(context: Context, taskId: String, result: String?) {
        val task = ScheduledTaskManager(context).get(taskId)
        if (!scheduleFinished(task, System.currentTimeMillis())) return
        onUpstreamFinished(context, taskId, result)
    }

    /**
     * [upstreamId] finished with [result]: fire every task waiting for it.
     * Each is CLAIMED first (disabled, counted) so a repeated completion event
     * or a concurrent caller cannot fire it twice — that claim is also what
     * makes it a one-shot.
     */
    suspend fun onUpstreamFinished(context: Context, upstreamId: String, result: String?) {
        val manager = ScheduledTaskManager(context)
        val waiting = dependents(manager.list(), upstreamId)
        if (waiting.isEmpty()) return
        for (dep in waiting) {
            if (!manager.claimOneShotFire(dep.id)) continue
            AppLogger.info(
                TAG,
                "upstream ${upstreamId.take(8)} finished -> firing ${dep.id.take(8)} \"${dep.label}\"",
            )
            ScheduledAgentRunner.run(
                context,
                dep.copy(prompt = substitute(dep.prompt, result)),
                waitForCompletion = false,
                // Its own fire, so a task waiting on IT is released in turn.
                scheduledFire = true,
            )
        }
    }
}
