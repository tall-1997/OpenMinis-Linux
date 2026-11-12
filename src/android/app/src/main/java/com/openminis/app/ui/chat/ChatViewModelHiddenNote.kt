package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [T-hidden-user-note] Append a host-authored note to the conversation ONCE,
 * in memory and in the DB, and never re-inject it per request.
 *
 * ZCode's avoid-shape: notices (recalled memory, goal objective, session
 * startup context) are written into the transcript once and then ride the
 * normal history. Rebuilding them into every request's system prompt is what
 * made the model read the same task state over and over and circle.
 *
 * The row is registered with a null UI id ([ChatViewModel.notePersistedUiRow])
 * so a synthetic turn never renders as a user bubble — the same treatment
 * [commitStreamDropReminder] uses.
 */
internal suspend fun ChatViewModel.commitHiddenUserNote(text: String) {
    if (text.isBlank()) return
    appendBoundedHistory(
        LLMMessage(
            role = LLMMessage.Role.USER,
            content = text,
            contentParts = listOf(AgentContentPart.Text(text)),
        ),
    )
    // Read the session id up front: the IO hop can outlive a session switch.
    val sid = activeSessionId
    val partsJson = """[{"type":"text","value":${escapeJson(text)}}]"""
    val persisted = withContext(Dispatchers.IO) {
        runCatching { chatRepository.appendMessage(sid, "user", partsJson) }.getOrNull()
    } ?: return
    withContext(Dispatchers.Main.immediate) {
        notePersistedUiRow(null, persisted.id)
    }
}
