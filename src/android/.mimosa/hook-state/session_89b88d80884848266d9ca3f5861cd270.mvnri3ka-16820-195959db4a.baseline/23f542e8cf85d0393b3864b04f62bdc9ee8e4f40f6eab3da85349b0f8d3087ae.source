package com.openminis.app.ui.chat

import androidx.lifecycle.viewModelScope
import com.openminis.app.agent.StreamDropReminder
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Commit the "your reply was cut off in transit — continue from there"
 * reminder for the session whose stream just dropped, in memory AND in the DB.
 *
 * Why the DB copy matters and not just [ChatViewModel.appendBoundedHistory]:
 *
 * A dropped stream leaves a PARTIAL assistant turn as the history tail, and
 * [com.openminis.app.agent.InterruptedTailDetector] classifies a plain
 * assistant reply as a COMPLETED turn (`NONE`). So if the reminder only lives
 * in memory, any reload or process death between the drop and the user's next
 * tap erases the evidence that the turn was interrupted: `canResume` comes
 * back false, the Resume affordance never appears, and the only thing left on
 * screen is the error banner — whose Retry then has nothing to resume.
 *
 * With the reminder persisted, the tail is a `CONTINUE_REMINDER` shape, which
 * the detector recognises across a cold start, so "continue where you left
 * off" survives exactly as long as the partial reply it refers to.
 *
 * The row is registered with a null UI id ([ChatViewModel.notePersistedUiRow])
 * so the synthetic turn never renders as a user bubble — same treatment the
 * deliberate-stop reminder gets in [resume].
 */
internal fun ChatViewModel.commitStreamDropReminder() {
    val reminder = StreamDropReminder.TEXT
    appendBoundedHistory(
        LLMMessage(
            role = LLMMessage.Role.USER,
            content = reminder,
            contentParts = listOf(AgentContentPart.Text(reminder)),
        ),
    )
    // Read the session id up front: the IO hop below can outlive a session
    // switch, and a reminder written into the wrong session's history would
    // make THAT session look interrupted.
    val sid = activeSessionId
    viewModelScope.launch(Dispatchers.IO) {
        val partsJson = """[{"type":"text","value":${escapeJson(reminder)}}]"""
        val persisted = chatRepository.appendMessage(sid, "user", partsJson)
        withContext(Dispatchers.Main.immediate) {
            notePersistedUiRow(null, persisted.id)
        }
    }
}
