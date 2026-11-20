package com.openminis.app.data.repository

import com.openminis.app.tools.AtomicFileWrite
import com.openminis.app.util.IsoTime
import java.io.File
import java.time.LocalDate

/** Explicit user actions on the Markdown source; no history is removed by lifecycle policy. */
internal object MemoryDiaryManagement {
    data class ArchiveEntry(val token: String, val body: String, val status: MemoryLifecycle.Status)

    fun archive(directory: File, now: Long, query: String = "", limit: Int = Int.MAX_VALUE): Sequence<ArchiveEntry> {
        val terms = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val files = directory.listFiles().orEmpty().filter { MemoryDiaryFormat.dateFile.matches(it.name) }
            .sortedByDescending { it.name }
        // [P3-8] Hard scan bound for the RECENT window, anchored on the diary
        // timeline's newest file (not the lifecycle clock). The recent window
        // gets the full body parse; OLDER diaries are admitted through their
        // sidecar alone.
        val oldest = files.firstOrNull()?.name?.removeSuffix(".md")?.let { newest ->
            runCatching {
                LocalDate.parse(newest).minusDays((MemoryRepository.MAX_LOOKBACK_DAYS - 1).toLong()).toString()
            }.getOrNull()
        }
        return files.asSequence()
            .flatMap { file ->
                val inRecentWindow = oldest == null || file.name.removeSuffix(".md") >= oldest
                // [T-archive-window-admission] Lifecycle-gated entries outlive any
                // fixed window (idle archive at 60 days, TTL up to 3650): an
                // out-of-window diary is admitted through its small sidecar JSON —
                // the body (with its digest hashing) is parsed only when a
                // non-ACTIVE row actually exists, so the archive query still
                // cannot degenerate into a full-history body scan.
                if (inRecentWindow || MemoryLifecycle.hasNonActiveRow(directory, file.name, now)) {
                    MemoryLifecycle.read(directory, file.name)?.managed().orEmpty().asSequence()
                } else {
                    emptySequence()
                }
            }
            .mapNotNull { entry ->
                val status = entry.state.status(now)
                if (status == MemoryLifecycle.Status.ACTIVE || !terms.all { entry.body.contains(it, ignoreCase = true) }) null
                else ArchiveEntry("${entry.ref.source.removeSuffix(".md")}:${entry.ref.id}", entry.body, status)
            }
            .take(limit)
    }

    /** Change only the matched entry, including when it carries a host ID or a BOM. */
    fun editRecent(directory: File, now: Long, original: String, replacement: String?): MemoryRepository.EntryMutationResult {
        val target = original.trim()
        val body = replacement?.trim()
        if (body != null && (body.isBlank() || MemoryDiaryFormat.marker.containsMatchIn(body))) {
            return MemoryRepository.EntryMutationResult.IOError("Invalid diary entry")
        }
        val today = LocalDate.parse(IsoTime.formatLocalDate(now))
        try {
            return MemoryLifecycle.locked(directory) { dir ->
                for (day in listOf(today, today.minusDays(1))) {
                    val source = "$day.md"
                    MemoryRestoreJournal.recover(dir, source)
                    val file = MemoryLifecycle.child(dir, source)?.takeIf { it.isFile } ?: continue
                    val matched = AtomicFileWrite.readModifyWrite(file) { content ->
                        val diary = MemoryLifecycle.read(dir, source)
                        val managedEnds = diary?.editEnds(content).orEmpty()
                        val block = MemoryDiaryFormat.blocks(content).firstOrNull {
                            val end = managedEnds[it.id] ?: it.end
                            content.substring(it.bodyStart, end).trim() == target
                        }
                        if (block == null) Edit(null, changed = false) else {
                            val end = managedEnds[block.id] ?: block.end
                            val next = when {
                                body == null -> ""
                                body == target -> return@readModifyWrite Edit(null, changed = true)
                                else -> "${block.legacyMarker}\n$body\n\n" // Now authored by the user; remove the host ID.
                            }
                            Edit(content.replaceRange(block.start, end, next), changed = true)
                        }
                    } ?: return@locked MemoryRepository.EntryMutationResult.IOError("Could not write $source")
                    if (matched.changed) return@locked MemoryRepository.EntryMutationResult.Success(day.toString())
                }
                MemoryRepository.EntryMutationResult.NotFound
            }
        } catch (e: Exception) {
            return MemoryRepository.EntryMutationResult.IOError(e.message ?: "Could not edit diary")
        }
    }

    private data class Edit(val content: String?, val changed: Boolean) : com.openminis.app.tools.HasPersistableText {
        override fun persistableText(): String? = content
    }
}
