package com.openminis.app.data

import com.openminis.app.data.ContextTokenEstimator.SimpleEstimableMessage
import com.openminis.app.data.ContextTokenEstimator.TokenUsageAnchor
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [ContextTokenEstimator] (pi ai/src/utils/estimate.ts:71-112):
 * anchor-valid vs anchor-stale paths, whole-history fallback, image weighting,
 * and the context-window cap.
 */
class ContextTokenEstimatorTest {

    private fun msg(
        role: String,
        textLength: Int,
        imageCount: Int = 0,
        timestampMs: Long,
    ) = SimpleEstimableMessage(
        role = role,
        textLength = textLength,
        imageCount = imageCount,
        timestampMs = timestampMs,
    )

    // ── arithmetic primitives ───────────────────────────────────────────────

    @Test
    fun `text estimates at three and a half characters per token, rounded up`() {
        assertEquals(0, ContextTokenEstimator.estimateTextTokens(0))
        assertEquals(1, ContextTokenEstimator.estimateTextTokens(1))
        assertEquals(2, ContextTokenEstimator.estimateTextTokens(7))
        assertEquals(1000, ContextTokenEstimator.estimateTextTokens(3500))
        assertEquals(1143, ContextTokenEstimator.estimateTextTokens(4000))
    }

    @Test
    fun `images are charged a flat character weight`() {
        // 4800 / 3.5 = 1371.43 → 1372.
        assertEquals(1372, ContextTokenEstimator.estimateMessageTokens(msg("user", 0, 1, timestampMs = 100)))
        // 9600 / 3.5 = 2742.86 → 2743.
        assertEquals(2743, ContextTokenEstimator.estimateMessageTokens(msg("user", 0, 2, timestampMs = 100)))
        // Text and images sum before the division: (35 + 4800) / 3.5 = 1381.43 → 1382.
        assertEquals(1382, ContextTokenEstimator.estimateMessageTokens(msg("user", 35, 1, timestampMs = 100)))
    }

    // ── anchor-valid path ───────────────────────────────────────────────────

    @Test
    fun `valid anchor is the prefix truth and only trailing messages are estimated`() {
        val messages = listOf(
            msg("user", 3500, timestampMs = 100),
            msg("assistant", 14, timestampMs = 200),
            msg("user", 3500, timestampMs = 300),
        )
        val anchor = TokenUsageAnchor(totalTokens = 2000, timestampMs = 200)
        // 2000 (usage) + 1000 (trailing user) — the anchor turn's own text is
        // not re-estimated, pi estimate.ts parity.
        assertEquals(3000, ContextTokenEstimator.estimate(messages, anchor, contextWindow = 0))
    }

    @Test
    fun `trailing estimate includes assistant turns after the anchor`() {
        val messages = listOf(
            msg("user", 35, timestampMs = 100),
            msg("assistant", 14, timestampMs = 200),
            // An errored assistant turn after the anchored one: still trailing content.
            msg("assistant", 100, timestampMs = 250),
            msg("user", 70, timestampMs = 300),
        )
        val anchor = TokenUsageAnchor(totalTokens = 2000, timestampMs = 200)
        // 2000 + 29 (100 chars) + 20 (70 chars) = 2049.
        assertEquals(2049, ContextTokenEstimator.estimate(messages, anchor, contextWindow = 0))
    }

    @Test
    fun `anchor applies again after a response to the inserted context`() {
        // pi context-estimate.test.ts case 3: a stale-looking summary turn is
        // followed by a FRESH assistant response, whose usage becomes the anchor.
        val messages = listOf(
            msg("user", 35, timestampMs = 200),
            msg("assistant", 14, timestampMs = 100),
            msg("user", 35, timestampMs = 300),
            msg("assistant", 14, timestampMs = 400),
            msg("user", 35, timestampMs = 500),
        )
        val anchor = TokenUsageAnchor(totalTokens = 2000, timestampMs = 400)
        // The anchor turn's ts (400) is not older than any earlier message's ts → valid.
        assertEquals(2010, ContextTokenEstimator.estimate(messages, anchor, contextWindow = 0))
    }

    @Test
    fun `anchor attaches to the last assistant turn sharing its timestamp`() {
        val messages = listOf(
            msg("user", 3500, timestampMs = 100),
            msg("assistant", 14, timestampMs = 200),
            msg("assistant", 14, timestampMs = 200),
            msg("user", 3500, timestampMs = 300),
        )
        val anchor = TokenUsageAnchor(totalTokens = 2000, timestampMs = 200)
        // Trailing starts after the SECOND same-ts assistant: 2000 + 1000.
        assertEquals(3000, ContextTokenEstimator.estimate(messages, anchor, contextWindow = 0))
    }

