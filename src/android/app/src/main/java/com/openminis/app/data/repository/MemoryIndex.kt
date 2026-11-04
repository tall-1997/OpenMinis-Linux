package com.openminis.app.data.repository

import com.openminis.app.data.authority.MemoryAuthority
import com.openminis.app.data.authority.MemoryOrigin
import com.openminis.app.data.authority.MemoryTrust
import java.io.File

/**
 * [T-memory-index] A table of contents for diary days that are NOT injected
 * verbatim.
 *
 * The prompt used to carry at most [MemoryRepository.MAX_RECENT_FILES] diary
 * files, so anything older was simply invisible: still on disk, still readable by
 * `memory_get`, but the model had no way to know it existed. In a chat that
 * spans weeks this reads exactly like "memory was lost".
 *
 * The fix is not a bigger window — that just moves the token bill. It is an
 * index: one line per older day (date, entry count, first line). The model can
 * then decide to fetch the detail, which is the same index+fetch split every
 * large-context system converges on.
 *
 * Two scopes feed it:
 *  - the session's own diary directory, always;
 *  - the shared `minis-global/memory` directory, so experience from another chat
 *    is at least *discoverable*. Shared rows are labelled, and only the first
 *    line is shown — the body still requires an explicit `memory_get`.
 */
object MemoryIndex {

    /** Hard cap on index rows; the block stays O(1) as history grows. */
    const val MAX_ROWS = 40

    /** App-wide memory directory, relative to `filesDir`. */
    const val SHARED_DIR = "minis-global/memory"

    /** The app-wide memory directory — the second scope this index reads. */
    fun sharedDir(filesDir: File): File = File(filesDir, SHARED_DIR)

    /** Chars of the first entry kept as a preview. */
    internal const val PREVIEW_CHARS = 80

    private val DAILY = Regex("""^\d{4}-\d{2}-\d{2}\.md$""")

    private val ENTRY_MARK = Regex("""(?m)^<!-- \d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} -->""")

    data class Row(
        /** `YYYY-MM-DD`, also the sort key. */
        val date: String,
        val entries: Int,
        val preview: String,
        /** True when the row comes from the shared directory rather than this chat. */
        val shared: Boolean,
    )

    /**
     * Rows for every diary day outside the verbatim window.
     *
     * [excludeNewest] skips the newest N dates, which the prompt already carries
     * in full; indexing them twice would waste budget and invite contradictions.
     * On a date present in both scopes the session copy wins — it is the one the
     * verbatim window would have shown.
     */
    fun rows(
        sessionDir: File?,
        sharedDir: File?,
        excludeNewest: Int,
        maxRows: Int = MAX_ROWS,
    ): List<Row> {
        val byDate = LinkedHashMap<String, Row>()
        // Shared first, session second: a later put() replaces the shared row.
        for ((dir, shared) in listOf(sharedDir to true, sessionDir to false)) {
            val files = dir?.listFiles() ?: continue
            for (file in files) {
                if (!file.isFile || !DAILY.matches(file.name)) continue
                val date = file.name.removeSuffix(".md")
                val row = rowFor(file, date, shared) ?: continue
                byDate[date] = row
            }
        }
        val sorted = byDate.values.sortedByDescending { it.date }
        return sorted.drop(excludeNewest.coerceAtLeast(0)).take(maxRows)
    }

    private fun rowFor(file: File, date: String, shared: Boolean): Row? {
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        if (text.isBlank()) return null
        val entries = ENTRY_MARK.findAll(text).count()
        return Row(date = date, entries = entries, preview = previewOf(text), shared = shared)
    }

    /** First line that is neither a timestamp marker nor a heading. */
    private fun previewOf(text: String): String {
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (ENTRY_MARK.matches(trimmed)) continue
            val cleaned = trimmed.removePrefix("#").trim()
            if (cleaned.isEmpty()) continue
            return if (cleaned.length <= PREVIEW_CHARS) cleaned else cleaned.take(PREVIEW_CHARS) + "…"
        }
        return ""
    }

    /** Renders [rows]; returns null when there is nothing to index. */
    fun render(rows: List<Row>, omitted: Int = 0): String? {
        if (rows.isEmpty()) return null
        return buildString {
            append("Older diary days (index only — call memory_get with a keyword to read one):\n")
            for (row in rows) {
                append("- ")
                append(row.date)
                append(" (")
                append(row.entries)
                append(if (row.entries == 1) " entry" else " entries")
                append(if (row.shared) " · shared" else " · this chat")
                append("): ")
                if (row.preview.isNotEmpty()) append('"').append(row.preview).append('"')
                appendLine()
            }
            if (omitted > 0) {
                append("… (").append(omitted).append(" older day(s) not listed; memory_get can still search them)")
            }
        }.trimEnd()
    }

    /**
     * The prompt fragment, wrapped in the standard memory envelope.
     * Null when both scopes have nothing older than the verbatim window.
     */
    fun fragment(
        sessionDir: File?,
        sharedDir: File?,
        excludeNewest: Int,
        maxRows: Int = MAX_ROWS,
    ): String? {
        val rows = rows(sessionDir, sharedDir, excludeNewest, maxRows)
        if (rows.isEmpty()) return null
        // Recompute the omitted count so the block can say how much it hid.
        val total = rows(sessionDir, sharedDir, excludeNewest, Int.MAX_VALUE).size
        val body = render(rows, (total - rows.size).coerceAtLeast(0)) ?: return null
        return MemoryAuthority.withEnvelope(
            body,
            MemoryTrust.UNVERIFIED_CANDIDATE,
            MemoryOrigin.AGENT,
        )
    }
}
