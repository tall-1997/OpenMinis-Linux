package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

/**
 * [T-stall-resume] One-shot USER-note rider on request assembly: the bounded
 * continuation note after a mid-stream stall. An assembly artifact only — it
 * is never persisted into agentHistory.
 *
 * No other note uses this lane: a synthetic USER turn makes the model stop and
 * answer in prose mid-task (ZCode/DeepSeek-Harness never do that).
 */
internal fun ChatViewModel.applyStallNudge(
    history: List<LLMMessage>,
    note: String?,
): List<LLMMessage> {
    if (note == null) return history
    return history + LLMMessage(
        role = LLMMessage.Role.USER,
        content = note,
        contentParts = listOf(AgentContentPart.Text(note)),
    )
}

// ─── Shared live-interruption UI teardown ────────────────────────────────────

/** Drop the streaming side-channel entry for [id] and cancel its trailing flush. */
internal fun ChatViewModel.dropStreamingEntry(id: String) {
    clearStreamFlushState(id)
    if (_streamingById.value.containsKey(id)) {
        _streamingById.value = _streamingById.value - id
    }
}

/**
 * Publish the final bubble text with streaming off, then drop the streaming
 * side-channel entry. MUST run on Dispatchers.Main (callers hop) — a late delta
 * after the loop exits would otherwise keep the "thinking" row alive forever.
 */
internal fun ChatViewModel.drainLiveAssistantUi(
    assistantId: String,
    text: String,
    blocks: List<AssistantBlock>,
) {
    updateAssistantMessage(
        assistantId, text, false, blocks,
        isAwaitingModelResponse = false,
    )
    dropStreamingEntry(assistantId)
}

/** A LIVE interruption happened: refresh the badge stamp and arm resume. */
internal fun ChatViewModel.markLiveInterruptionAndArmResume(fromResumeEpoch: Long) {
    markLiveInterruption()
    raiseCanResume(fromResumeEpoch)
}
