package com.openminis.app.ui.chat

/**
 * [T-tool-undo-replay] Turn-level file change set for the ZCode-style
 * "changes this turn" card.
 *
 * Semantics follow the two open harnesses studied for this feature:
 * - zai-org/ZCode `file-rewind`: the per-file inverse is either "restore"
 *   (write the pre-image back) or "delete" (the file did not exist before
 *   the turn). The pre-image is whatever the file held BEFORE the turn's
 *   first tool touched it — i.e. the EARLIEST checkpoint of that path in
 *   the turn window, not the latest one.
 * - deepseek-ai/deepseek-harness `session-checkpoint-policy`: the record
 *   that undoes a side effect is durable before the side effect lands
 *   (FileCheckpointStore.capture already runs inside every file tool).
 *
 * This object is pure: aggregation reads only tool blocks; checkpoint
 * resolution (IO) happens in the ViewModel at undo time.
 */
object TurnChanges {

    const val TOOL_FILE_WRITE = "file_write"
    const val TOOL_FILE_EDIT = "file_edit"
    const val TOOL_MULTI_EDIT = "multi_edit"

    val UNDOABLE_TOOLS = setOf(TOOL_FILE_WRITE, TOOL_FILE_EDIT, TOOL_MULTI_EDIT)

    /** Path, or null when args are malformed / path-less (kept out of the card). */
    data class PathChange(
        val path: String,
        val toolNames: List<String>,
        val firstStartMs: Long,
        val lastEndMs: Long,
    )

    /**
     * Aggregates the file-mutating tool blocks of ONE assistant message into
     * a per-path net change list, in first-touch order. A path touched by
     * several calls (write then edit then edit) appears ONCE — undo restores
     * the whole turn's net effect on that path.
     *
     * Streaming blocks and failures are excluded: only SUCCESS calls whose
     * args carry a parseable path. Never throws.
     */
    fun collect(blocks: List<AssistantBlock>): List<PathChange> {
        val order = ArrayList<PathChange>()
        val byPath = LinkedHashMap<String, PathChange>()
        for (block in blocks) {
            if (block.kind != "tool_use") continue
            if (block.toolStatus != ToolBlockStatus.SUCCESS) continue
            if (block.toolName !in UNDOABLE_TOOLS) continue
            val path = try {
                org.json.JSONObject(block.toolArgs).optString("path", "").trim()
            } catch (_: Exception) {
                ""
            }
            if (path.isEmpty()) continue
            val start = block.startTimeMs
            val end = block.startTimeMs + block.durationMs.coerceAtLeast(0L)
            val existing = byPath[path]
            if (existing == null) {
                val change = PathChange(
                    path = path,
                    toolNames = listOf(block.toolName),
                    firstStartMs = start,
                    lastEndMs = end,
                )
                byPath[path] = change
                order.add(change)
            } else {
                val merged = existing.copy(
                    toolNames = existing.toolNames + block.toolName,
                    lastEndMs = maxOf(existing.lastEndMs, end),
                )
                byPath[path] = merged
                order[order.indexOfFirst { it.path == path }] = merged
            }
        }
        return order
    }

    /** Undo-state key for one file row of one turn card. */
    fun revertedKey(messageId: String, path: String): String = "$messageId\n$path"
}
