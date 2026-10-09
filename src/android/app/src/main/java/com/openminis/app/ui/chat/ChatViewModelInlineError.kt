package com.openminis.app.ui.chat

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.openminis.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Set error inline on the last assistant message (iOS: message.error).
 *
 *  Also clears [ChatMessage.isAwaitingModelResponse] — without this, an
 *  exception thrown after a tool turn (which sets isAwaitingModelResponse=
 *  true at runAgentLoop ~4015) leaves the "Minis is thinking" indicator
 *  on screen even though streaming is over. The flag is per-message and
 *  is not implicitly cleared by isStreaming=false. */
internal fun ChatViewModel.setInlineError(errorText: String) {
    // [T-error-persist-android] Never let an empty/blank error string reach
    // the banner. The UI gate is `message.error?.let { … }` — a non-null ""
    // would render an EMPTY error banner, and (now that errors persist) it
    // would stick across reloads. An exception with a blank `message`
    // (`e.message ?: "Unknown error"` only guards null, not "") is the
    // realistic source. Coalesce to a generic non-empty message.
    val safeError = errorText.ifBlank { context.getString(R.string.error_empty_response_generic) }
    // T-streaming-side-channel: before mutating the canonical message,
    // drain any in-flight streaming delta so the error frame carries
    // the actual accumulated content (otherwise the user sees content
    // snap back to a pre-stream prefix when the error banner appears).
    flushAllStreamingDeltas()
    val msgs = _messages.value.toMutableList()
    val lastAssistantIdx = msgs.indexOfLast { it.role == "assistant" }
    if (lastAssistantIdx >= 0) {
        val msg = msgs[lastAssistantIdx]
        msgs[lastAssistantIdx] = msg.copy(
            error = safeError,
            isStreaming = false,
            isAwaitingModelResponse = false,
        )
        _messages.value = msgs
        // [T-error-persist-android] Persist the terminal error onto the
        // session's last assistant DB row so the inline error + Retry button
        // survive a session reload. This is a targeted UPDATE (not a fresh
        // insert): the in-memory bubble id differs from the persisted row id,
        // so we address the row by "last assistant" — matching the load-side
        // merge that keeps the last assistant row's identity. No-op when the
        // failing turn never persisted a row (first-turn failure).
        val sid = realSessionId.ifEmpty { sessionId }
        if (sid.isNotEmpty()) {
            viewModelScope.launch(Dispatchers.IO) {
                try { chatRepository.updateLastAssistantError(sid, safeError) }
                catch (e: Exception) { Log.w(ChatViewModel.TAG, "persist error_info failed: ${e.message}") }
            }
        }
    } else {
        // No assistant message yet — fall back to top-level error
        _error.value = safeError
    }
}

/**
 * [T-retry-marker-stale] 新发送前的兜底：上一回合若死在自动重试倒计时里，
 * 旧气泡上可能还盖着「— retrying (n/m)…」的瞬时错误横幅（倒计时取消路径
 * 已清，这里防的是任何漏网路径）。新回合开跑 = 旧标记作废。只清瞬时重试
 * 文案——真正的终局错误横幅是历史事实，保留。
 */
internal fun ChatViewModel.clearStaleTransientRetryMarker() {
    val lastAssistant = _messages.value.lastOrNull { it.role == "assistant" } ?: return
    if (lastAssistant.error?.contains("— retrying (") == true) {
        clearInlineError()
    }
}