    // ── anchor-invalid path: whole-history estimate ─────────────────────────

    @Test
    fun `stale anchor is ignored when a newer message was spliced before its turn`() {
        // pi context-estimate.test.ts case 2 (compaction regression pi #10497
        // family): the summary carries a NEWER timestamp than the old response
        // it precedes, so the old usage cannot describe the current prefix.
        val messages = listOf(
            msg("user", 35, timestampMs = 200),
            msg("assistant", 14, timestampMs = 100),
            msg("user", 4000, timestampMs = 300),
        )
        val staleAnchor = TokenUsageAnchor(totalTokens = 9500, timestampMs = 100)
        // Whole-history estimate: 10 + 4 + 1143 = 1157, NOT 9500 + 1143.
        assertEquals(1157, ContextTokenEstimator.estimate(messages, staleAnchor, contextWindow = 0))
    }

    @Test
    fun `anchor whose turn is not in the list is ignored`() {
        val messages = listOf(
            msg("user", 3500, timestampMs = 100),
            msg("assistant", 14, timestampMs = 200),
        )
        // No assistant turn at ts=999 (compacted away / stale cached anchor).
        val missing = TokenUsageAnchor(totalTokens = 9000, timestampMs = 999)
        // 1000 + 4 = 1004.
        assertEquals(1004, ContextTokenEstimator.estimate(messages, missing, contextWindow = 0))
    }

    @Test
    fun `anchor matching a user turn instead of an assistant is ignored`() {
        val messages = listOf(
            msg("user", 3500, timestampMs = 100),
            msg("assistant", 14, timestampMs = 200),
        )
        val mismatchedRole = TokenUsageAnchor(totalTokens = 9000, timestampMs = 100)
        assertEquals(1004, ContextTokenEstimator.estimate(messages, mismatchedRole, contextWindow = 0))
    }

    @Test
    fun `zero-usage anchor is ignored`() {
        val messages = listOf(
            msg("user", 3500, timestampMs = 100),
            msg("assistant", 14, timestampMs = 200),
            msg("user", 3500, timestampMs = 300),
        )
        val zero = TokenUsageAnchor(totalTokens = 0, timestampMs = 200)
        assertEquals(2004, ContextTokenEstimator.estimate(messages, zero, contextWindow = 0))
    }

    @Test
    fun `no anchor estimates the whole history`() {
        val messages = listOf(
            msg("system", 35, timestampMs = 50),
            msg("user", 3500, timestampMs = 100),
            msg("assistant", 14, timestampMs = 200),
        )
        assertEquals(1014, ContextTokenEstimator.estimate(messages, null, contextWindow = 0))
    }

    @Test
    fun `empty messages estimate to zero with or without an anchor`() {
        assertEquals(0, ContextTokenEstimator.estimate(emptyList(), null, contextWindow = 10_000))
        assertEquals(
            0,
            ContextTokenEstimator.estimate(emptyList(), TokenUsageAnchor(5000, 100), contextWindow = 10_000),
        )
    }

    // ── context-window cap ──────────────────────────────────────────────────

    @Test
    fun `estimate is capped at the context window`() {
        val messages = listOf(
            msg("assistant", 14, timestampMs = 100),
            msg("user", 3500, timestampMs = 200),
        )
        val anchor = TokenUsageAnchor(totalTokens = 9000, timestampMs = 100)
        // 9000 + 1000 = 10000 → exactly the window.
        assertEquals(10_000, ContextTokenEstimator.estimate(messages, anchor, contextWindow = 10_000))
        // Over the window → clamped to the window.
        assertEquals(9_500, ContextTokenEstimator.estimate(messages, anchor, contextWindow = 9_500))
        // Unknown window (0) → uncapped.
        assertEquals(10_000, ContextTokenEstimator.estimate(messages, anchor, contextWindow = 0))
    }

    @Test
    fun `estimate below the window passes through unchanged`() {
        val messages = listOf(
            msg("assistant", 14, timestampMs = 100),
            msg("user", 700, timestampMs = 200),
        )
        val anchor = TokenUsageAnchor(totalTokens = 9000, timestampMs = 100)
        // 9000 + 200 = 9200 < 10000.
        assertEquals(9200, ContextTokenEstimator.estimate(messages, anchor, contextWindow = 10_000))
    }
}
