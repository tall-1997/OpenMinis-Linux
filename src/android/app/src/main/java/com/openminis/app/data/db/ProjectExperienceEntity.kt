package com.openminis.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * [T-m9-supervisor-experience] One row per (session, tool): the supervision
 * loop folds each executed worker tool call into a small outcome profile
 * ([ProjectExperienceCore.applyResult]). The supervisor seat reads the rows
 * back at session start and injects them into its system prompt so the next
 * worker gets empirically grounded tool choices instead of a blank slate.
 *
 * [fingerprint] records which fold the row has already absorbed (used-count +
 * last timestamp), so a replayed or re-delivered tool event folds at most
 * once instead of inflating the counters.
 */
@Entity(
    tableName = "project_experience",
    indices = [
        Index(value = ["session_id"]),
    ],
)
data class ProjectExperienceEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "tool_name") val toolName: String,
    /** Total folded calls for this tool in this session. */
    @ColumnInfo(name = "used_count") val usedCount: Int,
    @ColumnInfo(name = "success_count") val successCount: Int,
    @ColumnInfo(name = "failure_count") val failureCount: Int,
    @ColumnInfo(name = "first_used_at") val firstUsedAtMs: Long,
    @ColumnInfo(name = "last_used_at") val lastUsedAtMs: Long,
    /** Fold marker: "usedCount|lastUsedAtMs" of the last absorbed event. */
    val fingerprint: String,
)
