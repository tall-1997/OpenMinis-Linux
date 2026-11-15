package com.openminis.app.data

import kotlin.math.ceil
import kotlin.math.min

/**
 * Hybrid context-token estimation. Port of pi `ai/src/utils/estimate.ts`
 * (packages/ai, MIT, getLastAssistantUsageInfo + estimateContextTokens):
 *
 * The most recent successful assistant response carries the provider's own
 * usage — that number IS the prefix (everything up to and including that
 * response). Only the messages after it need estimating, at
 * [CHARS_PER_TOKEN] characters per token with images charged a flat
 * [ESTIMATED_IMAGE_CHARS]. When no usable anchor exists, the whole history is
 * estimated from characters instead.
 *
 * ## Why the anchor has a timestamp rule
 *
 * A compaction summary inserted into the history invalidates the old prefix:
 * the usage described a prefix that no longer exists. pi detects this by
 * ordering — a message whose timestamp is NEWER than the anchor response but
 * positioned BEFORE it (a summary created later, spliced in earlier) proves
 * the prefix changed, so the anchor must be ignored and everything estimated
 * from characters. The check runs against the newest timestamp preceding the
 * anchor position, which subsumes "the first inserted message" and any older
 * ones.
 *
 * ## Contract
 *
 *  - [estimate] takes the FULL effective history (post-transform: compaction
 *    summary inlined, harness notes folded, ...) including assistant turns.
 *  - [TokenUsageAnchor] must come from the assistant turn it claims to
 *    describe: same turn, same timestamp. The anchor is located in the list
 *    by (role == assistant, timestampMs == anchor.timestampMs); an anchor
 *    whose turn is not in the list (compacted away, stale cache) is ignored
 *    and the whole history is estimated.
 *  - A zero/negative anchor total is ignored (pi skips usage blocks with a
 *    zero context total).
 *  - The result is capped at [contextWindow] when the window is known
 *    (contextWindow > 0); 0 = unknown window = uncapped, matching this repo's
 *    "0 = unknown" convention.
 *
 * This is the cheap path under the real BPE tokenizer (BPETokenizer) — a
 * full-history BPE scan is exact but linear in every character on every call;
 * the hybrid only scans the trailing segment. Pure JVM logic, safe for unit
 * tests.
 */
object ContextTokenEstimator {

    /** pi estimate.ts: average characters per token for mixed prose/code. */
    const val CHARS_PER_TOKEN = 3.5

    /** pi estimate.ts: flat character charge per image content block. */
    const val ESTIMATED_IMAGE_CHARS = 4800

    /** role value for assistant turns (anchor attachment point). */
    const val ROLE_ASSISTANT = "assistant"

    /**
     * The provider-reported usage of one assistant response, used as the
     * prefix truth for the hybrid estimate. [totalTokens] is the FULL context
     * size reported for that turn (pi usage.totalTokens; this app's
     * LLMUsage.latestContextTokens or the input+output+cache sum, caller's
     * choice). [timestampMs] must be the assistant message's own timestamp.
     */
    data class TokenUsageAnchor(
        val totalTokens: Long,
        val timestampMs: Long,
    )

    /**
     * The minimal message shape the estimator needs, so callers adapt their
     * own history types instead of materializing copies: [textLength] is the
     * total character count of the message's text content (system prompt
     * text, user/assistant text, tool-result text, tool-call JSON — whatever
     * the caller wants charged as text), [imageCount] the number of image
     * blocks charged [ESTIMATED_IMAGE_CHARS] each.
     */
    interface EstimableMessage {
        val role: String
        val textLength: Int
        val imageCount: Int
        val timestampMs: Long
    }

    /** Ready-made [EstimableMessage] for tests and simple callers. */
    data class SimpleEstimableMessage(
        override val role: String,
        override val textLength: Int,
        override val imageCount: Int = 0,
        override val timestampMs: Long = 0L,
    ) : EstimableMessage

    /**
     * Hybrid estimate of the current context size in tokens:
     * anchor total (prefix) + character estimate of everything after the
     * anchor turn, or a whole-history character estimate when no anchor
     * applies. Capped at [contextWindow] when known (0 = uncapped).
     */
    fun estimate(
        messages: List<EstimableMessage>,
        lastAssistantUsage: TokenUsageAnchor?,
        contextWindow: Int,
    ): Long {
        if (messages.isEmpty()) return 0L
        val anchorIndex = anchorPrefixIndex(messages, lastAssistantUsage)
        val total: Long = if (anchorIndex >= 0 && lastAssistantUsage != null) {
            lastAssistantUsage.totalTokens + trailingEstimate(messages, anchorIndex + 1)
        } else {
            fullEstimate(messages)
        }
        return capped(total, contextWindow)
    }

    /**
     * Token estimate for one message: ceil((text chars + images x image
     * chars) / [CHARS_PER_TOKEN]). All roles share the same arithmetic — role
     * only matters for locating the anchor.
     */
    fun estimateMessageTokens(message: EstimableMessage): Long =
        ceil((message.textLength.toLong() + message.imageCount.toLong() * ESTIMATED_IMAGE_CHARS) / CHARS_PER_TOKEN)
            .toLong()

    /**
     * Token estimate for a plain character count: ceil(chars / [CHARS_PER_TOKEN]).
     */
    fun estimateTextTokens(textChars: Long): Long = ceil(textChars / CHARS_PER_TOKEN).toLong()

    /**
     * Locate the anchor's assistant turn and validate the prefix, returning
     * the index the anchor may attach to, or -1 when the whole history must
     * be estimated:
     *
     *  - no anchor, or a zero/negative usage total (pi skips empty usage),
     *  - no assistant turn at the anchor's timestamp (turn compacted away, or
     *    a stale anchor from a turn that is no longer in the list),
     *  - a NEWER message positioned before the anchor turn (pi's
     *    `usageAppliesToPrefix` check — a compaction summary created after the
     *    response but spliced before it proves the prefix changed).
     *
     * pi requires `assistant.timestamp >= max(timestamps of earlier messages)`;
     * equal timestamps stay valid (same-millisecond ordering is normal).
     */
    private fun anchorPrefixIndex(messages: List<EstimableMessage>, anchor: TokenUsageAnchor?): Int {
        if (anchor == null || anchor.totalTokens <= 0) return -1
        var index = -1
        for (i in messages.indices.reversed()) {
            val message = messages[i]
            if (message.role == ROLE_ASSISTANT && message.timestampMs == anchor.timestampMs) {
                index = i
                break
            }
        }
        if (index < 0) return -1
        var latestBefore = Long.MIN_VALUE
        for (i in 0 until index) {
            latestBefore = maxOf(latestBefore, messages[i].timestampMs)
        }
        if (anchor.timestampMs < latestBefore) return -1
        return index
    }

    private fun fullEstimate(messages: List<EstimableMessage>): Long =
        messages.fold(0L) { acc, message -> acc + estimateMessageTokens(message) }

    private fun trailingEstimate(messages: List<EstimableMessage>, fromIndex: Int): Long {
        var acc = 0L
        for (i in fromIndex until messages.size) acc += estimateMessageTokens(messages[i])
        return acc
    }

    private fun capped(total: Long, contextWindow: Int): Long =
        if (contextWindow > 0) min(total, contextWindow.toLong()) else total
}
