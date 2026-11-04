package com.openminis.app.ui.chat

import com.openminis.app.data.instruction.InstructionReplay
import com.openminis.app.data.instruction.InstructionState
import com.openminis.app.data.instruction.InstructionStateStore
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [T-p1-instruction-snapshot] Observability only: replay the frozen instruction
 * events for this session and report drift against the stored `currentValues`.
 *
 * Run once on session load. It never mutates state and never gates anything —
 * the point is that the event stream now has a reader, so a commit path that
 * writes deltas which do not reconstruct the frozen state shows up in the log
 * instead of staying invisible until someone tries to recover from it.
 *
 * Cost is two indexed queries capped at [InstructionStateStore.UPDATE_REPLAY_CAP]
 * rows; a session with no snapshot returns before touching the DB a second time.
 */
internal suspend fun ChatViewModel.checkInstructionSnapshotDrift() {
    val database = chatRepository.db ?: return
    val sessionIdForCheck = realSessionId.ifEmpty { sessionId }
    withContext(Dispatchers.IO) {
        runCatching {
            val store = InstructionStateStore(database)
            val state = database.instructionStateDao().stateFor(sessionIdForCheck)
                ?: return@runCatching
            val updates = store.allUpdates(sessionIdForCheck)
            val replay = InstructionReplay.rebuild(state.initialValuesJson, updates)
            val current = InstructionState.parseValues(state.currentValuesJson)
            if (replay.values != current) {
                AppLogger.warning(
                    "InstructionStateStore",
                    "[T-p1-instruction-drift] session=$sessionIdForCheck replay != current " +
                        "applied=${replay.applied} stop=${replay.stopReason} " +
                        "missing=${current.keys - replay.values.keys} " +
                        "extra=${replay.values.keys - current.keys}",
                )
            }
        }.onFailure {
            AppLogger.warning(
                "InstructionStateStore",
                "[T-p1-instruction-drift] check failed for session=$sessionIdForCheck: ${it.message}",
            )
        }
    }
}
