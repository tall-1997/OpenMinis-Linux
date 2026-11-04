package com.openminis.app.data.db

/**
 * [T-m9-supervisor-experience] Pure functions over [ProjectExperienceEntity]:
 * row identity, the per-call fold, fingerprint rotation, and the recall block
 * the supervisor seat prepends to its system prompt at session start. All
 * rules live here so the DAO stays a thin SQL surface and the fold is
 * unit-testable without Room.
 */
object ProjectExperienceCore {

    /** Row key: one outcome profile per (session, tool name). */
    fun idFor(sessionId: String, toolName: String): String = "$sessionId|$toolName"

    /**
     * Fold marker for the event that would bring the row to [usedCount] uses
     * at [lastUsedAtMs]. Events are deduplicated on this marker before the
     * fold is applied, so a replayed tool event counts at most once.
     */
    fun fingerprintFor(sessionId: String, toolName: String, usedCount: Int, lastUsedAtMs: Long): String =
        "$usedCount|$lastUsedAtMs"

    /**
     * Folds one executed tool call into the row. A null [prev] starts the
     * row with counters at one; otherwise the counters are carried over so
     * later sessions of the same chat see the accumulated profile.
     */
    fun applyResult(
        sessionId: String,
        toolName: String,
        prev: ProjectExperienceEntity?,
        success: Boolean,
        nowMs: Long,
    ): ProjectExperienceEntity {
        val base = prev ?: ProjectExperienceEntity(
            id = idFor(sessionId, toolName),
            sessionId = sessionId,
            toolName = toolName,
            usedCount = 0,
            successCount = 0,
            failureCount = 0,
            firstUsedAtMs = nowMs,
            lastUsedAtMs = nowMs,
            fingerprint = "",
        )
        return base.copy(
            usedCount = base.usedCount + 1,
            successCount = base.successCount + if (success) 1 else 0,
            failureCount = base.failureCount + if (success) 0 else 1,
            lastUsedAtMs = nowMs,
            fingerprint = fingerprintFor(sessionId, toolName, base.usedCount + 1, nowMs),
        )
    }

    /**
     * Recall block injected into the supervisor system prompt. Empty list
     * yields "" so a fresh session's prompt is byte-identical to the
     * pre-experience one. Rows with zero uses are dropped (they carry no
     * evidence), and the rest are listed newest-first by [ProjectExperienceEntity.lastUsedAtMs].
     */
    fun recallBlock(rows: List<ProjectExperienceEntity>): String {
        val live = rows.filter { it.usedCount > 0 }
            .sortedByDescending { it.lastUsedAtMs }
        if (live.isEmpty()) return ""
        val lines = live.joinToString("\n") { r ->
            "- ${r.toolName}: ${r.successCount}✓ / ${r.failureCount}✗ (${r.usedCount} uses, last ${r.lastUsedAtMs})"
        }
        return "\n\n[Session tool experience, accumulated by the supervisor seat in this session]\n" +
            "Read these measured outcomes before choosing/dispatching tools; evidence over intuition:\n$lines"
    }
}
