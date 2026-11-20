package com.openminis.app.data.repository

import com.openminis.app.evolution.LearnedPrefsStore
import java.io.File

/** File governance is resolved in the physical directory, before any window, query or scope overlay. */
internal object MemorySources {
    private val standing = setOf(MemoryRepository.GLOBAL_FILE, "SOUL.md", "PERSONA.md", "AGENTS.md", LearnedPrefsStore.FILE_NAME)

    fun isRecallSource(name: String): Boolean = name.endsWith(".md") && name !in standing

    class Source(
        val directory: File,
        val name: String,
        val modifiedAt: Long,
        private val pair: MemoryRestoreJournal.PairFiles,
        private val header: MemoryMetadataParser.Header?,
        private val text: String?,
        private val now: Long,
    ) {
        val metadata: MemoryMetadata get() = header?.metadata ?: MemoryMetadata()

        /** Use under [withScope]. The header is stripped only from the projected view, not the digest input. */
        fun view(): MemoryLifecycle.View? {
            val file = pair.diary ?: return null
            val raw = text ?: readTextResilient(file, "memory") ?: return null
            // Host writes are locked. Also reject a guest edit observed between the header and body reads.
            if (text == null && isRecallSource(name) &&
                (file.lastModified() != modifiedAt || MemoryMetadataParser.header(raw) != header)
            ) return null
            return MemoryLifecycle.projectDiary(directory, name, raw, pair.metadata)
                .active(now, header?.bodyStart ?: 0).takeIf { it.hasVisibleText() }
        }
    }

    /**
     * Full scans reuse one read of a file for policy and body. Bounded/targeted
     * readers probe only the leading header of other files, so an old note can
     * retire a recent one without reading the entire old diary or its sidecar.
     * A pending restore is read from the original pair, including its mtime.
     */
    fun <T> withScope(
        directory: File,
        now: Long,
        loadBody: (String) -> Boolean = { false },
        includeStanding: Boolean = false,
        use: (List<Source>) -> T,
    ): T? = try {
        MemoryLifecycle.locked(directory) { dir ->
            val candidates = dir.listFiles().orEmpty().mapNotNull { file ->
                val name = file.name
                val governed = isRecallSource(name)
                if (!governed && !(includeStanding && name in standing)) return@mapNotNull null
                runCatching {
                    val pair = MemoryLifecycle.sourceFiles(dir, name)
                    val diary = pair.diary ?: return@runCatching null
                    val modified = diary.lastModified()
                    val text = if (loadBody(name)) readTextResilient(diary, "memory") ?: return@runCatching null else null
                    if (text != null && diary.lastModified() != modified) return@runCatching null
                    val header = if (!governed) null else if (text != null) MemoryMetadataParser.header(text)
                        else diary.bufferedReader().use(MemoryMetadataParser::header)
                    Source(dir, name, modified, pair, header, text, now)
                }.getOrNull()
            }
            val replacements = MemoryMetadataParser.supersededIds(
                candidates.filter { isRecallSource(it.name) }.associate { it.name to it.metadata },
            )
            use(candidates.filter { it.name !in replacements && it.metadata.isEligible(now, it.modifiedAt) })
        }
    } catch (e: java.io.IOException) {
        android.util.Log.w("MemorySources", "Cannot read memory scope: ${e.javaClass.simpleName}")
        null
    }

    data class Visible(val directory: File, val name: String, val view: MemoryLifecycle.View)

    /** Earlier scope wins only if its file has a visible body, even for targeted revalidation. */
    fun recallSources(directories: List<File>, now: Long, names: Set<String>? = null): Map<String, Visible> {
        val visible = LinkedHashMap<String, Visible>()
        for (directory in directories.distinct()) {
            withScope(directory, now, loadBody = { (names == null || it in names) && it !in visible }) { sources ->
                for (source in sources.sortedByDescending { it.modifiedAt }) {
                    if (source.name in visible || (names != null && source.name !in names)) continue
                    val view = source.view() ?: continue
                    visible[source.name] = Visible(source.directory, source.name, view)
                }
            }
        }
        return visible
    }

    /** Same model-facing lines for BM25, vectors and stale-hit revalidation. */
    fun recallLines(view: MemoryLifecycle.View): List<MemoryLifecycle.OwnedLine> = buildList {
        var inHtmlComment = false
        for (owned in view.lines()) {
            val line = owned.text.removePrefix("\uFEFF").trim()
            if (inHtmlComment || line.startsWith("<!--")) {
                inHtmlComment = "-->" !in line
                continue
            }
            if (owned.isSedimentCaption() || line.none { it.isLetterOrDigit() }) continue
            add(owned.copy(text = line))
        }
    }
}
