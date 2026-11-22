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
    const val MAX_ROWS = 100

    /** App-wide memory directory, relative to `filesDir`. */
    const val SHARED_DIR = "minis-global/memory"

    /** The app-wide memory directory — the second scope this index reads. */
    fun sharedDir(filesDir: File): File = File(filesDir, SHARED_DIR)

    /** Chars of the first entry kept as a preview. */
    internal const val PREVIEW_CHARS = 80

    private val ENTRY_MARK = MemoryDiaryFormat.marker

    internal data class TrackedFragment(val text: String, val entries: List<MemoryDeliverySpan> = emptyList())

    /** The note follows existing rows; all entry offsets remain relative to the original prefix. */
    internal fun appendBudgetNote(toc: TrackedFragment?, note: String?): TrackedFragment? = when {
        note.isNullOrBlank() -> toc
        toc == null -> TrackedFragment(note)
        else -> toc.copy(text = "${toc.text}\n$note")
    }
    private data class IndexedDay(val row: Row, val previewRef: MemoryEntryRef?)

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
        now: Long = System.currentTimeMillis(),
    ): List<Row> = indexedDays(sessionDir, sharedDir, excludeDates, now).take(maxRows).map { it.row }

    private fun indexedDays(
        sessionDir: File?,
        sharedDir: File?,
        excludeDates: Set<String>,
        now: Long,
    ): List<IndexedDay> {
        val byDate = LinkedHashMap<String, IndexedDay>()
        // The first visible copy wins, as in recall. Excluded dates still participate in file governance.
        for ((dir, shared) in listOf(sessionDir to false, sharedDir to true)) {
            if (dir == null) continue
            MemorySources.withScope(dir, now, loadBody = { name ->
                val date = name.removeSuffix(".md")
                MemoryDiaryFormat.dateFile.matches(name) && date !in excludeDates && date !in byDate
            }) { sources ->
                for (source in sources) {
                    if (!MemoryDiaryFormat.dateFile.matches(source.name)) continue
                    val date = source.name.removeSuffix(".md")
                    if (date in excludeDates || date in byDate) continue
                    val view = source.view() ?: continue
                    val entries = ENTRY_MARK.findAll(view.text).count()
                    val (preview, ref) = previewOf(view)
                    byDate[date] = IndexedDay(Row(date, entries, preview, shared), ref)
                }
            }
        }
        return byDate.values.sortedByDescending { it.row.date }
    }

    private fun previewOf(view: MemoryLifecycle.View): Pair<String, MemoryEntryRef?> {
        for (owned in view.lines()) {
            if (owned.isSedimentCaption()) continue
            val (line, entry) = owned
            val trimmed = line.removePrefix("\uFEFF").trim()
            if (trimmed.isEmpty() || ENTRY_MARK.matches(trimmed)) continue
            val cleaned = MemoryAuthority.stripForgedTag(trimmed.removePrefix("#").trim())
            if (cleaned.isEmpty() || cleaned.startsWith("<!--")) continue
            val preview = if (cleaned.length <= PREVIEW_CHARS) cleaned else memoryTextPrefix(cleaned, PREVIEW_CHARS) + "…"
            return preview to entry?.ref
        }
        return "" to null
    }

    /** Renders [rows]; returns null when there is nothing to index. */
    fun render(rows: List<Row>, omitted: Int = 0): String? =
        renderTracked(rows.map { IndexedDay(it, null) }, omitted)?.text

    private fun renderTracked(rows: List<IndexedDay>, omitted: Int): TrackedFragment? {
        if (rows.isEmpty()) return null
        val spans = mutableListOf<MemoryDeliverySpan>()
        val text = buildString {
            append("Older diary days (index only — call memory_get with a keyword to read one):\n")
            for ((row, ref) in rows) {
                append("- ")
                append(row.date)
                append(" (")
                append(row.entries)
                append(if (row.entries == 1) " entry" else " entries")
                append(if (row.shared) " · shared" else " · this chat")
                append("): ")
                if (row.preview.isNotEmpty()) {
                    append('"')
                    val start = length
                    append(row.preview)
                    if (ref != null) spans += MemoryDeliverySpan(ref, start, length)
                    append('"')
                }
                appendLine()
            }
            if (omitted > 0) {
                append("… (").append(omitted).append(" older day(s) not listed; memory_get can still search them)")
            }
        }.trimEnd()
        return TrackedFragment(text, spans)
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
    ): String? = fragmentWithEntries(sessionDir, sharedDir, excludeDates, maxRows)?.text

    internal fun fragmentWithEntries(
        sessionDir: File?,
        sharedDir: File?,
        excludeDates: Set<String>,
        maxRows: Int = MAX_ROWS,
        now: Long = System.currentTimeMillis(),
    ): TrackedFragment? {
        val all = indexedDays(sessionDir, sharedDir, excludeDates, now)
        val shown = all.take(maxRows)
        val body = renderTracked(shown, all.size - shown.size) ?: return null
        val text = MemoryAuthority.withEnvelope(body.text, MemoryTrust.UNVERIFIED_CANDIDATE, MemoryOrigin.AGENT)
        val prefix = text.length - body.text.length
        return body.copy(text = text, entries = body.entries.map { it.shifted(prefix) })
    }
}
