package com.openminis.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "session_goals")
data class SessionGoalEntity(
    @PrimaryKey
    @ColumnInfo(name = "session_id") val sessionId: String,
    val objective: String,
    val status: String = GoalStatus.ACTIVE,
    @ColumnInfo(name = "token_budget") val tokenBudget: Long? = null,
    @ColumnInfo(name = "tokens_used") val tokensUsed: Long = 0,
    @ColumnInfo(name = "elapsed_ms") val elapsedMs: Long = 0,
    @ColumnInfo(name = "blocked_condition") val blockedCondition: String? = null,
    @ColumnInfo(name = "blocked_count") val blockedCount: Int = 0,
    @ColumnInfo(name = "paused_at") val pausedAt: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /**
     * [T-goal-model-origin] Who wrote the objective — see [GoalOrigin].
     * Decides whether a later task may replace this row: a human objective is
     * sovereign, a model objective is only ever the CURRENT task's.
     */
    @ColumnInfo(name = "created_by") val createdBy: String = GoalOrigin.USER,
)

object GoalStatus {
    const val ACTIVE = "active"
    const val PAUSED = "paused"
    const val COMPLETE = "complete"
    const val BLOCKED = "blocked"
    const val BUDGET_LIMITED = "budget_limited"
}

/**
 * [T-goal-model-origin] Provenance of a Goal's objective.
 *
 * Without this the session Goal was write-once: `createByModel` is idempotent
 * (a retried tool call must not fail the turn), so once ANY goal existed — even
 * one the model itself wrote for a finished task — every later task in that
 * session silently inherited the OLD objective and no new goal was recorded.
 * The agent loop then mounted `<goal_context>` for the stale task on every
 * request, which is exactly what "the model read the previous task's goal"
 * looked like.
 *
 * The column exists so the two cases stay distinguishable:
 *  - [USER]: written by /goal create or the goal sheet. Never replaced by the
 *    model (user sovereignty), only the human may clear or edit it.
 *  - [MODEL]: written by update_goal(op=create) or by opening a task board. It
 *    describes the task in progress, so a DIFFERENT objective from the model
 *    supersedes it.
 * Rows written before this column existed default to [USER] — the
 * conservative side: they are never silently overwritten.
 */
object GoalOrigin {
    const val USER = "user"
    const val MODEL = "model"
}
