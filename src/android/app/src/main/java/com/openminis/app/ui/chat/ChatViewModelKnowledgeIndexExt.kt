package com.openminis.app.ui.chat

import com.openminis.app.MinisApp
import com.openminis.app.data.repository.KnowledgeNoteIndex
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [T-memory-recall-multisource] Keeps the knowledge-note mirror used by recall
 * fresh.
 *
 * Recall runs inside prompt assembly (synchronous), while notes live in Room
 * (suspend), so the mirror is refreshed from here: session load is a suspend
 * point that every chat passes through, the TTL keeps it to one query per five
 * minutes, and any failure degrades to "recall has no notes" rather than
 * breaking prompt assembly.
 */
internal suspend fun ChatViewModel.refreshKnowledgeNoteIndex() {
    if (!KnowledgeNoteIndex.isStale()) return
    val app = context.applicationContext as? MinisApp ?: return
    withContext(Dispatchers.IO) {
        runCatching {
            val notes = app.knowledgeNoteRepository.listActive().map { it.title to it.bodyMd }
            KnowledgeNoteIndex.refresh(notes)
            AppLogger.info(
                "KnowledgeNoteIndex",
                "[T-memory-recall-multisource] mirror refreshed: ${notes.size} active note(s)",
            )
        }.onFailure {
            AppLogger.warning(
                "KnowledgeNoteIndex",
                "[T-memory-recall-multisource] refresh failed, recall keeps the previous mirror: ${it.message}",
            )
        }
    }
}
