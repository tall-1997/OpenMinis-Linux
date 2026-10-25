package com.openminis.app.data.model

/**
 * Aggregated token accounting for the current session.
 *
 * Mirrors XINCODE `TokenStats.kt` — used to drive the context-usage ring
 * on the session-menu Token Usage item and the token-stats sheet.
 */
data class TokenStats(
    val prompt: Long = 0L,
    val cacheHit: Long = 0L,
    val cacheMiss: Long = 0L,
    val completion: Long = 0L,
) {
    val total: Long get() = prompt + completion

    /** 0.0..1.0 — fraction of prompt tokens served from the KV cache. */
    val cacheHitRatio: Float
        get() {
            val denom = cacheHit + cacheMiss
            return if (denom <= 0) 0f else cacheHit.toFloat() / denom.toFloat()
        }

    val hasData: Boolean get() = total > 0

    companion object {
        val EMPTY = TokenStats(0L, 0L, 0L, 0L)
    }
}

/**
 * Current context-window usage, for the session-menu ring.
 *
 * @param usedTokens  tokens occupied by the current conversation (persisted
 *                    usage sum + live estimate for the in-flight request).
 * @param windowTokens the model's effective context window. 0 ⇒ unknown,
 *                    in which case the ring renders as "unknown / ?".
 */
data class ContextUsage(
    val usedTokens: Long = 0L,
    val windowTokens: Long = 0L,
) {
    /** 0.0..1.0; returns 0 when the window is unknown. */
    val ratio: Float
        get() = if (windowTokens <= 0) 0f else
            (usedTokens.toFloat() / windowTokens.toFloat()).coerceIn(0f, 1f)

    val known: Boolean get() = windowTokens > 0

    companion object {
        val EMPTY = ContextUsage(0L, 0L)
    }
}
