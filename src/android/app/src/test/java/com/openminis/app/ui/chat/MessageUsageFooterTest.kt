package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-message-usage-footer] Pure plumbing behind the per-reply token
 * usage footer: parsing the stored `token_usage` JSON, merging a merged
 * assistant bubble's rows, formatting the counts, joining DB rows onto UI
 * message ids, and resolving which flat row carries the footer.
 */
class MessageUsageFooterTest {

    // ── Parsing ──────────────────────────────────────────────────────────

    @Test
    fun `parses the persisted json shape`() {
        val stats = MessageUsageStats.fromJson(
            """{"inputTokens":1200,"outputTokens":340,"cacheCreationTokens":0,"cacheReadTokens":8100,"latestContextTokens":24000}""",
        )!!
        assertEquals(1200, stats.inputTokens)
        assertEquals(340, stats.outputTokens)
        assertEquals(8100, stats.cacheReadTokens)
        assertEquals(0, stats.cacheCreationTokens)
    }

    @Test
    fun `null or malformed json yields null`() {
        assertNull(MessageUsageStats.fromJson(null))
        assertNull(MessageUsageStats.fromJson(""))
        assertNull(MessageUsageStats.fromJson("not json"))
    }

    @Test
    fun `prompt total is fresh input plus both cache counters`() {
        // Write-side invariant: inputTokens is FRESH-only (both provider
        // parsers subtract the cached portion), so the prompt total must add
        // the cache read AND write counters back in.
        val stats = MessageUsageStats(500, 100, 2000, 300)
        assertEquals(2800, stats.promptTotalTokens)
    }

    @Test
    fun `cache hit ratio uses read over prompt total`() {
        val stats = MessageUsageStats(500, 100, 2000, 300)
        assertEquals(2000f / 2800f, stats.cacheHitRatio, 1e-6f)
        assertTrue(stats.hasCacheData)
        assertEquals(0f, MessageUsageStats(500, 100, 0, 0).cacheHitRatio, 1e-6f)
        assertFalse(MessageUsageStats(500, 100, 0, 0).hasCacheData)
    }

    // ── Merging ──────────────────────────────────────────────────────────

    @Test
    fun `merges rows and skips unparseable ones`() {
        val merged = mergeMessageUsageRows(
            listOf(
                """{"inputTokens":100,"outputTokens":10,"cacheCreationTokens":0,"cacheReadTokens":900}""",
                "garbage",
                null,
                """{"inputTokens":50,"outputTokens":5,"cacheCreationTokens":20,"cacheReadTokens":0}""",
            ),
        )!!
        assertEquals(150, merged.inputTokens)
        assertEquals(15, merged.outputTokens)
        assertEquals(900, merged.cacheReadTokens)
        assertEquals(20, merged.cacheCreationTokens)
    }

    @Test
    fun `parses stream duration and merges only complete windows`() {
        val parsed = MessageUsageStats.fromJson(
            """{"inputTokens":100,"outputTokens":90,"cacheCreationTokens":0,"cacheReadTokens":0,"streamDurationMs":2000}""",
        )!!
        assertEquals(2000L, parsed.streamDurationMs)
        assertEquals(45, parsed.outputTokensPerSecond)

        // Old rows have no duration -> no tok/s.
        assertNull(MessageUsageStats.fromJson("""{"inputTokens":1,"outputTokens":1}""")!!.streamDurationMs)
        assertNull(MessageUsageStats.fromJson("""{"inputTokens":1,"outputTokens":1,"streamDurationMs":0}""")!!.streamDurationMs)

        // One merged row without a window suppresses the merged speed.
        val merged = parsed.plus(MessageUsageStats(10, 5, 0, 0))
        assertEquals(95, merged.outputTokens)
        assertNull(merged.streamDurationMs)
        assertNull(merged.outputTokensPerSecond)

        // Both rows carrying windows sum them (tok/s recomputes at render).
        val both = parsed.plus(MessageUsageStats(10, 50, 0, 0, streamDurationMs = 500L))
        assertEquals(2500L, both.streamDurationMs)
        assertEquals(56, both.outputTokensPerSecond)
    }

