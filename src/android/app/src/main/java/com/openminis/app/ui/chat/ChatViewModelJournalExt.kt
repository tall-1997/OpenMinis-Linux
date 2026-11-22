package com.openminis.app.ui.chat

import com.openminis.app.util.Digest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Records the final host projection, not a provider response or a second source of history. */
internal class ChatRunJournal(private val filesDir: File, val sessionId: String) {
    private val runId = UUID.randomUUID().toString()
    private var openTurn: Int? = null

    suspend fun recordRequestJournal(turn: Int, projection: ProjectedChatRequest) = withContext(Dispatchers.IO) {
        if (openTurn != turn) {
            openTurn?.let { record(SessionJournal.TURN_END, it) }
            openTurn = turn
            record(SessionJournal.TURN_START, turn)
            record(SessionJournal.STEP_START, turn)
        }
        projection.pairingRepair?.let {
            record(SessionJournal.PAIRING_REPAIRED, turn, mapOf(
                "droppedResults" to it.droppedResults, "interruptedUses" to it.interruptedUses,
                "droppedNames" to it.droppedNames.joinToString(","),
            ))
        }
        SessionJournal.recordRequest(
            filesDir, sessionId, turn, projection.outboundRequest.messages,
            estimatedTokens = projection.estimate.tokens,
            providerTokens = 0, // No response for this attempt yet.
            summaryChars = projection.summaryChars,
            markerId = projection.markerId,
            droppedRounds = 0, // The send path does not omit uncovered rounds.
            runId = runId,
        )
    }

    suspend fun close() {
        val turn = openTurn ?: return
        withContext(NonCancellable + Dispatchers.IO) {
            record(SessionJournal.TURN_END, turn)
            openTurn = null
        }
    }

    private fun record(kind: String, turn: Int, fields: Map<String, Any?> = emptyMap()) {
        SessionJournal.record(filesDir, sessionId, kind, fields + mapOf("turn" to turn, "runId" to runId))
    }
}

internal suspend fun ChatViewModel.recordToolUseJournal(turn: Int, tool: String, argsJson: String, sessionId: String) =
    recordToolJournal(turn, tool, argsJson, sessionId, null)

internal suspend fun ChatViewModel.recordToolResultJournal(turn: Int, tool: String, argsJson: String, success: Boolean, sessionId: String) =
    recordToolJournal(turn, tool, argsJson, sessionId, success)

private suspend fun ChatViewModel.recordToolJournal(
    turn: Int, tool: String, argsJson: String, sessionId: String, success: Boolean?,
) = withContext(Dispatchers.IO) {
    SessionJournal.record(
        context.filesDir, sessionId, if (success == null) SessionJournal.TOOL_CALL else SessionJournal.TOOL_RESULT,
        toolJournalFields(sessionId, turn, tool, argsJson, success),
    )
}

/** Correlate the bracket without putting command text or credentials in the journal. */
internal fun toolJournalFields(sessionId: String, turn: Int, tool: String, argsJson: String, success: Boolean?): Map<String, Any?> =
    mapOf(
        "turn" to turn, "tool" to tool.take(120),
        "key" to Digest.sha256Hex("${sessionId.length}:$sessionId:$turn:${tool.length}:$tool:$argsJson"),
        "ok" to success,
    )
