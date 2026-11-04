package com.openminis.app.data.repository

import java.io.File

/**
 * [T-memory-center-stats] Numbers for the memory hub.
 *
 * The hub used to be pure navigation: it told the user where things live but not
 * whether anything is there. These counters answer the question a user actually
 * opens that screen with — "is my memory working, and how much of it is there?"
 *
 * Everything here is a bounded filesystem walk; there is no database access and
 * no network. The walk is capped ([MAX_SESSION_DIRS], [MAX_FILES_SCANNED]) so a
 * device with hundreds of chats still renders the screen in one pass instead of
 * indexing the whole workspace.
 *
 * Pure apart from file reads, so the counting rules are unit-testable.
 */
object MemoryCenterStats {

    /** Session directories examined, newest names first. */
    const val MAX_SESSION_DIRS = 120

    /** Diary files opened while counting sediments. */
    const val MAX_FILES_SCANNED = 400

    /** Written by [MemorySediment]; counting the label is enough. */
    const val SEDIMENT_MARKER = MemorySediment.LABEL

    const val RECALL_SIDECAR = ".recall-counts.json"

    /** Same ledger the known-failure gate writes. */
    const val FAILURE_LEDGER = com.openminis.app.tools.ToolFailureMemory.FILE_NAME

    private val DAILY = Regex("""^\d{4}-\d{2}-\d{2}\.md$""")

    data class Snapshot(
        val files: Int,
        /** Distinct entries the recall engines have served at least once. */
        val recalled: Int,
        /** Tool calls the known-failure gate is currently blocking. */
        val blockedRepeats: Int,
        /** Compaction summaries written into diaries. */
        val sediments: Int,
    )

    fun collect(filesDir: File): Snapshot {
        val shared = File(filesDir, MemoryIndex.SHARED_DIR)
        val sharedFiles = shared.listFiles()?.filter { it.isFile && it.extension == "md" }.orEmpty()

        var recalled = jsonKeyCount(File(shared, RECALL_SIDECAR))
        var blockedRepeats = 0
        var sediments = 0
        var scanned = 0

        val sessions = File(filesDir, "minis-sessions")
            .listFiles()
            ?.filter { it.isDirectory }
            ?.sortedByDescending { it.name }
            ?.take(MAX_SESSION_DIRS)
            .orEmpty()

        for (session in sessions) {
            blockedRepeats += jsonKeyCount(File(session, FAILURE_LEDGER))
            val memory = File(session, "memory")
            recalled += jsonKeyCount(File(memory, RECALL_SIDECAR))
            if (scanned >= MAX_FILES_SCANNED) continue
            val diaries = memory.listFiles()
                ?.filter { it.isFile && DAILY.matches(it.name) }
                .orEmpty()
            for (diary in diaries) {
                if (scanned >= MAX_FILES_SCANNED) break
                scanned += 1
                val text = runCatching { diary.readText() }.getOrNull() ?: continue
                var index = text.indexOf(SEDIMENT_MARKER)
                while (index >= 0) {
                    sediments += 1
                    index = text.indexOf(SEDIMENT_MARKER, index + SEDIMENT_MARKER.length)
                }
            }
        }

        return Snapshot(
            files = sharedFiles.size,
            recalled = recalled,
            blockedRepeats = blockedRepeats,
            sediments = sediments,
        )
    }

    /** Number of keys in a JSON object file; 0 when missing or unreadable. */
    private fun jsonKeyCount(file: File): Int {
        if (!file.isFile) return 0
        return runCatching { org.json.JSONObject(file.readText()).length() }.getOrDefault(0)
    }
}
