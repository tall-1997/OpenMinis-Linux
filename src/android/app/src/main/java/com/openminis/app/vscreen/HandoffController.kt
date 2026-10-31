package com.openminis.app.vscreen

import com.openminis.app.logging.AppLogger
import com.openminis.app.vscreen.HandoffStateMachine.Action
import com.openminis.app.vscreen.HandoffStateMachine.State
import com.openminis.app.vscreen.TaskMover.ListOutcome
import com.openminis.app.vscreen.TaskMover.MoveOutcome

/**
 * [T-android-vscreen2] Orchestrates the hand_to_user flow (DSH
 * AGENT-SCREEN-V2.md §2 semantics, adapted to our synchronous tool model —
 * the call returns as soon as the task is on the user's screen and the
 * notification is up; the three resolution actions arrive later via the
 * notification receiver and every state change is persisted + broadcast).
 *
 * Flow of [start]:
 *  1. resolve the display's TOP task (login/payment pages often open their
 *     own task, so the newest task on the display is the right one);
 *  2. move it to the main display (display 0) via [TaskMover];
 *  3. restore the app's sound ([AudioMuter.unmute]) — the user is about to
 *     look at it;
 *  4. persist a WAITING [HandoffRecord] and post the "needs you"
 *     notification (three action buttons, see [HandoffNotifier]).
 *
 * [resolve] applies 处理好了 (move back + re-mute), 我自己做完了 (keep on
 * main) and 不做了 (leave on main, agent stops the step) through the pure
 * [HandoffStateMachine].
 *
 * Blocking by design (Shizuku binder / shell calls); callers wrap it in a
 * thread on the notification path. All decision logic goes through seams so
 * JVM tests exercise it without Android.
 */
