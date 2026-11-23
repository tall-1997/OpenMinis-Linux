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
    // [T-time-context-no-nag] A clock note is a THROTTLED aid, and the two
    // inputs mean different things:
    //  - an explicit time question ("现在几点") may pull the clock forward,
    //    but still at most once per timestamp — never once per request;
    //  - the implicit 10-minute refresh needs no question at all.
    // The old `!shouldInject && !hasTimeHint` form was "inject when either
    // holds", and with the loose hint list nearly every turn qualified, so a
    // note landed on every request ("(since previous message: 0s)" noise) and
    // the model kept treating each note as a cue to re-verify.
    val latestText = latestUser?.content.orEmpty()
    // [T-time-context-no-nag] History that already carries a clock note is
    // settled: whatever the question or the interval says, appending a second
    // stamp re-teaches the model that notes arrive per request.
    if (MessageTransformerChain.carriesClockNote(latestText)) return null
    val askedForTime = MessageTransformerChain.hasTimeHint(latestText)
    if (!askedForTime && !TimeContextGate.shouldInject(sessionId, nowMs)) return null
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
