package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

/**
 * [T-stall-resume] A previous attempt stalled mid-stream: append the bounded
 * continuation note so this attempt picks up where it stopped instead of
 * regenerating the same text. Extracted verbatim from the agent loop; the note
 * is read per request there, so the caller passes it in.
 */
internal fun appendStallResumeNote(
    history: List<LLMMessage>,
    note: String?,
): List<LLMMessage> = if (note == null) history else history + LLMMessage(
    role = LLMMessage.Role.USER,
    content = note,
    contentParts = listOf(AgentContentPart.Text(note)),
)
