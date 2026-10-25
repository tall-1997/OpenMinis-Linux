package com.openminis.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface GoalDao {
    @Query("SELECT * FROM session_goals WHERE session_id = :sessionId LIMIT 1")
    suspend fun get(sessionId: String): SessionGoalEntity?

    @Query("DELETE FROM session_goals WHERE session_id = :sessionId")
    suspend fun delete(sessionId: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(goal: SessionGoalEntity)

    @Query("UPDATE session_goals SET objective = :objective, status = :status, token_budget = :tokenBudget, tokens_used = :tokensUsed, elapsed_ms = :elapsedMs, blocked_condition = :blockedCondition, blocked_count = :blockedCount, paused_at = :pausedAt, updated_at = :updatedAt WHERE session_id = :sessionId")
    suspend fun saveState(
        sessionId: String,
        objective: String,
        status: String,
        tokenBudget: Long?,
        tokensUsed: Long,
        elapsedMs: Long,
        blockedCondition: String?,
        blockedCount: Int,
        pausedAt: Long?,
        updatedAt: Long,
    )

    @Query("UPDATE session_goals SET objective = :objective, updated_at = :updatedAt WHERE session_id = :sessionId")
    suspend fun updateObjective(sessionId: String, objective: String, updatedAt: Long)

    @Query("UPDATE session_goals SET status = :status, paused_at = :pausedAt, updated_at = :updatedAt WHERE session_id = :sessionId")
    suspend fun updateStatus(sessionId: String, status: String, pausedAt: Long?, updatedAt: Long)

    @Query("UPDATE session_goals SET tokens_used = tokens_used + :tokens, elapsed_ms = elapsed_ms + :elapsedMs, updated_at = :updatedAt WHERE session_id = :sessionId")
    suspend fun addUsage(sessionId: String, tokens: Long, elapsedMs: Long, updatedAt: Long)

    @Query("UPDATE session_goals SET blocked_condition = :condition, blocked_count = :count, status = :status, updated_at = :updatedAt WHERE session_id = :sessionId")
    suspend fun updateBlocked(sessionId: String, condition: String?, count: Int, status: String, updatedAt: Long)
}
