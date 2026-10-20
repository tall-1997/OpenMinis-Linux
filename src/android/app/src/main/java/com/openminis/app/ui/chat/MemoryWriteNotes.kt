package com.openminis.app.ui.chat

import com.openminis.app.tools.MemoryTools

/**
 * [T-memory-freeze-freshness] Record a memory_write for the session sheet and
 * queue a one-shot note for the next turn context.
 *
 * The system prompt's memory snapshot is frozen per session (cache stability),
 * so a fresh write is not in it. The note rides in the turn context at the tail
 * — it cannot invalidate the cached prefix — and tells the model the entry
 * exists so it reads the full text with `memory_get` instead of claiming it has
 * no memory of the user's instruction.
 */
internal fun ChatViewModel.recordMemoryWrite(result: MemoryTools.ToolResult, content: String) {
    _memoryToolRecords.value = _memoryToolRecords.value + MemoryToolRecord(
        title = result.toolTitle,
        isWrite = true,
        preview = content.lines().firstOrNull { it.isNotBlank() }?.take(100) ?: "",
        output = result.output,
        writtenContent = content,
    )
    if (result.success) {
        promptCache.pendingMemoryWriteNote = content.lines()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.take(160)
    }
}
