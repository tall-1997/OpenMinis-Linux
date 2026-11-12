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
/** [T-recall-once] Reset one-shot notice bookkeeping when the session changes. */
internal fun ChatViewModel.ensureSessionScopedNotices() {
    val sid = activeSessionId
    if (sid != noticesSessionId) {
        noticesSessionId = sid
        announcedRecallKeys.clear()
        goalAnnouncedSha = null
    }
}

internal suspend fun ChatViewModel.commitHiddenUserNote(text: String) {
    if (text.isBlank()) return
    // [T-hidden-user-note] Harness framing, not user speech: ZCode delivers
    // recalled memory / objective as a `<system-reminder>` notice so the model
    // does not treat it as a request to answer.
    val framed = "<system-reminder>\n$text\n</system-reminder>"
    appendBoundedHistory(
        LLMMessage(
            role = LLMMessage.Role.USER,
            content = framed,
            contentParts = listOf(AgentContentPart.Text(framed)),
        ),
    )
    // Read the session id up front: the IO hop can outlive a session switch.
    val sid = activeSessionId
    val partsJson = """[{"type":"text","value":${escapeJson(framed)}}]"""
    val persisted = withContext(Dispatchers.IO) {
        runCatching { chatRepository.appendMessage(sid, "user", partsJson) }.getOrNull()
    } ?: return
    withContext(Dispatchers.Main.immediate) {
        notePersistedUiRow(null, persisted.id)
    }
}
