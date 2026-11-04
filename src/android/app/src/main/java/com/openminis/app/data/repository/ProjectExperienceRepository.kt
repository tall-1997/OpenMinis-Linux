package com.openminis.app.data.repository

import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.db.ProjectExperienceCore
import com.openminis.app.data.db.ProjectExperienceEntity

/**
 * [T-m9-supervisor-experience] Read/write helpers for per-session tool
 * experience rows. Extension functions keep [ChatRepository] under its
 * architecture ceiling while the supervision loop records one fold per
 * executed worker tool call and recalls the profile at session start.
 */

/** Rows for one session, most recently used first. */
suspend fun ChatRepository.listProjectExperience(sessionId: String): List<ProjectExperienceEntity> =
    projectExperienceDaoOrNull()?.selectBySession(sessionId) ?: emptyList()

/**
 * [T-m9-supervisor-experience] Supervisor system-prompt block recalling the
 * session's folded tool profile. UI layers call this instead of touching
 * Room-facing [ProjectExperienceCore] directly (architecture policy
 * batch-6 item 21: ui → repository/DTO seam only).
 */
suspend fun ChatRepository.projectExperienceRecall(sessionId: String): String =
    ProjectExperienceCore.recallBlock(listProjectExperience(sessionId))

/**
 * Folds one executed worker tool call into the row for (session, tool).
 * No-op when the optional DB handle is absent (JVM tests construct the
 * repository without one), so callers never need a null branch. A replayed
 * event whose fingerprint already matches the stored fold marker is skipped,
 * so counters never inflate on retry/replay.
 */
suspend fun ChatRepository.recordProjectExperience(
    sessionId: String,
    toolName: String,
    success: Boolean,
    nowMs: Long = System.currentTimeMillis(),
) {
    val dao = projectExperienceDaoOrNull() ?: return
    val next = ProjectExperienceCore.applyResult(sessionId, toolName, null, success, nowMs)
    val prev = dao.get(next.id)
    if (prev != null && prev.fingerprint == next.fingerprint) return
    dao.upsert(ProjectExperienceCore.applyResult(sessionId, toolName, prev, success, nowMs))
}

private fun ChatRepository.projectExperienceDaoOrNull(): com.openminis.app.data.db.ProjectExperienceDao? =
    dbOrNull()?.projectExperienceDao()

/** Promotes the optional handle to the extension; null-safe by design. */
private fun ChatRepository.dbOrNull(): AppDatabase? = db
