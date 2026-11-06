package com.openminis.app.scheduled

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

/**
 * [T-scheduled-active-session-android, port upstream 1.16 ScheduledSessionHold]
 * Which chats a scheduled task keeps ACTIVE between its fires: they are not
 * finished, they are waiting to be woken.
 *
 * Minis model adaptation: upstream counts only a follow-up aimed back at its
 * creator chat (`creatorSessionId`), a field the minis [ScheduledTask] schema
 * does not carry. The equivalent "this chat is still working" test here is
 * the task TARGETING an existing chat — [ScheduledTargetMode.AppendToSession]
 * and [ScheduledTargetMode.RerunMessage]. A NewSession task materialises its
 * chat at fire time, so there is nothing to hold beforehand.
 *
 * The result feeds:
 *  - [com.openminis.app.service.SessionActivityTracker.setScheduledSessions]
 *    (foreground service continuation between fires),
 *  - [com.openminis.app.ui.chat.ChatViewModelStore] (the session's cached
 *    ViewModel — its context, drafts and running state — survives idle trim
 *    while a task is still due to write into it).
 */
object ScheduledSessionHold {

    /**
     * The chat [task] keeps active at [now], or null. Only a task aimed at an
     * existing chat counts, and only while it still has a next occurrence —
     * a disabled task, an exhausted one (fired ONCE, past its end date) or a
     * lost target holds nothing.
     */
    fun heldSession(task: ScheduledTask, now: Long): String? {
        if (!task.enabled) return null
        val target = task.targetMode.sessionIdOrNull ?: return null
        return target.takeIf { task.nextTriggerMs(now) != null }
    }

    /**
     * Whether [task] would still fire (upstream `isLive`): enabled with a
     * pending slot. What "Run now" may take, and what its confirmation counts.
     */
    fun isLive(task: ScheduledTask, now: Long): Boolean =
        task.enabled && task.nextTriggerMs(now) != null

    fun heldSessions(tasks: List<ScheduledTask>, now: Long): Set<String> =
        tasks.mapNotNullTo(LinkedHashSet()) { heldSession(it, now) }

    /**
     * Tasks whose fire is being run right now (alarm received → agent loop
     * finished), by task id, counted per run. [ScheduledAgentRunner.run]
     * holds it for the whole run, which covers the two moments the store
     * alone cannot: the handoff before the turn starts, and the LAST fire of
     * a task that the alarm already disabled.
     */
    private val _runningRuns = MutableStateFlow<Map<String, Int>>(emptyMap())
    val runningTaskIds: StateFlow<Map<String, Int>> = _runningRuns.asStateFlow()

    fun beginRun(taskId: String) {
        _runningRuns.update { it + (taskId to ((it[taskId] ?: 0) + 1)) }
    }

    fun endRun(taskId: String) {
        _runningRuns.update { m ->
            val n = (m[taskId] ?: 0) - 1
            if (n > 0) m + (taskId to n) else m - taskId
        }
    }

    /**
     * The chat a task keeps active while one of its fires is being run,
     * enabled or not (the alarm disables a task at its last fire, before the
     * run): the same target test as [heldSession], without the schedule.
     */
    fun runHold(task: ScheduledTask): String? = task.targetMode.sessionIdOrNull

    /**
     * The chats scheduled tasks keep active, re-derived on every change to
     * the persisted task list (create, edit, enable / disable, delete, each
     * fire's bookkeeping) and to the runs in flight. Persisted input, so it
     * is right again from the first emission after a process restart.
     */
    fun observe(context: Context): Flow<Set<String>> =
        combine(
            ScheduledTaskStore(context.applicationContext).observe(),
            _runningRuns,
        ) { tasks, running ->
            heldNow(tasks, running.keys, System.currentTimeMillis())
        }.distinctUntilChanged()
            .onEach { _heldSnapshot.value = it }

    /** Latest held set, for synchronous readers (the VM-store trim is @Synchronized on main). */
    private val _heldSnapshot = MutableStateFlow<Set<String>>(emptySet())

    fun heldSnapshot(): Set<String> = _heldSnapshot.value

    /**
     * What [observe] emits for one set of inputs: the chats held by their
     * schedule, plus the chat of every task whose fire is being run right
     * now. Pure, so a whole fire sequence can be replayed in tests.
     */
    fun heldNow(tasks: List<ScheduledTask>, runningIds: Set<String>, now: Long): Set<String> =
        heldSessions(tasks, now) +
            tasks.filter { it.id in runningIds }.mapNotNull(::runHold)
}
