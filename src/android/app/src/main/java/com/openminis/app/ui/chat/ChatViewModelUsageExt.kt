package com.openminis.app.ui.chat

import com.openminis.app.service.outputTokensPerSecond
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
 * Speed (tokens/s) is derived from `streamDurationMs` — the streamed-text
 * wall clock persisted alongside the counters since [T-android-usage-tok-s]
 * (first→last text delta of the persisted window; tool-call gaps inside the
 * window make the figure conservative). Absent on rows written before the
 * field existed, or when the window was too short to be meaningful.
 */
internal data class MessageUsageStats(
    val inputTokens: Int,
    val outputTokens: Int,
    val cacheReadTokens: Int,
    val cacheCreationTokens: Int,
    val streamDurationMs: Long? = null,
    /**
     * [T-message-usage-footer-time] Wall-clock time of the message this stats
     * belongs to (ms epoch), injected by [usageByUiMessageId] from
     * [ChatMessage.createdAt] — NOT part of the persisted usage JSON
     * ([fromJson] always yields null). Null when no DB row backs the bubble;
     * the three-tier time tier in the usage footer formats it.
     */
    val createdAtMs: Long? = null,
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

    /** Output speed for the footer; null when the window is missing/too short. */
    val outputTokensPerSecond: Int?
        get() = streamDurationMs?.let { outputTokensPerSecond(outputTokens, it) }

    operator fun plus(other: MessageUsageStats): MessageUsageStats = MessageUsageStats(
        inputTokens = inputTokens + other.inputTokens,
        outputTokens = outputTokens + other.outputTokens,
        cacheReadTokens = cacheReadTokens + other.cacheReadTokens,
        cacheCreationTokens = cacheCreationTokens + other.cacheCreationTokens,
        // Sum only when every merged row carried a window — one missing window
        // would otherwise divide summed tokens by a partial duration.
        streamDurationMs = if (streamDurationMs != null && other.streamDurationMs != null) {
            streamDurationMs + other.streamDurationMs
        } else null,
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
                    streamDurationMs = obj.optLong("streamDurationMs", 0L).takeIf { it > 0L },
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

// [P1-18] mergeMessageUsageRows (List<String?>) removed: zero production
// callers — the footer path pre-parses rows (usageByUiMessageId) and merges
// via mergeMessageUsageStats; the raw-row variant existed only for its test.

/**
 * Sum pre-parsed rows (see [usageByUiMessageId]); null entries skipped.
 *
 * [T-usage-tok-s-merge] A merged bubble's rows come from different persist
 * paths — the turn-persist path stamps a stream window, the others don't.
 * The binary [MessageUsageStats.plus] keeps its all-or-nothing window gate,
 * but THIS merge — the one the footer actually uses — aggregates the windows
 * that ARE present, so one un-windowed row no longer drops tok/s for the
 * whole bubble. The speed then reads slightly fast (un-windowed rows still
 * contribute tokens to the numerator), which beats a footer that loses its
 * speed segment on every long multi-tool-turn reply. Pure.
 */
internal fun mergeMessageUsageStats(stats: List<MessageUsageStats?>): MessageUsageStats? {
    var merged: MessageUsageStats? = null
    var windowedMs = 0L
    for (parsed in stats) {
        if (parsed == null) continue
        merged = merged?.plus(parsed) ?: parsed
        parsed.streamDurationMs?.let { windowedMs += it }
    }
    return merged?.copy(streamDurationMs = windowedMs.takeIf { it > 0L })
}

/**
 * Token-count formatting for the footer, mirroring the Token Usage sheet's
 * iOS convention ("%.1fK" / "%.1fM") but lowercase ("1.2k") to match the
 * compact footer style. Pure — JVM-tested.
 *
 * [T-android-format-converge] Body moved to [formatTokenCount]; this keeps
 * the footer profile — lowercase, zero-decimals above 10k, normal k/M
 * magnitude — pinned by MessageUsageFooterTest.
 */
internal fun formatUsageTokenCount(n: Int): String =
    formatTokenCount(n.toLong(), thinTenK = true)

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
        // [T-message-usage-footer-time] Stamp the bubble's wall-clock time for
        // the footer's three-tier time tier — merged stats carry no time of
        // their own (the persisted usage JSON has no timestamp).
        if (merged.hasData) out[message.id] = merged.copy(createdAtMs = message.createdAt)
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
