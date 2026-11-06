package com.openminis.app.scheduled

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.openminis.app.logging.AppLogger
import com.openminis.app.service.AgentForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [T-sched-inapp-timer, port upstream 1.16 ScheduledInAppTimer] Fires a timed
 * scheduled task from inside the process at its exact time, next to its
 * AlarmManager alarm.
 *
 * Without SCHEDULE_EXACT_ALARM (denied by default on Android 14+) the alarm
 * is inexact and the system may deliver it minutes late, so the chat's
 * countdown pill sat at 00:00 with the app open in front of it. While the
 * process is alive this timer runs the task on time instead; the alarm stays
 * the fallback for a dead or frozen process.
 *
 * Whichever reaches a run first takes it; the other finds it taken:
 * - The timer claims through [ScheduledRunNow.start] with the run key read
 *   when it started waiting ([ScheduledTaskManager.pendingRunKey]). If the
 *   alarm fired that run first, the key has moved on and the claim fails.
 * - The claim records the slot ([ScheduledTask.runNowSlotMs]) and re-arms the
 *   alarm for the next run, so an alarm for the taken run that was already on
 *   its way is turned away by [ScheduledTaskManager.rescheduleNext].
 *
 * It fires only while Minis is in front or its foreground service is up: a
 * run started from the background would not be allowed to start the service
 * and could be killed after its run was claimed. Otherwise the alarm, which
 * may start the service, keeps the run.
 */
object ScheduledInAppTimer {

    private const val TAG = "SchedInAppTimer"

    /** Re-read the wall clock at least this often while waiting, like iOS's 30 s step. */
    private const val SLEEP_STEP_MS = 30_000L

    private val started = AtomicBoolean(false)

    /**
     * Held for the life of the process. The store's change listener is only
     * weakly referenced by SharedPreferences, so a collector parked on it with
     * nothing else pointing at it could be collected and never wake again.
     */
    @Volatile
    private var job: Job? = null

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        job = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            // Every change to the task list (create, edit, a fire's bookkeeping)
            // re-plans from scratch.
            ScheduledTaskStore(app).observe().collectLatest { tasks -> fireAsDue(app, tasks) }
        }
    }

    private suspend fun fireAsDue(app: Context, tasks: List<ScheduledTask>) {
        // Runs this list has already been tried for: a refused or skipped one
        // waits for the next change (the alarm's fire is one) instead of spinning.
        val tried = HashSet<String>()
        while (true) {
            val due = nextDue(tasks, System.currentTimeMillis(), tried) ?: return
            AppLogger.info(TAG, "task=${due.task.id} ${due.runKey} waiting until ${due.at}")
            sleepUntil(due.at)
            tried += due.tryKey
            if (!canFire()) {
                AppLogger.info(TAG, "task=${due.task.id} due, Minis in background - left to its alarm")
                continue
            }
            // Just before the slot, so the claim takes the run this wait was for.
            val ok = ScheduledRunNow.start(app, due.task.id, now = due.at - 1)
            val lateMs = System.currentTimeMillis() - due.at
            AppLogger.info(TAG, "task=${due.task.id} ${due.runKey} fired=$ok late=${lateMs}ms")
        }
    }

    internal data class Due(val task: ScheduledTask, val at: Long, val runKey: String) {
        val tryKey: String get() = "${task.id}|$runKey"
    }

    /** The soonest timed run not yet tried. Pure, for tests. */
    internal fun nextDue(tasks: List<ScheduledTask>, now: Long, tried: Set<String>): Due? =
        tasks.asSequence()
            .filter { it.enabled }
            .mapNotNull { t ->
                val at = t.nextTriggerMs(now) ?: return@mapNotNull null
                val key = ScheduledTaskManager.pendingRunKey(t, now) ?: return@mapNotNull null
                Due(t, at, key).takeIf { it.tryKey !in tried }
            }
            .minByOrNull { it.at }

    private suspend fun sleepUntil(at: Long) {
        while (true) {
            val remaining = at - System.currentTimeMillis()
            if (remaining <= 0) return
            delay(minOf(remaining, SLEEP_STEP_MS))
        }
    }

    private fun canFire(): Boolean =
        AgentForegroundService.isRunning || runCatching {
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }.getOrDefault(false)
}