    @Test
    fun `merging nothing yields null`() {
        assertNull(mergeMessageUsageStats(emptyList()))
        assertNull(mergeMessageUsageRows(listOf<String?>(null, "garbage")))
    }

    // ── Formatting ───────────────────────────────────────────────────────

    @Test
    fun `formats like the footer spec`() {
        assertEquals("0", formatUsageTokenCount(0))
        assertEquals("340", formatUsageTokenCount(340))
        assertEquals("1.2k", formatUsageTokenCount(1200))
        assertEquals("12k", formatUsageTokenCount(12_345))
        assertEquals("1.2M", formatUsageTokenCount(1_234_567))
    }

    // ── UI-message join ──────────────────────────────────────────────────

    private fun msg(
        id: String,
        role: String,
        dbIds: List<String> = emptyList(),
        streaming: Boolean = false,
    ) = ChatMessage(
        id = id,
        role = role,
        content = "x",
        isStreaming = streaming,
        sourceDbIds = dbIds,
    )

    @Test
    fun `joins db rows onto assistant ui ids only`() {
        val usage = mapOf(
            "db1" to MessageUsageStats(100, 10, 0, 0),
            "db2" to MessageUsageStats(200, 20, 0, 0),
        )
        val messages = listOf(
            msg("u1", "user", dbIds = listOf("u1")),
            msg("a1", "assistant", dbIds = listOf("db1")),
            msg("a2", "assistant", dbIds = listOf("db2", "db3")),
            msg("a3", "assistant", dbIds = listOf("db1"), streaming = true),
        )
        val joined = usageByUiMessageId(messages, usage)
        assertEquals(setOf("a1", "a2"), joined.keys)
        assertEquals(100, joined["a1"]!!.inputTokens)
        // A merged bubble sums its source rows.
        assertEquals(200, joined["a2"]!!.inputTokens)
    }

    @Test
    fun `messages without usage rows are dropped`() {
        val joined = usageByUiMessageId(listOf(msg("a1", "assistant", dbIds = listOf("nope"))), emptyMap())
        assertTrue(joined.isEmpty())
    }

    // ── Flat row resolution ──────────────────────────────────────────────

    @Test
    fun `footer lands on the last row of the assistant message`() {
        val stats = MessageUsageStats(100, 10, 0, 0)
        val items = listOf<FlatChatItem>(
            FlatChatItem.UserBubble(ChatMessage(id = "u1", role = "user", content = "hi")),
            FlatChatItem.AssistantHeader(messageId = "a1"),
            FlatChatItem.AssistantMarkdownBlock(
                messageId = "a1",
                parentBlockId = "b0",
                rawText = "one",
                blockIndex = 0,
                isLastBlockOfMessage = false,
                messageIsStreaming = false,
                messageMarkdown = "one",
            ),
            FlatChatItem.AssistantToolUse(
                messageId = "a1",
                block = AssistantBlock(id = "t1", kind = "tool_use", toolName = "read"),
                allToolBlocks = emptyList(),
            ),
            FlatChatItem.AssistantMarkdownBlock(
                messageId = "a1",
                parentBlockId = "b1",
                rawText = "two",
                blockIndex = 1,
                isLastBlockOfMessage = true,
                messageIsStreaming = false,
                messageMarkdown = "two",
            ),
        )
        val rows = computeUsageFooterRows(items, mapOf("a1" to stats))
        assertEquals(1, rows.size)
        // The LAST flat row of a1 — the second markdown block, not the tool
        // pill and not the header.
        assertEquals(stats, rows[FlatKeys.of(FlatKeys.KIND_MDBLOCK, "a1", "b1", 1)])
    }

    @Test
    fun `no usage means no footer keys`() {
        val items = listOf<FlatChatItem>(
            FlatChatItem.AssistantTyping(messageId = "a1"),
        )
        assertTrue(computeUsageFooterRows(items, emptyMap()).isEmpty())
    }
}
