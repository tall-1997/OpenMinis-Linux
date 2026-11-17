package com.openminis.app.ui.chat

// [T-android-split-chat] Memory-record mutations extracted verbatim from
// ChatViewModel.kt (architecture ceiling). Members -> same-package extensions:
// call sites are unchanged (receiver-call syntax is identical for members and
// extensions). They touch only internal/public VM state (sessionMemoryRepo(),
// _memoryToolRecords) — no private symbols — and are not overrides of any
// interface, so the member->extension move is behaviour-preserving.

import android.util.Log
import org.json.JSONObject

/**
 * Revoke a previously recorded memory_write by removing its entry from
 * today's or yesterday's daily log on disk, and dropping the row from
 * [memoryToolRecords] so the SessionMemorySheet reflects the removal.
 *
 * Returns the repository result so the UI can show a success / not-found
 * / I/O error dialog. The original ChatMessage tool block stays in the
 * conversation history untouched — only the on-disk entry and the
 * op-log row are mutated.
 */
fun ChatViewModel.revokeMemoryRecord(record: MemoryToolRecord): com.openminis.app.data.repository.MemoryRepository.EntryMutationResult {
    val repo = sessionMemoryRepo()
    val written = record.writtenContent
        ?: return com.openminis.app.data.repository.MemoryRepository.EntryMutationResult.NotFound
    val result = repo.revokeEntry(written)
    if (result is com.openminis.app.data.repository.MemoryRepository.EntryMutationResult.Success) {
        _memoryToolRecords.value = _memoryToolRecords.value - record
    }
    return result
}

/**
 * T149: revoke every `memory_write` tool block embedded in the supplied
 * messages. Used when a retry path truncates the conversation — the
 * deleted assistant turns may have written entries to today's daily
 * memory log, and leaving them on disk after the conversation rewinds
 * means user-visible history is gone but the side effects remain.
 *
 * We match by the `content` field of the tool args against
 * [MemoryToolRecord.writtenContent] (which is what `revokeMemoryRecord`
 * keys on). If multiple records share the same content body — possible
 * if the agent wrote the same note twice — we revoke them in the
 * reverse insertion order so the most recent disk write is removed
 * first; the repository's revokeEntry only removes the first match
 * each call, so subsequent records may end up NotFound on disk but
 * still get pulled from the in-memory record list.
 */
internal fun ChatViewModel.revokeMemoryWritesInDeletedMessages(deletedMessages: List<ChatMessage>) {
    val deletedContents = mutableListOf<String>()
    for (msg in deletedMessages) {
        for (block in msg.toolBlocks) {
            if (block.kind != "tool_use") continue
            if (block.toolName != "memory_write") continue
            val content = try {
                JSONObject(block.toolArgs).optString("content", "")
            } catch (_: Exception) { "" }
            if (content.isNotBlank()) deletedContents.add(content)
        }
    }
    if (deletedContents.isEmpty()) return
    Log.i(ChatViewModel.TAG, "revokeMemoryWritesInDeletedMessages: ${deletedContents.size} write(s) to revoke")
    for (content in deletedContents.asReversed()) {
        // Find the latest matching record so revoke targets the most
        // recent disk entry first. Snapshot value because revoke mutates
        // the flow.
        val record = _memoryToolRecords.value.lastOrNull {
            it.isWrite && it.writtenContent == content
        } ?: continue
        val result = revokeMemoryRecord(record)
        Log.i(ChatViewModel.TAG, "  revoke result: ${result::class.simpleName}")
    }
}

/**
 * Replace the body of a previously recorded memory_write with
 * [newContent]. Mirrors iOS `MemoryWriteDetailView.replaceEntryInLog`.
 * On success, also updates the in-memory [MemoryToolRecord] so a
 * subsequent revoke or revisit sees the new body.
 */
fun ChatViewModel.replaceMemoryRecord(
    record: MemoryToolRecord,
    newContent: String,
): com.openminis.app.data.repository.MemoryRepository.EntryMutationResult {
    val repo = sessionMemoryRepo()
    val old = record.writtenContent
        ?: return com.openminis.app.data.repository.MemoryRepository.EntryMutationResult.NotFound
    val result = repo.replaceEntryBody(old, newContent)
    if (result is com.openminis.app.data.repository.MemoryRepository.EntryMutationResult.Success) {
        _memoryToolRecords.value = _memoryToolRecords.value.map {
            if (it == record) it.copy(
                writtenContent = newContent,
                preview = newContent.lines().firstOrNull { line -> line.isNotBlank() }?.take(100) ?: "",
            ) else it
        }
    }
    return result
}
