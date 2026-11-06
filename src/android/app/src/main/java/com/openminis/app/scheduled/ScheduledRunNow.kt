// [port-upstream-1.16] Ported from OpenMinis/OpenMinis@main (com.openminis.app.scheduled
// domain), adapted to this tree's task store and hold APIs; divergences marked.
package com.openminis.app.scheduled

import android.content.Context
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * [T-sched-run-now, port upstream 1.16 ScheduledRunNow] "Run now" on the
 * chat's timer pill / the session menu: fire the task's PENDING next run
 * immediately, through the same path an alarm takes —
 * [ScheduledTaskManager.claimRunNow] (the alarm's claim and transition) and
 * [ScheduledAgentRunner.run].
 *
 * Never more than one execution of a run:
 * - A start already in progress here wins: [inFlight] is a check-and-mark
 *   under one lock, so a double tap starts once.
 * - A run already in progress is refused, re-checked under the store's lock
 *   at claim time — not only when the menu opened.
 * - The alarm and "Run now" claim the run in the same atomic store step; the
 *   loser finds the slot taken ([ScheduledTask.runNowSlotMs]) and does
 *   nothing.
 */
object ScheduledRunNow {

    private const val TAG = "SchedRunNow"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val inFlight = HashSet<String>()

    /** A "Run now" of [taskId] is being started (claim through run handoff). */
    fun isStarting(taskId: String): Boolean = synchronized(inFlight) { taskId in inFlight }

    /** Whether the menu offers "Run now": only while counting down to an unclaimed run. */
    fun canOffer(nextFireAt: Long?, running: Boolean, starting: Boolean): Boolean =
        nextFireAt != null && !running && !starting

    private fun isRunning(taskId: String): Boolean =
        taskId in ScheduledSessionHold.runningTaskIds.value.keys

    /**
     * Start the pending run of [taskId] now. Returns whether a start was
     * handed off: false, silently, when it cannot even be attempted (a start
     * already in progress, or a run already running - both decided without
     * touching the store); true otherwise, with the claim's own outcome logged
     * here rather than returned: nothing pending any more (fired by its alarm
     * meanwhile, disabled, exhausted), or a claim / handoff failure.
     *
     * [FIX-R2-10, review §三 主线程 IO] The claim runs on this object's scope,
     * not on the caller's thread: the UI click paths (ScheduledCountdownPill /
     * SessionRunNowMenuItem) call this on Main, and the claim is a
     * SharedPreferences `commit()` plus [ScheduledTaskManager]'s
     * notification-channel init - both synchronous disk / system work that
     * must not run there. The atomic claim itself (alarm vs "Run now") is
     * unchanged, only deferred by the hop.
     *
     * [FIX-R2-9, review §三 异步与并发] [inFlight] is cleared in a `finally`,
     * so a claim that throws (or any other unexpected failure) can no longer
     * leave the task permanently "already starting" for the life of the
     * process.
     */
    fun start(
        context: Context,
        taskId: String,
        manager: ScheduledTaskManager? = null,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        synchronized(inFlight) {
            if (isRunning(taskId) || !inFlight.add(taskId)) {
                AppLogger.info(TAG, "task=$taskId not started: already running or starting")
                return false
            }
        }
        val app = context.applicationContext
        scope.launch {
            try {
                // Checked under the store lock, before this start counts as running.
                // The manager (and its notification channel) is built here too, so
                // no constructor work lands on the caller's thread either.
                val claimed = (manager ?: ScheduledTaskManager(app)).claimRunNow(taskId, now, isRunning = ::isRunning)
                if (claimed == null) {
                    AppLogger.info(TAG, "task=$taskId not started: no pending run to take")
                    return@launch
                }
                // Held like the alarm holds it, so the chat reads "running" from here;
                // run() keeps its own hold until the fire's loop ends.
                ScheduledSessionHold.beginRun(taskId)
                AppLogger.info(TAG, "task=$taskId started now")
                try {
                    ScheduledAgentRunner.run(app, claimed, waitForCompletion = false)
                } finally {
                    ScheduledSessionHold.endRun(taskId)
                }
            } catch (t: Throwable) {
                AppLogger.error(TAG, "task=$taskId run now failed: ${t.message}")
            } finally {
                synchronized(inFlight) { inFlight.remove(taskId) }
            }
        }
        return true
    }
}
