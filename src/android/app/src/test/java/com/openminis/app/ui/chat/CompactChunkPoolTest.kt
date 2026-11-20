package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-compact-chunk-pool] The rolling per-compaction chunk pool plus the
 * keyword retrieval that decides which chunks ride along with the current
 * instruction. Legacy markers (no `summary_chunks`) must resolve to an empty
 * pool so the read side keeps using the monolithic summary.
 *
 * [P1-18] The former [ChatViewModel.parseSummaryChunks] projection was
 * deleted as production-dead; assertions that read the pool back now parse
 * the marker column locally through [chunkTexts] (same legacy/garbage →
 * empty contract the write path relies on).
 */
class CompactChunkPoolTest {

    private fun chunk(text: String) = text

    /** Local mirror of the deleted projection: pool texts, oldest first. */
    private fun chunkTexts(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.optString("t")?.takeIf { it.isNotBlank() }
            }
        }.getOrElse { emptyList() }
    }

    @Test
    fun `legacy markers carry no pool`() {
        assertTrue(chunkTexts(null).isEmpty())
        assertTrue(chunkTexts("").isEmpty())
        assertTrue(chunkTexts("not json").isEmpty())
    }

    @Test
    fun `first append produces a single retrievable chunk`() {
        val json = appendSummaryChunk(null, "notification pipeline work")
        assertEquals(listOf("notification pipeline work"), chunkTexts(json))
    }

    @Test
    fun `appends accumulate and stay chronological`() {
        var json: String? = null
        listOf("alpha", "beta", "gamma").forEach { json = appendSummaryChunk(json, it) }
        assertEquals(listOf("alpha", "beta", "gamma"), chunkTexts(json))
    }

    @Test
    fun `the pool is capped and keeps the newest chunks`() {
        var json: String? = null
        for (i in 1..(SUMMARY_CHUNK_POOL_MAX + 4)) {
            json = appendSummaryChunk(json, "chunk-$i")
        }
        val chunks = chunkTexts(json)
        assertEquals(SUMMARY_CHUNK_POOL_MAX, chunks.size)
        assertTrue(chunks.last().endsWith("chunk-${SUMMARY_CHUNK_POOL_MAX + 4}"))
        assertFalseFirst(chunks)
    }

    private fun assertFalseFirst(chunks: List<String>) {
        assertTrue("oldest chunks should be evicted", !chunks.first().endsWith("chunk-1"))
    }

    @Test
    fun `an oversized chunk is capped instead of bloating the pool`() {
        val huge = "x".repeat(SUMMARY_CHUNK_MAX_CHARS * 3)
        val chunks = chunkTexts(appendSummaryChunk(null, huge))
        assertEquals(1, chunks.size)
        assertEquals(SUMMARY_CHUNK_MAX_CHARS, chunks.first().length)
    }

    @Test
    fun `retrieval picks the chunk matching the current instruction`() {
        var json: String? = null
        listOf(
            chunk("deployment: switched the release tag and published the APK"),
            chunk("notification: rewrote the foreground service publish pipeline"),
            chunk("database: added the summary chunk pool migration 17 to 18"),
        ).forEach { json = appendSummaryChunk(json, it) }
        val picked = selectSummaryChunks(json, "what did we do with the foreground service")
        assertTrue(
            "expected the notification chunk, got $picked",
            picked.any { it.contains("foreground service") },
        )
    }

    @Test
    fun `retrieval returns selected chunks in chronological order`() {
        var json: String? = null
        listOf("alpha budget", "beta budget", "gamma budget").forEach {
            json = appendSummaryChunk(json, it)
        }
        val picked = selectSummaryChunks(json, "alpha beta gamma budget", topK = 3)
        assertEquals(listOf("alpha budget", "beta budget", "gamma budget"), picked)
    }

    @Test
    fun `an off-topic query falls back to the most recent chunk`() {
        var json: String? = null
        listOf("old topic", "middle topic", "latest topic").forEach {
            json = appendSummaryChunk(json, it)
        }
        assertEquals(listOf("latest topic"), selectSummaryChunks(json, "完全无关的查询"))
    }

    @Test
    fun `a single-chunk pool resolves to that chunk`() {
        val json = appendSummaryChunk(null, "only chunk")
        assertEquals(listOf("only chunk"), selectSummaryChunks(json, "anything"))
    }

    @Test
    fun `cjk chunks are retrievable by a cjk query`() {
        var json: String? = null
        listOf("部署：发布版本标签并上传安装包", "通知：重写前台服务发布管线").forEach {
            json = appendSummaryChunk(json, it)
        }
        val picked = selectSummaryChunks(json, "前台服务怎么改的")
        assertTrue(
            "expected the 通知 chunk, got $picked",
            picked.any { it.contains("前台服务") },
        )
    }

    @Test
    fun `cjk queries tokenize into bigrams rather than one noisy term`() {
        val terms = summaryQueryTerms("压缩机制")
        assertTrue(terms.contains("压缩"))
        assertTrue(terms.contains("缩机"))
    }

    /**
     * [P3-3] An over-budget pool must degrade to its newest fitting records —
     * not be dropped whole, which silently erased every earlier snapshot on
     * both the read and the write path.
     */
    @Test
    fun `a pool within the byte budget passes through unchanged`() {
        val json = appendSummaryChunk(null, "notification pipeline work")
        assertEquals(json, fitSummaryChunks(json))
        assertNull(fitSummaryChunks(null))
        assertNull(fitSummaryChunks("not json"))
        assertNull(fitSummaryChunks("   "))
    }

    @Test
    fun `an over-budget pool keeps the newest records that fit and stays retrievable`() {
        // A pool written by THIS build can never exceed the budget (8 records
        // x 4k cap); the over-budget case only arises from a legacy pool with
        // larger records, so the fixture is hand-built.
        val legacy = org.json.JSONArray()
        for (i in 0 until 12) {
            legacy.put(org.json.JSONObject().put("t", "legacy-$i " + "x".repeat(6_000)).put("a", i.toLong()))
        }
        val pool = legacy.toString()
        assertTrue("the fixture must actually exceed the budget", pool.length > SUMMARY_CHUNK_POOL_MAX_CHARS)
        val fitted = fitSummaryChunks(pool)!!
        assertTrue(fitted.length <= SUMMARY_CHUNK_POOL_MAX_CHARS)
        val texts = chunkTexts(fitted)
        assertTrue("newest records kept, oldest evicted", texts.size in 1 until 12)
        assertTrue(texts.last().startsWith("legacy-11"))
        assertFalse(texts.first().startsWith("legacy-0"))
        assertTrue(
            "the fitted pool must stay retrievable JSON",
            selectSummaryChunks(fitted, "legacy-11").any { it.startsWith("legacy-11") },
        )
    }

    @Test
    fun `a single record over the budget is truncated to its newest head instead of vanishing`() {
        val pool = """[{"t":"${"y".repeat(200_000)}","a":1}]"""
        val fitted = fitSummaryChunks(pool)!!
        assertTrue(fitted.length <= SUMMARY_CHUNK_POOL_MAX_CHARS)
        val texts = chunkTexts(fitted)
        assertEquals(1, texts.size)
        assertTrue(texts.single().startsWith("y"))
        assertTrue(texts.single().length < 200_000)
        assertEquals(texts, selectSummaryChunks(fitted, "whatever"))
    }
}
