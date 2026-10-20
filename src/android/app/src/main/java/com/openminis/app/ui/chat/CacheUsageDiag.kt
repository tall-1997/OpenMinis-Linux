package com.openminis.app.ui.chat

import com.openminis.app.data.model.LLMUsage
import com.openminis.app.logging.AppLogger

/**
 * [T-prompt-cache-diag] Per-request cache numbers beside the fingerprint line.
 *
 * The usage screen aggregates a window, which hides the shape that matters:
 * the first request of a chat always writes the cache (hit = 0), later requests
 * read it. Logging the breakdown per request makes that visible in logcat and
 * gives support a way to tell a genuine prefix break from "I measured the first
 * turn".
 */
internal fun logCacheUsage(usage: LLMUsage, tag: String = ChatViewModel.TAG_STREAM) {
    val read = usage.cacheReadInputTokens ?: 0
    val write = usage.cacheCreationInputTokens ?: 0
    val total = usage.inputTokens + read + write
    if (total <= 0) return
    val hit = read.toDouble() / total * 100
    AppLogger.info(
        tag,
        "[CacheDiag] usage input=${usage.inputTokens} cacheRead=$read cacheWrite=$write " +
            "total=$total hit=${"%.1f".format(hit)}%",
    )
}
