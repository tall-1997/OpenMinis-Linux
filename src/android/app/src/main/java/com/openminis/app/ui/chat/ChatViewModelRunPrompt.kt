package com.openminis.app.ui.chat

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
 * Falls back to [current] when the build fails; a stale prompt must never
 * abort the run.
 */
internal suspend fun ChatViewModel.refreshRunPromptForInjectedTurn(current: String?): String? =
    withContext(Dispatchers.IO) {
        runCatching { buildSystemPrompt() }.getOrNull()
    } ?: current
