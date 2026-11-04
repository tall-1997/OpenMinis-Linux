package com.openminis.app.data.repository

import com.openminis.app.util.IsoTime

/**
 * [T-memory-sediment] Turns a successful compaction into a durable memory entry.
 *
 * Until now the only surviving copy of what a compaction folded away was the
 * summary inside the compact marker: good for rebuilding the prompt, invisible to
 * `memory_get`, and unreachable from any later chat. codex and opencode do not
 * need this because they persist the entire trajectory; OpenMinis keeps only a
 * bounded window plus the marker, so a deliberate trace is what keeps earlier
 * decisions findable.
 *
 * Deliberate constraints:
 *
 *  - labelled entries, so a user reading the daily log can tell these from notes
 *    the agent wrote on purpose;
 *  - de-duplicated by exact payload, because re-compacting the same stretch
 *    (retry, revert then compact again) must not multiply the log;
 *  - gated by the session's memory toggle at the call site — with memory off
 *    nothing is written, consistent with the rest of the memory feature.
 *
 * Pure helpers here; the write itself goes through [MemoryRepository.writeMemory]
 * so the existing size/entry quotas still apply.
 */
object MemorySediment {

    /** Marks auto-generated entries. Keep stable: de-duplication keys on it. */
    const val LABEL = "[compaction-summary]"

    /** Summary excerpt stored per compaction; the full text stays in the marker. */
    internal const val MAX_CHARS = 2_000

    /** Builds the log entry for one compaction, or null when there is nothing worth keeping. */
    fun buildEntry(summary: String, compactedCount: Int): String? {
        val trimmed = summary.trim()
        if (trimmed.isEmpty()) return null
        val excerpt = if (trimmed.length <= MAX_CHARS) trimmed else trimmed.take(MAX_CHARS) + "…"
        return buildString {
            append(LABEL)
            append(" ")
            append(compactedCount.coerceAtLeast(0))
            append(" message(s) folded into this summary:\n")
            append(excerpt)
        }
    }

    /** True when [entry] is already present in the daily log (byte-identical). */
    fun isDuplicate(entry: String, existingDailyLog: String): Boolean =
        existingDailyLog.contains(entry)

    /**
     * Writes [entry] into today's log unless it is already there.
     * Returns the repository's result string, or null when nothing was written.
     */
    fun record(repository: MemoryRepository, entry: String?): String? {
        val payload = entry ?: return null
        val today = repository.readFile("${IsoTime.formatLocalDate()}.md")
        if (isDuplicate(payload, today)) return null
        return repository.writeMemory(payload)
    }

    /** Convenience: build + record in one call. */
    fun recordCompaction(repository: MemoryRepository, summary: String, compactedCount: Int): String? =
        record(repository, buildEntry(summary, compactedCount))
}
