package com.openminis.app.ui.chat

import com.openminis.app.agent.MessageTransformerChain
import com.openminis.app.agent.TimeContextGate
import com.openminis.app.data.model.LLMMessage

/** Planning is read-only; only a provider response carrying model progress confirms delivery. */
internal data class RequestClockNote(val sessionId: String, val stamp: String, val text: String) {
    fun confirm(nowMs: Long = System.currentTimeMillis()) {
        TimeContextGate.markInjected(sessionId, nowMs)
        TimeContextGate.markStamped(sessionId, stamp)
    }
}

internal fun requestClockNote(
    sessionId: String,
    history: List<LLMMessage>,
    nowMs: Long = System.currentTimeMillis(),
): RequestClockNote? {
    if (sessionId.isBlank() || history.none { it.role == LLMMessage.Role.USER }) return null
    val latestUser = history.lastOrNull(::receiptUserRow)
    if (!TimeContextGate.shouldInject(sessionId, nowMs) &&
        !MessageTransformerChain.hasTimeHint(latestUser?.content.orEmpty())
    ) return null
    val stamp = MessageTransformerChain.clockStamp(nowMs)
    if (TimeContextGate.alreadyStamped(sessionId, stamp)) return null
    val note = MessageTransformerChain.renderClockNote(stamp, nowMs, TurnClock.previousMessageAt(sessionId))
    return RequestClockNote(sessionId, stamp, note)
}

internal fun ProjectedChatRequest.markModelProgress(sessionId: String, nowMs: Long = System.currentTimeMillis()) {
    if (!carriesChatContext) return
    clockNote?.takeIf { it.sessionId == sessionId }?.confirm(nowMs)
    TurnClock.markModelVisible(sessionId, nowMs)
}
