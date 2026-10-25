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
)

object GoalStatus {
    const val ACTIVE = "active"
    const val PAUSED = "paused"
    const val COMPLETE = "complete"
    const val BLOCKED = "blocked"
    const val BUDGET_LIMITED = "budget_limited"
}