class HandoffController(
    private val store: HandoffStore,
    private val mover: TaskMover,
    private val muter: AudioMuter,
    private val notifier: (HandoffRecord) -> Boolean,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val mainDisplayId: Int = DEFAULT_MAIN_DISPLAY,
) {

    sealed class StartOutcome {
        data class Started(val record: HandoffRecord, val soundRestored: Boolean, val notificationPosted: Boolean) : StartOutcome()
        data class AlreadyWaiting(val record: HandoffRecord) : StartOutcome()
        data class NoScreen(val message: String) : StartOutcome()
        data class MoveUnavailable(val message: String) : StartOutcome()
        data class MoveFailed(val message: String) : StartOutcome()
    }

    sealed class ResolveOutcome {
        data class Resolved(val record: HandoffRecord) : ResolveOutcome()
        /** MOVE_BACK_DONE whose move-back failed — still WAITING, note carries the reason. */
        data class MoveBackFailed(val record: HandoffRecord) : ResolveOutcome()
        data class NotWaiting(val message: String) : ResolveOutcome()
    }

    /** Hand the top task of [displayId] to the user. */
    fun start(displayId: Int, kind: String, reason: String): StartOutcome {
        val existing = store.get(displayId)
        if (existing != null && existing.state == State.WAITING) {
            return StartOutcome.AlreadyWaiting(existing)
        }
        val tasks = mover.listTasks()
        val top = when (tasks) {
            is ListOutcome.Rows -> tasks.rows.firstOrNull { it.displayId == displayId }
            is ListOutcome.Unavailable -> return StartOutcome.MoveUnavailable(
                "task move unavailable on this ROM; ask the user to handle it manually " +
                    "(cannot even list tasks to identify what to hand over)",
            )
        } ?: return StartOutcome.MoveFailed(
            "no task is running on display $displayId — there is nothing to hand over; " +
                "launch the app first (action=launch) and retry",
        )
        if (top.packageName.isBlank()) {
            return StartOutcome.MoveFailed("the top task on display $displayId has no resolvable package")
        }
        val move = mover.move(top.taskId, mainDisplayId)
        val moved = when (move) {
            is MoveOutcome.Moved -> true
            is MoveOutcome.Unavailable -> return StartOutcome.MoveUnavailable(move.message)
            is MoveOutcome.Failed -> return StartOutcome.MoveFailed("moving task ${top.taskId} to the main screen failed: ${move.message}")
        }
        val restoreNote = muter.unmute(top.packageName)
        val record = HandoffRecord(
            displayId = displayId,
            taskId = top.taskId,
            packageName = top.packageName,
            topActivity = top.topActivity,
            kind = kind,
            reason = reason,
            state = State.WAITING,
            startedAtMs = nowMs(),
            lastChangedAtMs = nowMs(),
            note = buildString {
                append(
                    when (move) {
                        is MoveOutcome.Moved ->
                            if (move.verified) "moved to the main screen via ${move.via}"
                            else "move reported by ${move.via} but NOT verified (task listing unavailable)"
                        else -> ""
                    },
                )
                restoreNote?.let { append("; $it") }
            },
        )
        store.put(record)
        val posted = runCatching { notifier(record) }
            .onFailure { AppLogger.warning(TAG, "handoff notification failed: ${it.message}") }
            .getOrDefault(false)
        AppLogger.info(TAG, "hand_to_user: task ${top.taskId} (${top.packageName}) → main display, posted=$posted")
        return StartOutcome.Started(record, soundRestored = restoreNote != null, notificationPosted = posted)
    }

    /**
     * Apply a user choice (from the notification buttons). The state machine
     * decides the transition; MOVE_BACK_DONE additionally performs the real
     * move (failures keep the handoff WAITING and are reported in the note).
     */
    fun resolve(displayId: Int, action: Action): ResolveOutcome {
        val record = store.get(displayId)
            ?: return ResolveOutcome.NotWaiting("no handoff record for display $displayId")
        val moveBackOk = when (action) {
            Action.MOVE_BACK_DONE -> {
                val move = mover.move(record.taskId, displayId)
                when (move) {
                    is MoveOutcome.Moved -> {
                        muter.mute(displayId, record.packageName)
                        true
                    }
                    is MoveOutcome.Unavailable -> false
                    is MoveOutcome.Failed -> false
                }
            }
            else -> true
        }
        val transition = HandoffStateMachine.transition(record.state, action, moveBackOk)
        val t = when (transition) {
            is HandoffStateMachine.Transition.Rejected -> return ResolveOutcome.NotWaiting(transition.reason)
            is HandoffStateMachine.Transition.Allowed -> transition
        }
        val moveBackNote = t.note
        val updated = record.copy(
            state = t.state,
            lastChangedAtMs = nowMs(),
            note = listOfNotNull(record.note.takeIf { it.isNotBlank() }, moveBackNote)
                .joinToString("; ")
                .take(500),
        )
        store.put(updated)
        AppLogger.info(TAG, "handoff display=$displayId action=$action → ${t.state}")
        return if (t.state == State.WAITING && !moveBackOk) {
            ResolveOutcome.MoveBackFailed(updated)
        } else {
            ResolveOutcome.Resolved(updated)
        }
    }

    /** Clears a handoff whose display was destroyed by other means. Returns the cleared record, if any. */
    fun clearForDestroyedDisplay(displayId: Int): HandoffRecord? {
        val record = store.get(displayId) ?: return null
        store.remove(displayId)
        return record
    }

    /**
     * Model-facing annotation for tool results on [displayId]: the record's
     * state guidance plus any accumulated note. Null when nothing is going on.
     */
    fun noteFor(displayId: Int): String? {
        val record = store.get(displayId) ?: return null
        val guidance = HandoffStateMachine.guidance(record.state, record.packageName, record.kind) ?: return null
        return if (record.note.isBlank()) guidance else "$guidance (${record.note})"
    }

    companion object {
        const val TAG = "HandoffController"
        const val DEFAULT_MAIN_DISPLAY = 0
    }
}
