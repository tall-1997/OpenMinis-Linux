package com.openminis.app.goal

import com.openminis.app.data.db.GoalOrigin
import com.openminis.app.data.db.GoalStatus
import com.openminis.app.data.db.SessionGoalEntity
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.goal.GoalCompletionVerifier.Verification
import java.util.concurrent.ConcurrentHashMap
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

        /**
         * [T-goal-completion-verifier] Consecutive host rejections per
         * session, counted across manager instances (the manager is built
         * ad-hoc everywhere, so the count lives on the companion like
         * [version]). In-process on purpose: the count is loop-control
         * bookkeeping, not goal state — persisting it would need a Room
         * migration for a number that only matters within one run. A process
         * restart clears it, which costs at most one more reject-continue
         * cycle — the same trade [com.openminis.app.tools.AgentPlanStore]
         * makes for its un-persisted finished stamps.
         */
        private val completionRejections = ConcurrentHashMap<String, Int>()

        /** Test seam: reset the per-session rejection counters. */
        internal fun resetCompletionRejections() = completionRejections.clear()
    }

    private suspend fun <T> locked(sessionId: String, block: suspend () -> T): T =
        locks[(sessionId.hashCode() and Int.MAX_VALUE) % locks.size].withLock { block() }

    suspend fun get(sessionId: String): SessionGoalEntity? = persistence.load(sessionId)

    suspend fun createByUser(sessionId: String, objective: String, tokenBudget: Long? = null): SessionGoalEntity =
        locked(sessionId) {
            check(persistence.load(sessionId) == null) { "A Goal already exists for this session" }
            GoalStateMachine.create(
                sessionId,
                objective,
                tokenBudget,
                System.currentTimeMillis(),
                GoalOrigin.USER,
            ).also {
                persistence.insert(it)
                bump()
            }
        }

    /**
     * [T-goal-created-with-plan] Model-side creation, the counterpart of the
     * harness's `create_goal`: when the agent opens a plan board for a
     * long-running objective, the objective is recorded as the session Goal in
     * the same step instead of being lost with the tool arguments.
     *
     * Idempotent for the SAME objective — a model may re-emit the board
     * (op=replace is a full-list rewrite) or retry a tool call; creating a Goal
     * twice must not fail the turn. A DIFFERENT objective, however, is a new
     * task and must not inherit the old one: before
     * [T-goal-model-origin] this branch silently returned the existing row, so
     * a finished task's Goal stayed ACTIVE and every later request mounted its
     * `<goal_context>` — the model kept reading a previous task's objective and
     * the new task never got a Goal of its own.
     *
     * A [GoalOrigin.MODEL] Goal is therefore superseded by the new objective
     * unless the human paused it (a pause is an instruction to stop touching
     * it). A [GoalOrigin.USER] Goal is never overwritten: the human's objective
     * outranks whatever the model inferred.
     */
    suspend fun createByModel(sessionId: String, objective: String, tokenBudget: Long? = null): SessionGoalEntity = locked(sessionId) {
        val normalized = objective.trim()
        val existing = persistence.load(sessionId)
        if (existing != null) {
            val sameObjective = existing.objective == normalized
            if (sameObjective || !supersedableByModel(existing)) return@locked existing
            // One row per session: drop the stale model Goal, then write the
            // new task's. (A single UPDATE would need saveState to carry
            // created_at; the delete+insert keeps the fresh start time and is
            // no worse than the pre-existing write path.)
            persistence.delete(sessionId)
        }
        GoalStateMachine.create(
            sessionId,
            normalized,
            tokenBudget,
            System.currentTimeMillis(),
            GoalOrigin.MODEL,
        ).also {
            persistence.insert(it)
            bump()
        }
    }

    /**
     * A model-written Goal describes the task in progress, so a new objective
     * replaces it — except when the human paused it, which is an explicit
     * "leave this alone". Any other status (active, complete, blocked,
     * budget-limited) belongs to finished or failed work and must not block the
     * next task from recording its own objective.
     */
    private fun supersedableByModel(goal: SessionGoalEntity): Boolean =
        goal.createdBy == GoalOrigin.MODEL && goal.status != GoalStatus.PAUSED

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

    /**
     * The outcome of a host-verified completion
     * ([T-goal-completion-verifier]). [accepted] covers both the happy path
     * and the forced one — the Goal row moved to COMPLETE either way.
     * [rejected] means the row was NOT touched: the Goal stays ACTIVE and
     * [Verification.reason] is the rejection's channel back to the model.
     */
    sealed class VerifiedCompletion {
        abstract val goal: SessionGoalEntity
        abstract val verification: Verification

        /** Accepted as-is (nothing to verify, or the board checked out). */
        class Accepted(
            override val goal: SessionGoalEntity,
            override val verification: Verification,
        ) : VerifiedCompletion()

        /**
         * Rejected but the limit was reached: the model's call is accepted
         * anyway ([GoalCompletionVerifier.MAX_REJECTIONS] in a row means the
         * verifier and the model disagree persistently, and dead-locking the
         * Goal helps nobody). [forcedReason] records the verifier's standing
         * objection for the transcript.
         */
        class Forced(
            override val goal: SessionGoalEntity,
            override val verification: Verification,
            val forcedReason: String,
        ) : VerifiedCompletion()

        /** Rejected: the Goal row was NOT touched, it stays ACTIVE. */
        class Rejected(
            override val goal: SessionGoalEntity,
            override val verification: Verification,
        ) : VerifiedCompletion()
    }

    /**
     * [T-goal-completion-verifier] Host-verified completion for
     * `update_goal(op=complete)`. The model's call is NOT the last word: the
     * verifier ([GoalCompletionVerifier.verifyCompletion]) arbitrates the
     * plan board layer, and a rejected call leaves the Goal row untouched —
     * the Goal stays ACTIVE, the rejection count for the session advances,
     * and the tool result carries [Verification.reason] back to the model.
     *
     * After [GoalCompletionVerifier.MAX_REJECTIONS] consecutive rejections
     * the model's call is accepted anyway
     * ([VerifiedCompletion.Forced]) — the verifier and the model disagree
     * persistently, and dead-locking the Goal against its own agent helps
     * nobody. A pass (empty board) or an accepted board resets the count.
     *
     * [planStatuses] is the plan board statuses the caller read for this
     * session (empty list = no board); reading is the caller's job so this
     * method stays persistence-only and JVM tests drive the seam without a
     * Context.
     */
    suspend fun completeByModelVerified(
        sessionId: String,
        planStatuses: List<String>,
    ): VerifiedCompletion = locked(sessionId) {
        val current = checkNotNull(persistence.load(sessionId)) { "No Goal exists for this session" }
        val verification = GoalCompletionVerifier.verifyCompletion(planStatuses)
        if (verification.allowed) {
            completionRejections.remove(sessionId)
            return@locked VerifiedCompletion.Accepted(
                goal = GoalStateMachine.modelUpdate(current, GoalStatus.COMPLETE, null, System.currentTimeMillis()).also {
                    persistence.save(it)
                    bump()
                },
                verification = verification,
            )
        }
        val rejections = (completionRejections[sessionId] ?: 0) + 1
        if (rejections < GoalCompletionVerifier.MAX_REJECTIONS) {
            completionRejections[sessionId] = rejections
            return@locked VerifiedCompletion.Rejected(goal = current, verification = verification)
        }
        // Limit reached: accept the model's call, record the disagreement.
        completionRejections.remove(sessionId)
        VerifiedCompletion.Forced(
            goal = GoalStateMachine.modelUpdate(current, GoalStatus.COMPLETE, null, System.currentTimeMillis()).also {
                persistence.save(it)
                bump()
            },
            verification = verification,
            forcedReason = GoalCompletionVerifier.forcedAcceptanceReason(planStatuses),
        )
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

    /**
     * [T-goal-tool-clear] Model-side removal — the delete op the `update_goal`
     * tool was missing. Before it existed the agent could only mark a Goal
     * complete, so the row stayed behind and every later task in the session
     * inherited (mounted) an objective nobody wanted any more; the model had
     * no way to say "this task is over, drop it".
     *
     * Policy: the model may clear a Goal it is replacing, including one the
     * human wrote, but NOT a PAUSED one — a pause is the human explicitly
     * saying "leave this alone", and deleting it would discard an objective
     * they can no longer see. A paused Goal is refused so the model reports
     * the conflict instead of silently winning.
     *
     * Returns false without bumping when nothing existed or the Goal is paused.
     */
    suspend fun clearByModel(sessionId: String): Boolean = locked(sessionId) {
        val current = persistence.load(sessionId) ?: return@locked false
        if (current.status == GoalStatus.PAUSED) return@locked false
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
