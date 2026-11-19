package com.openminis.app.ui.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [T-drain-prompt-refresh] Rebuild the run-scoped system prompt when a NEW
 * user turn starts inside a running agent loop (queued prompt drained at a
 * tool boundary).
 *
 * The prompt is otherwise built once per send. A queued prompt is a new user
 * turn: the task anchor, the daily-memory gate and the recall query are all
 * keyed to "the latest user message", so without this rebuild the loop keeps
 * carrying the previous turn's prompt — the correction the user just queued
 * is in history but nothing in the system prompt reflects it.
 *
 * [T-drain-prompt-session] The builder reads `activeSessionId` / `_messages`.
 * If the user switched sessions while the loop ran, rebuilding would inject
 * session B's persona/rules/anchor into session A's request. Skip the rebuild
 * when the expected session is gone; the old prompt is the safer fallback.
 * Cancellation is rethrown (never swallowed) so a session switch can stop the
 * caller.
 */
internal suspend fun ChatViewModel.refreshRunPromptForInjectedTurn(
    current: String?,
    expectedSessionId: String?,
): String? {
    if (expectedSessionId != null && activeSessionId != expectedSessionId) return current
    return try {
        withContext(Dispatchers.IO) { buildSystemPrompt() }
    } catch (ce: CancellationException) {
        throw ce
    } catch (_: Exception) {
        null
    } ?: current
}
