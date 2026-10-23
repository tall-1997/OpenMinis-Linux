package com.openminis.app.goal

import com.openminis.app.data.db.SessionGoalEntity
import com.openminis.app.data.repository.ChatRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Narrow persistence seam: the manager needs exactly these four goal
 * operations, and JVM tests drive it through an in-memory fake instead of
 * dragging Room in. The production adapter below wraps [ChatRepository].
 */
internal interface GoalPersistence {
    suspend fun load(sessionId: String): SessionGoalEntity?
    suspend fun insert(goal: SessionGoalEntity)
    suspend fun save(goal: SessionGoalEntity)
    suspend fun delete(sessionId: String)
}

/** [ChatRepository]-backed [GoalPersistence]; `goalDao` is internal to the module. */
private class RepositoryGoalPersistence(private val repository: ChatRepository) : GoalPersistence {
    override suspend fun load(sessionId: String): SessionGoalEntity? = repository.goal(sessionId)
    override suspend fun insert(goal: SessionGoalEntity) = repository.createGoal(goal)
    override suspend fun save(goal: SessionGoalEntity) = repository.saveGoal(goal)
    override suspend fun delete(sessionId: String) = repository.goalDao.delete(sessionId)
}

/** Session-scoped Goal API used by the slash UI, tool dispatcher and agent loop. */
class GoalManager internal constructor(private val persistence: GoalPersistence) {
    constructor(repository: ChatRepository) : this(RepositoryGoalPersistence(repository))

    companion object {
        /**
         * Bumped on every successful write, from ANY [GoalManager] instance —
         * the manager is constructed ad-hoc by the slash UI, tool dispatcher
         * and agent loop, so the signal lives on the class. UI projections
         * (the PlanGoalCard header row) collect it and reload without the
         * writers knowing a UI exists. Failed writes bump nothing.
         */
        val version = MutableStateFlow(0L)

        private val locks = Array(64) { Mutex() }
        private fun bump() { version.value = version.value + 1 }
    }

    private suspend fun <T> locked(sessionId: String, block: suspend () -> T): T =
        locks[(sessionId.hashCode() and Int.MAX_VALUE) % locks.size].withLock { block() }

    suspend fun get(sessionId: String): SessionGoalEntity? = persistence.load(sessionId)

    suspend fun createByUser(sessionId: String, objective: String, tokenBudget: Long? = null): SessionGoalEntity =
        locked(sessionId) {
            check(persistence.load(sessionId) == null) { "A Goal already exists for this session" }
            GoalStateMachine.create(sessionId, objective, tokenBudget, System.currentTimeMillis()).also {
                persistence.insert(it)
                bump()
            }
        }

    suspend fun setPausedByUser(sessionId: String, paused: Boolean): SessionGoalEntity? = locked(sessionId) {
        val current = persistence.load(sessionId) ?: return@locked null
        val updated = if (paused) GoalStateMachine.pause(current, System.currentTimeMillis())
        else GoalStateMachine.resume(current, System.currentTimeMillis())
        persistence.save(updated)
        bump()
        updated
    }

    suspend fun updateBudgetByUser(sessionId: String, tokenBudget: Long?): SessionGoalEntity = locked(sessionId) {
        val current = checkNotNull(persistence.load(sessionId)) { "No Goal exists for this session" }
        GoalStateMachine.setBudget(current, tokenBudget, System.currentTimeMillis()).also {
            persistence.save(it)
            bump()
        }
    }

    suspend fun updateObjectiveByUser(sessionId: String, objective: String): SessionGoalEntity = locked(sessionId) {
        val normalized = objective.trim()
        require(normalized.isNotEmpty() && normalized.length <= GoalStateMachine.MAX_OBJECTIVE_LENGTH)
        val current = checkNotNull(persistence.load(sessionId)) { "No Goal exists for this session" }
        current.copy(objective = normalized, updatedAt = System.currentTimeMillis()).also {
            persistence.save(it)
            bump()
        }
    }

    /** Model tools cannot create goals or pause/resume them. */
    suspend fun updateByModel(sessionId: String, status: String, condition: String?): SessionGoalEntity = locked(sessionId) {
        val current = checkNotNull(persistence.load(sessionId)) { "No Goal exists for this session" }
        GoalStateMachine.modelUpdate(current, status, condition, System.currentTimeMillis()).also {
            persistence.save(it)
            bump()
        }
    }

    suspend fun recordUsage(sessionId: String, tokens: Long, elapsedMs: Long): SessionGoalEntity? = locked(sessionId) {
        val current = persistence.load(sessionId) ?: return@locked null
        GoalStateMachine.recordProgress(current, tokens.coerceAtLeast(0), elapsedMs.coerceAtLeast(0), System.currentTimeMillis())
            .also {
                persistence.save(it)
                bump()
            }
    }

    /**
     * User-sovereign removal, from ANY status (complete, blocked,
     * budget-limited included): the goal sheet's "clear" and "new goal"
     * both land here. Returns false (no bump) when nothing existed.
     */
    suspend fun clearByUser(sessionId: String): Boolean = locked(sessionId) {
        if (persistence.load(sessionId) == null) return@locked false
        persistence.delete(sessionId)
        bump()
        true
    }

    suspend fun mayContinue(sessionId: String, usefulActivity: Boolean): Boolean =
        persistence.load(sessionId)?.let { GoalStateMachine.canContinue(it, usefulActivity) } ?: false

    fun continuationPrompt(goal: SessionGoalEntity): String = goalContextPrompt(goal)

    fun render(goal: SessionGoalEntity?): String {
        if (goal == null) return "(no goal)"
        val spent = goal.tokensUsed
        val budget = goal.tokenBudget?.toString() ?: "unlimited"
        return buildString {
            appendLine("Goal [${goal.status}] — ${goal.objective}")
            append("tokens=$spent/$budget elapsed=${goal.elapsedMs}ms")
            goal.blockedCondition?.let { append(" blocked=$it (${goal.blockedCount}/${GoalStateMachine.BLOCKED_CONFIRMATIONS})") }
        }
    }
}
