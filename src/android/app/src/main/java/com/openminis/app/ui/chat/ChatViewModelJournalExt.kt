package com.openminis.app.ui.chat

import com.openminis.app.data.model.LLMMessage

/**
 * [T-session-journal] The write-side seam between the agent loop and the
 * append-only journal.
 *
 * Kept in its own file for two reasons:
 *  - `ChatViewModelAgentLoopExt.kt` sits exactly on its architecture ceiling
 *    (1897 lines, ratcheted), so a multi-line call site there is not allowed;
 *    the loop gets ONE line and the payload construction lives here;
 *  - the payload is policy (what is worth recording), and policy belongs next
 *    to the other request-assembly seams rather than inside the loop body.
 *
 * Contract: never throws, never blocks the turn, never influences the request.
 * A journal failure must be invisible to the model and the user.
 */
internal fun ChatViewModel.recordRequestJournal(
    turn: Int,
    history: List<LLMMessage>,
    droppedRounds: Int = 0,
) {
    // [T-session-journal-turn-bounds] Turn and step bounds are derived here
    // rather than at the loop's own call sites: `ChatViewModelAgentLoopExt` sits
    // exactly on its ratcheted ceiling, and every request already knows its
    // turn. A new turn number is a `turn/start`+`turn/end` pair around the
    // previous one only in the sense of ordering — the journal is a timeline,
    // not a balanced tree — so the boundary is recorded once per change.
    val sid = realSessionId.ifEmpty { sessionId }
    if (lastJournalTurn[sid] != turn) {
        lastJournalTurn[sid] = turn
        recordTurnJournal(turn, starting = true)
        SessionJournal.record(
            filesDir = context.filesDir,
            sessionId = sid,
            kind = SessionJournal.STEP_START,
            fields = mapOf("turn" to turn),
        )
    }
    runCatching {
        SessionJournal.recordRequest(
            filesDir = context.filesDir,
            sessionId = realSessionId.ifEmpty { sessionId },
            turn = turn,
            messages = history,
            estimatedTokens = estimateMessagesTokens(history),
            providerTokens = _lastTurnContextTokens.value,
            summaryChars = _compactSummary.value?.length ?: 0,
            markerId = _cachedLatestMarker?.id,
            droppedRounds = droppedRounds,
        )
    }
}

/**
 * [T-session-journal] Turn/step bounds. Recorded so a reader can group requests
 * without inferring boundaries from timing.
 */
/** [T-session-journal-turn-bounds] Last turn number written per session. */
private val lastJournalTurn = java.util.concurrent.ConcurrentHashMap<String, Int>()

/**
 * [T-session-journal-turn-bounds] One tool round, recorded from the tool
 * producer. Call/result both carry the pairing key so a reader can rebuild the
 * round without the transcript.
 */
internal fun ChatViewModel.recordToolJournal(turn: Int, tool: String, argsJson: String, success: Boolean) {
    val sid = realSessionId.ifEmpty { sessionId }
    val key = ToolPairingCore.pairKey(tool, argsJson)
    SessionJournal.record(
        filesDir = context.filesDir,
        sessionId = sid,
        kind = SessionJournal.TOOL_CALL,
        fields = mapOf("turn" to turn, "tool" to tool, "key" to key),
    )
    SessionJournal.record(
        filesDir = context.filesDir,
        sessionId = sid,
        kind = SessionJournal.TOOL_RESULT,
        fields = mapOf("turn" to turn, "tool" to tool, "key" to key, "ok" to success),
    )
}

/**
 * [T-orphan-use-report] Record that one request was served from a REPAIRED
 * history.
 *
 * WHY the journal and not just the existing warning: the warning line scrolls
 * out of logcat, and the question this answers ("did this session ever send a
 * history whose tool pairing had to be fixed?") is asked days later. One line
 * per repaired request, keyed by counts, is what makes that answerable.
 *
 * Best-effort by contract: never throws, never blocks the turn.
 */
internal fun ChatViewModel.recordPairingRepairJournal(droppedResults: Int, interruptedUses: Int) {
    runCatching {
        SessionJournal.record(
            filesDir = context.filesDir,
            sessionId = realSessionId.ifEmpty { sessionId },
            kind = SessionJournal.PAIRING_REPAIRED,
            fields = mapOf(
                "droppedResults" to droppedResults,
                "interruptedUses" to interruptedUses,
            ),
        )
    }
}

internal fun ChatViewModel.recordTurnJournal(turn: Int, starting: Boolean) {
    runCatching {
        SessionJournal.record(
            filesDir = context.filesDir,
            sessionId = realSessionId.ifEmpty { sessionId },
            kind = if (starting) SessionJournal.TURN_START else SessionJournal.TURN_END,
            fields = mapOf("turn" to turn, "history" to agentHistory.size),
        )
    }
}
