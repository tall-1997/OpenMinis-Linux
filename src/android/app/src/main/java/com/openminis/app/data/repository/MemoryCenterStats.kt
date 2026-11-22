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

    /** Session directories examined, most recently modified first. */
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

        var recalled = 0
        var blockedRepeats = 0
        var sediments = 0
        var scanned = 0

        val sessions = File(filesDir, "minis-sessions")
            .listFiles()
            ?.filter { it.isDirectory }
            // UUIDs are opaque, so name order is not "newest"; use the workspace
            // mtime as a bounded best-effort recency signal for the capped walk.
            ?.sortedByDescending { it.lastModified() }
            ?.take(MAX_SESSION_DIRS)
            .orEmpty()

        val sessionMemories = sessions.map { session ->
            blockedRepeats += jsonKeyCount(File(session, FAILURE_LEDGER))
            File(session, "memory")
        }
        for (memory in sessionMemories + shared) {
            // [T-memory-center-distinct] The legacy `.recall-counts.json` sidecar
            // is frozen (nothing writes it since delivery receipts landed) but
            // still describes entries served before the upgrade. Summing its key
            // count with the lifecycle counter counted any entry that was served
            // both before and after the upgrade twice, so "distinct entries
            // served at least once" could read nearly 2×. Match the old keys to
            // the entries that own them and count the union.
            val sidecarKeys = recallSidecarKeys(File(memory, RECALL_SIDECAR))
            val matchedKeys = HashSet<String>()
            var complete = true
            val diaries = memory.listFiles().orEmpty().filter { it.isFile && DAILY.matches(it.name) }
            for (file in diaries) {
                if (scanned >= MAX_FILES_SCANNED) {
                    complete = false
                    break
                }
                scanned += 1
                val diary = MemoryLifecycle.read(memory, file.name) ?: continue
                val day = if (file.name == MemoryRepository.GLOBAL_FILE) "global" else file.name.removeSuffix(".md")
                for (entry in diary.managed()) {
                    val legacyKey = if (sidecarKeys.isEmpty()) null
                    else MemoryRecallEngine.recallCountKey(day, entry.body)
                    if (legacyKey != null && legacyKey in sidecarKeys) {
                        matchedKeys += legacyKey
                        recalled += 1
                    } else if (entry.state.usage > 0) {
                        recalled += 1
                    }
                }
                if (memory != shared) {
                    var index = diary.text.indexOf(SEDIMENT_MARKER)
                    while (index >= 0) {
                        sediments += 1
                        index = diary.text.indexOf(SEDIMENT_MARKER, index + SEDIMENT_MARKER.length)
                    }
                }
            }
            // Keys that no surviving entry claims (a deleted or edited diary)
            // still happened; count them once when the walk covered the scope.
            if (complete && sidecarKeys.isNotEmpty()) recalled += (sidecarKeys - matchedKeys).size
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

    private val OPAQUE_COUNT_KEY = Regex("v2:[0-9a-f]{64}")

    /**
     * The sidecar's key set, normalized the same way [MemoryRecallEngine] loads
     * it, so a legacy `day:line` key resolves to the v2 hash the entry itself
     * produces.
     */
    private fun recallSidecarKeys(file: File): Set<String> {
        if (!file.isFile) return emptySet()
        return runCatching {
            val obj = org.json.JSONObject(file.readText())
            buildSet {
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key.isBlank() || obj.optInt(key, 0) <= 0) continue
                    add(
                        if (OPAQUE_COUNT_KEY.matches(key)) key
                        else MemoryRecallEngine.recallCountKey(key.substringBefore(':'), key.substringAfter(':', "")),
                    )
                }
            }
        }.getOrDefault(emptySet())
    }
}
