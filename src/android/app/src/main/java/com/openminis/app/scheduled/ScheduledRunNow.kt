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

    /** A "Run now" of [taskId] is being started (claimed, run not yet handed off). */
    fun isStarting(taskId: String): Boolean = synchronized(inFlight) { taskId in inFlight }

    /** Whether the menu offers "Run now": only while counting down to an unclaimed run. */
    fun canOffer(nextFireAt: Long?, running: Boolean, starting: Boolean): Boolean =
        nextFireAt != null && !running && !starting

    private fun isRunning(taskId: String): Boolean =
        taskId in ScheduledSessionHold.runningTaskIds.value.keys

    /**
     * Start the pending run of [taskId] now. Returns false, silently, when it
     * cannot: a start already in progress, a run already running, or nothing
     * pending any more (fired by its alarm meanwhile, disabled, exhausted).
     */
    fun start(
        context: Context,
        taskId: String,
        manager: ScheduledTaskManager = ScheduledTaskManager(context.applicationContext),
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        synchronized(inFlight) {
            if (isRunning(taskId) || !inFlight.add(taskId)) {
                AppLogger.info(TAG, "task=$taskId not started: already running or starting")
                return false
            }
        }
        // Checked under the store lock, before this start counts as running.
        val claimed = manager.claimRunNow(taskId, now, isRunning = ::isRunning)
        if (claimed == null) {
            synchronized(inFlight) { inFlight.remove(taskId) }
            AppLogger.info(TAG, "task=$taskId not started: no pending run to take")
            return false
        }
        // Held like the alarm holds it, so the chat reads "running" from here;
        // run() keeps its own hold until the fire's loop ends.
        ScheduledSessionHold.beginRun(taskId)
        synchronized(inFlight) { inFlight.remove(taskId) }
        val app = context.applicationContext
        scope.launch {
            try {
                ScheduledAgentRunner.run(app, claimed, waitForCompletion = false)
            } catch (t: Throwable) {
                AppLogger.error(TAG, "task=$taskId run now failed: ${t.message}")
            } finally {
                ScheduledSessionHold.endRun(taskId)
            }
        }
        AppLogger.info(TAG, "task=$taskId started now")
        return true
    }
}
