package com.openminis.app.ui.chat

import com.openminis.app.logging.AppLogger
import com.openminis.app.scheduled.PrefilledToolCall
import com.openminis.app.scheduled.ScriptedToolTurn

/**
 * [T-scheduled-tool-prefill] The scripted first turn for [prefill], or null
 * when there is nothing to run. Calls to a tool this session does not offer
 * are dropped with a warning: a history showing the model calling a tool it
 * was never given is one it could not have produced, so the prompt is left to
 * the model instead — the behaviour a task had before prefill.
 */
internal fun ChatViewModel.scriptedTurnFor(prefill: List<PrefilledToolCall>): ScriptedToolTurn? {
    if (prefill.isEmpty()) return null
    val turn = ScriptedToolTurn.from(prefill, agentTools.map { it.name }.toSet())
    if (turn == null || turn.calls.size < prefill.size) {
        AppLogger.warning(
            ChatViewModel.TAG_STREAM,
            "[ScheduledPrefill] dropped ${prefill.size - (turn?.calls?.size ?: 0)} of ${prefill.size} " +
                "prefilled call(s) [${prefill.joinToString { it.toolName }}] — tool unavailable or invalid; " +
                "the model decides for itself",
        )
    }
    return turn
}

/**
 * [T-scheduled-tool-prefill] A fire that ends up somewhere other than the
 * agent loop — a group-chat session answers with its own discussion turn, a
 * video model renders instead of running tools — has no first turn to hand
 * the call to. Say so: a task that promised a call must not lose it in
 * silence. The model reading the prompt itself is the fallback, not a failure.
 */
internal fun reportPrefillUnused(prefill: List<PrefilledToolCall>, why: String) {
    if (prefill.isEmpty()) return
    AppLogger.warning(
        ChatViewModel.TAG_STREAM,
        "[ScheduledPrefill] dropped ${prefill.size} prefilled call(s) " +
            "[${prefill.joinToString { it.toolName }}] — $why",
    )
}
