package com.openminis.app.ui.chat

import org.json.JSONObject

/**
 * [T-android-message-usage-footer] One assistant turn's parsed `token_usage`
 * JSON — the same shape [ChatViewModel.persistAssistantTurn] writes and
 * [ChatViewModel.loadSessionTokenStats] reads, kept as a lightweight value so
 * the chat list can render a usage footer under each reply without pulling
 * the whole [com.openminis.app.data.db.MessageUsageRow] projection into the
 * UI layer (ui → data.db imports are forbidden by the architecture gate; the
 * repository returns a plain id→JSON map instead).
 *
 * Field semantics follow the write side exactly:
 *  - `inputTokens` is FRESH input only. Both provider parsers subtract the
 *    cached portion before storing (OpenAIProviderUsage.kt: subtracting keeps
 *    `input + cacheRead` from double-counting the cached tokens).
 *  - `cacheReadTokens` / `cacheCreationTokens` are the KV-cache read/write
 *    counts (Anthropic reports both; OpenAI/DeepSeek only ever report reads).
 *  - So the prompt total for the turn is input + read + write, and the cache
 *    hit ratio is read / that total — the same denominator the Usage page's
 *    [T-cache-hit-rate] row uses.
 *
 * Speed (tokens/s) is deliberately NOT modelled: no stream duration is
 * recorded anywhere in the persistence path (blocks carry per-tool
 * durations only), so the ratio cannot be derived from existing fields.
 */
internal data class MessageUsageStats(
    val inputTokens: Int,
    val outputTokens: Int,
    val cacheReadTokens: Int,
    val cacheCreationTokens: Int,
) {
    val promptTotalTokens: Int get() = inputTokens + cacheReadTokens + cacheCreationTokens

    /** True when any cache counter is populated — gates the cache segment. */
    val hasCacheData: Boolean get() = cacheReadTokens > 0 || cacheCreationTokens > 0

    /** True when the row carries anything worth rendering. */
    val hasData: Boolean get() = inputTokens > 0 || outputTokens > 0 || hasCacheData

    /** 0.0..1.0 — cached fraction of the prompt; 0 when there is no data. */
    val cacheHitRatio: Float
        get() = if (promptTotalTokens <= 0) 0f
        else cacheReadTokens.toFloat() / promptTotalTokens.toFloat()

    operator fun plus(other: MessageUsageStats): MessageUsageStats = MessageUsageStats(
        inputTokens = inputTokens + other.inputTokens,
        outputTokens = outputTokens + other.outputTokens,
        cacheReadTokens = cacheReadTokens + other.cacheReadTokens,
        cacheCreationTokens = cacheCreationTokens + other.cacheCreationTokens,
    )

    companion object {
        val ZERO = MessageUsageStats(0, 0, 0, 0)

        /**
         * Parse one stored `token_usage` JSON. Null when absent or malformed —
         * rows written before the column existed, or hand-edited databases.
         */
        fun fromJson(json: String?): MessageUsageStats? {
            if (json.isNullOrBlank()) return null
            return try {
                val obj = JSONObject(json)
                MessageUsageStats(
                    inputTokens = obj.optInt("inputTokens", 0),
                    outputTokens = obj.optInt("outputTokens", 0),
                    cacheReadTokens = obj.optInt("cacheReadTokens", 0),
                    cacheCreationTokens = obj.optInt("cacheCreationTokens", 0),
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

/** Sum every parseable row; null when none parse. Pure — JVM-tested. */
internal fun mergeMessageUsageRows(rows: List<String?>): MessageUsageStats? {
    var merged: MessageUsageStats? = null
    for (json in rows) {
        val parsed = MessageUsageStats.fromJson(json) ?: continue
        merged = merged?.plus(parsed) ?: parsed
    }
    return merged
}

/** Sum pre-parsed rows (see [usageByUiMessageId]); null entries skipped. Pure. */
internal fun mergeMessageUsageStats(stats: List<MessageUsageStats?>): MessageUsageStats? {
    var merged: MessageUsageStats? = null
    for (parsed in stats) {
        if (parsed == null) continue
        merged = merged?.plus(parsed) ?: parsed
    }
    return merged
}

/**
 * Token-count formatting for the footer, mirroring the Token Usage sheet's
 * iOS convention ("%.1fK" / "%.1fM") but lowercase ("1.2k") to match the
 * compact footer style. Pure — JVM-tested.
 */
internal fun formatUsageTokenCount(n: Int): String = when {
    n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
    n >= 10_000 -> String.format("%.0fk", n / 1_000.0)
    n >= 1_000 -> String.format("%.1fk", n / 1_000.0)
    else -> n.toString()
}

/**
 * Fetch this session's persisted per-turn usage, keyed by DB row id. The UI
 * layer joins it against [ChatMessage.sourceDbIds] — merged assistant bubbles
 * carry every source row's id, so a merged bubble sums its rows via
 * [mergeMessageUsage]. Empty on a draft session or any repository failure:
 * a usage readout must never break the chat.
 */
internal suspend fun ChatViewModel.loadMessageUsageByDbId(): Map<String, MessageUsageStats> {
    val sid = realSessionId.ifEmpty { sessionId }
    if (sid.isEmpty()) return emptyMap()
    return try {
        chatRepository.sessionMessageUsageJsons(sid)
            .mapValues { (_, json) -> MessageUsageStats.fromJson(json) ?: MessageUsageStats.ZERO }
    } catch (_: Exception) {
        emptyMap()
    }
}

/**
 * Join the DB-keyed usage map onto UI message ids (one assistant bubble may
 * merge several DB rows) and drop every row that is still streaming — its
 * usage is not final until the turn persists. Pure — JVM-tested.
 */
internal fun usageByUiMessageId(
    messages: List<ChatMessage>,
    usageByDbId: Map<String, MessageUsageStats>,
): Map<String, MessageUsageStats> {
    if (usageByDbId.isEmpty()) return emptyMap()
    val out = HashMap<String, MessageUsageStats>()
    for (message in messages) {
        if (message.role != "assistant" || message.isStreaming) continue
        if (message.sourceDbIds.isEmpty()) continue
        val merged = mergeMessageUsageStats(message.sourceDbIds.map { usageByDbId[it] }) ?: continue
        if (merged.hasData) out[message.id] = merged
    }
    return out
}

/**
 * Flat row key → usage stats for the LAST flat row of every assistant
 * message that carries usage. One O(n) pass per flatItems publication — the
 * per-row render then only does a map lookup. Pure — JVM-tested.
 */
internal fun computeUsageFooterRows(
    flatItems: List<FlatChatItem>,
    usageByMessageId: Map<String, MessageUsageStats>,
): Map<String, MessageUsageStats> {
    if (usageByMessageId.isEmpty() || flatItems.isEmpty()) return emptyMap()
    val lastKeyByMessage = HashMap<String, String>()
    for (item in flatItems) {
        val messageId = FlatKeys.parse(item.key)?.messageId ?: continue
        lastKeyByMessage[messageId] = item.key
    }
    val out = HashMap<String, MessageUsageStats>()
    for ((messageId, stats) in usageByMessageId) {
        lastKeyByMessage[messageId]?.let { out[it] = stats }
    }
    return out
}
