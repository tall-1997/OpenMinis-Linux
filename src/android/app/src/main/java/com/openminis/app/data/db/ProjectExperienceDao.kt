package com.openminis.app.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/**
 * [T-m9-supervisor-experience] Access to the per-session tool outcome rows.
 * Written once per executed worker tool call, read once at supervisor session
 * start; low row counts by design (one row per tool name per session).
 */
@Dao
interface ProjectExperienceDao {

    @Query("SELECT * FROM project_experience WHERE session_id = :sessionId ORDER BY last_used_at DESC")
    suspend fun selectBySession(sessionId: String): List<ProjectExperienceEntity>

    @Query("SELECT * FROM project_experience WHERE id = :id")
    suspend fun get(id: String): ProjectExperienceEntity?

    @Upsert
    suspend fun upsert(entity: ProjectExperienceEntity)

    @Query("UPDATE project_experience SET fingerprint = :fingerprint WHERE id = :id")
    suspend fun updateFingerprint(id: String, fingerprint: String)

    @Query("DELETE FROM project_experience WHERE session_id = :sessionId")
    suspend fun deleteBySession(sessionId: String)
}
