package com.openminis.app.data.repository

import com.openminis.app.data.authority.MemoryAuthority
import com.openminis.app.data.authority.MemoryOrigin
import com.openminis.app.data.authority.MemoryTrust
import java.io.File

/**
 * [T-memory-index] A table of contents for diary days that are NOT injected
 * verbatim.
 *
 * The prompt carries at most [MemoryRepository.MAX_RECENT_FILES] diary days
 * verbatim — and the budget may admit fewer — so anything not carried is
 * indexed here instead of invisible: still on disk, still readable by
 * `memory_get`, and now discoverable. In a chat that spans weeks this used
 * to read exactly like "memory was lost".
 *
 * The fix is not a bigger window alone — that just moves the token bill. It
 * is an index: one line per non-verbatim day (date, entry count, first
 * line). The model can then decide to fetch the detail, which is the same
 * index+fetch split every large-context system converges on.
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
     * [excludeDates] are the dates the prompt already carries verbatim —
     * excluded by identity, not by position. The verbatim window reads only
     * this chat's directory while this index spans both scopes, so a
     * count-based drop could skip a shared-only day the prompt never showed
     * (invisible) and double-list a session day it did. On a date present in
     * both scopes the session copy wins — it is the one the verbatim window
     * would have shown.
     */
    fun rows(
        sessionDir: File?,
        sharedDir: File?,
        excludeDates: Set<String>,
        maxRows: Int = MAX_ROWS,
        /** [T-memory-frontmatter] Clock for TTL eligibility; injectable for tests. */
        nowMs: Long = System.currentTimeMillis(),
    ): List<Row> {
        val candidates = ArrayList<Triple<File, String, Boolean>>()
        // Shared first, session second: a later put() replaces the shared row.
        for ((dir, shared) in listOf(sharedDir to true, sessionDir to false)) {
            val files = dir?.listFiles() ?: continue
            for (file in files) {
                if (!file.isFile || !DAILY.matches(file.name)) continue
                val text = runCatching { file.readText() }.getOrNull() ?: continue
                if (text.isBlank()) continue
                candidates += Triple(file, text, shared)
            }
        }
        // [T-memory-index-single-read] One read per file serves BOTH the metadata
        // decisions and the row build; reading the directory twice (once here, once
        // inside a supersede scan) doubled the prompt-path IO for no gain.
        val superseded = MemoryMetadataParser.supersededIds(
            candidates.map { (file, text, _) -> file.name.removeSuffix(".md") to text },
        )
        val byDate = LinkedHashMap<String, Row>()
        for ((file, text, shared) in candidates) {
            val date = file.name.removeSuffix(".md")
            if (date in superseded) continue
            if (!MemoryMetadataParser.isEligible(text, nowMs, file.lastModified())) continue
            byDate[date] = Row(
                date = date,
                entries = ENTRY_MARK.findAll(text).count(),
                // [T-memory-frontmatter-strip] Preview the BODY: the metadata block is
                // not memory content, and a new day's first line is `---`.
                preview = previewOf(MemoryMetadataParser.strip(text)),
                shared = shared,
            )
        }
        val sorted = byDate.values.sortedByDescending { it.date }
        return sorted.filter { it.date !in excludeDates }.take(maxRows)
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
        excludeDates: Set<String>,
        maxRows: Int = MAX_ROWS,
    ): String? {
        // [T-memory-index-single-pass] One scan, not two. The old body called
        // rows() twice — once for the page and once at Int.MAX_VALUE purely to
        // learn how many rows were hidden — and each call now also reads and
        // parses frontmatter for every daily file in both scopes. Computing the
        // total from the same List keeps the omitted count exact with half the
        // IO and half the parsing.
        val all = rows(sessionDir, sharedDir, excludeDates, Int.MAX_VALUE)
        if (all.isEmpty()) return null
        val page = all.take(maxRows.coerceAtLeast(1))
        val body = render(page, (all.size - page.size).coerceAtLeast(0)) ?: return null
        return MemoryAuthority.withEnvelope(
            body,
            MemoryTrust.UNVERIFIED_CANDIDATE,
            MemoryOrigin.AGENT,
        )
    }
}
