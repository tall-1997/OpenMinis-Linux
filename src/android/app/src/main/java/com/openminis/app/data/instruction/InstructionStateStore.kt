package com.openminis.app.data.instruction

import androidx.room.withTransaction
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.db.SessionGoalEntity
import com.openminis.app.logging.AppLogger

/**
 * The P1 commit path: observe the instruction sources, diff them against the
 * frozen state row, and freeze any change into the append-only event stream.
 *
 * Mirrors opencode `instruction-state.ts` commit(): an empty delta on a
 * non-initial state is a no-op (returns false, nothing written). The initial
 * snapshot and every change afterwards produce exactly one event row.
 *
 * [goalInstructionValues] keeps volatile runtime progress (tokensUsed /
 * elapsedMs) OUT of the instruction values on purpose: those change every
 * turn and must not flood the event stream. Injection reads them live from
 * the entity instead.
 */
class InstructionStateStore(
    private val db: AppDatabase?,
) {
    private val dao: InstructionStateDao? get() = db?.instructionStateDao()

    /**
     * Freezes [values] for [sessionId] if (and only if) they differ from the
     * stored snapshot. Returns true when an event row was appended. A null
     * db (JVM tests that never run the commit path) is an observed no-op.
     */
    suspend fun observeAndCommit(sessionId: String, values: Map<String, String>): Boolean {
        val dao = dao ?: return false
        val database = db ?: return false
        val state = dao.stateFor(sessionId)
        // Skip freezing an all-empty baseline: nothing observable yet. The
        // first non-empty observation becomes the initial snapshot. An empty
        // observation WITH a stored state is a real clear-everything change.
        if (values.isEmpty() && state == null) return false
        val observation = InstructionState.observe(
            storedValues = state?.let { InstructionState.parseValues(it.currentValuesJson) },
            current = values,
        )
        if (!observation.initial && observation.delta.isEmpty) return false

        val now = System.currentTimeMillis()
        val frozenSeq = database.withTransaction {
            val prevSeq = state?.throughSeq ?: 0L
            val nextSeq = prevSeq + 1
            val rendered = if (observation.initial) {
                InstructionState.renderInitial(observation.currentValues)
            } else {
                InstructionState.renderUpdate(
                    observation.previousValues,
                    observation.currentValues,
                    observation.delta,
                )
            }
            dao.upsertState(
                InstructionStateEntity(
                    sessionId = sessionId,
                    epochStartSeq = if (observation.initial) nextSeq else state!!.epochStartSeq,
                    throughSeq = nextSeq,
                    initialValuesJson = if (observation.initial) {
                        InstructionState.valuesJson(observation.currentValues)
                    } else {
                        state!!.initialValuesJson
                    },
                    currentValuesJson = InstructionState.valuesJson(observation.currentValues),
                    updatedAt = now,
                ),
            )
            dao.insertUpdate(
                InstructionUpdateEntity(
                    sessionId = sessionId,
                    seq = nextSeq,
                    isInitial = observation.initial,
                    deltaJson = if (observation.initial) null else {
                        InstructionState.deltaJson(observation.delta)
                    },
                    renderedText = rendered,
                    createdAt = now,
                ),
            )
            nextSeq
        }
        AppLogger.info("InstructionStateStore", "frozen seq=$frozenSeq session=$sessionId initial=${observation.initial}")
        return true
    }

    /** Latest frozen values for [sessionId], or null when nothing was snapshotted yet. */
    suspend fun currentValues(sessionId: String): Map<String, String>? =
        dao?.stateFor(sessionId)?.let { InstructionState.parseValues(it.currentValuesJson) }

    /** Most recent frozen update events, oldest first. */
    suspend fun recentUpdates(sessionId: String, limit: Int): List<InstructionUpdateEntity> =
        dao?.updatesFor(sessionId, limit) ?: emptyList()

    companion object {
        /**
         * Goal instruction value: the semantic instruction fields only.
         * A null goal (cleared) yields an empty map, which observe() turns
         * into removal deltas for the goal key.
         */
        fun goalInstructionValues(goal: SessionGoalEntity?): Map<String, String> {
            if (goal == null) return emptyMap()
            val fields: List<Pair<String, String>> = listOf(
                "objective" to goal.objective,
                "status" to goal.status,
                "tokenBudget" to goal.tokenBudget?.toString().orEmpty(),
                "blockedCondition" to goal.blockedCondition.orEmpty(),
                "blockedCount" to goal.blockedCount.toString(),
            ).filter { it.second.isNotEmpty() }
            return mapOf<String, String>(
                Pair(InstructionState.KEY_GOAL, InstructionState.valuesJson(fields.toMap())),
            )
        }
    }
}
