package com.openminis.app.data.repository

import com.openminis.app.data.memory.MemoryEmbeddingSupport
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-memory-embedding-recall] Semantic-recall fusion (#27): the merge rules,
 * the hybrid engine entry point, and — most importantly — that the DEFAULT
 * (support == null) engine and the provider-unavailable path produce results
 * identical to the plain keyword engine, so flipping the feature on can never
 * change a session that has no embeddings.
 */
class MemoryRecallEngineHybridTest {

    private fun tempDir(): File = Files.createTempDirectory("recall-hybrid").toFile()

    private fun engineWith(dir: File, support: MemoryEmbeddingSupport? = null): MemoryRecallEngine {
        File(dir, "2026-11-12.md").writeText(
            "# 项目偏好\n用户偏好安静模式\n中午的咖啡店在楼下\n数据库用 Postgres\n",
        )
        return MemoryRecallEngine({ dir }, null, support).also { it.loadRecallCounts() }
    }

    private class FakeSupport(
        private val hits: List<MemoryEmbeddingSupport.SemanticHit>,
    ) : MemoryEmbeddingSupport {
        var backfills = 0
            private set

        override suspend fun semanticHits(query: String, limit: Int): List<MemoryEmbeddingSupport.SemanticHit> =
            hits.take(limit)

        override fun backfillAsync(dirs: List<File>) {
            backfills++
        }
    }

    private fun hit(source: String, line: String, score: Float) =
        MemoryEmbeddingSupport.SemanticHit(source, line, score)

    private fun ftsHit(source: String, line: String, score: Double) = MemoryRecallEngine.RecallHit(
        source = source,
        line = line,
        day = source.removeSuffix(".md"),
        score = score,
        recallCount = 0,
        archived = false,
    )

    // ── SemanticRecallFusion (pure merge) ──────────────────────────────────

    @Test
    fun `fusion keeps fts order and appends semantic below`() {
        val fts = listOf(ftsHit("2026-11-12.md", "用户偏好安静模式", 5.0))
        val semantic = listOf(hit("2026-11-12.md", "数据库用 Postgres", 0.83f))
        val merged = MemoryRecallEngine.SemanticRecallFusion.merge(
            fts, semantic, limit = 4, perFileCap = 2, archiveCutoff = null,
        ) { it.removeSuffix(".md") }
        assertEquals(2, merged.size)
        assertEquals("用户偏好安静模式", merged[0].line)
        assertEquals("数据库用 Postgres", merged[1].line)
        // Weight strictly below the FTS hit.
        assertTrue(merged[1].score < merged[0].score)
        assertTrue(merged[1].scoreDetail.startsWith("semantic="))
        assertEquals(0, merged[1].recallCount)
    }

    @Test
    fun `fusion dedupes exact key and head-prefix near duplicates`() {
        val fts = listOf(ftsHit("2026-11-12.md", "用户偏好安静模式", 5.0))
        val semantic = listOf(
            hit("2026-11-12.md", "用户偏好安静模式", 0.9f), // exact dup
            hit("2026-11-12.md", "用户偏好安静模式（已确认）", 0.8f), // head-prefix dup
        )
        val merged = MemoryRecallEngine.SemanticRecallFusion.merge(
            fts, semantic, limit = 4, perFileCap = 4, archiveCutoff = null,
        ) { it.removeSuffix(".md") }
        assertEquals(1, merged.size)
    }

    @Test
    fun `fusion enforces limit and per-file cap`() {
        val fts = listOf(ftsHit("a.md", "one", 4.0))
        val semantic = listOf(
            hit("2026-11-12.md", "two", 0.9f),
            hit("2026-11-12.md", "three", 0.8f), // same file as "two": per-file cap denies it
            hit("b.md", "four", 0.7f),
            hit("c.md", "five", 0.6f),
        )
        val merged = MemoryRecallEngine.SemanticRecallFusion.merge(
            fts, semantic, limit = 3, perFileCap = 1, archiveCutoff = null,
        ) { it.removeSuffix(".md") }
        assertEquals(3, merged.size)
        assertEquals(listOf("one", "two", "four"), merged.map { it.line })
    }

    @Test
    fun `fusion keeps cosine score when keyword search found nothing`() {
        val semantic = listOf(hit("2026-11-12.md", "数据库用 Postgres", 0.83f))
        val merged = MemoryRecallEngine.SemanticRecallFusion.merge(
            emptyList(), semantic, limit = 4, perFileCap = 2, archiveCutoff = null,
        ) { it.removeSuffix(".md") }
        assertEquals(1, merged.size)
        assertEquals(0.83, merged[0].score, 1e-6)
    }

    @Test
    fun `fusion flags old entries archived and never global`() {
        val semantic = listOf(
            hit("2026-01-01.md", "old note", 0.9f),
            hit("GLOBAL.md", "global text", 0.8f),
        )
        val merged = MemoryRecallEngine.SemanticRecallFusion.merge(
            emptyList(), semantic, limit = 4, perFileCap = 4,
            archiveCutoff = java.time.LocalDate.parse("2026-06-01"),
        ) { it.removeSuffix(".md") }
        assertEquals(2, merged.size)
        assertTrue(merged[0].archived)
        assertTrue(!merged[1].archived) // "global" is not a date -> never archived
    }

    // ── engine hybrid path ─────────────────────────────────────────────────

    @Test
    fun `hybrid with no support matches plain recallForPrompt`() = runBlocking {
        val dir = tempDir()
        val engine = engineWith(dir, support = null)
        val query = "用户的偏好"
        // recallForPrompt and recallForPromptHybrid on the same default engine:
        // the support is null, so the hybrid must return the keyword result.
        assertEquals(
            engine.recallForPrompt(query).map { it.line },
            engine.recallForPromptHybrid(query).map { it.line },
        )
    }

    @Test
    fun `hybrid falls back to keyword result when provider unavailable`() = runBlocking {
        val dir = tempDir()
        val engine = engineWith(dir, FakeSupport(emptyList()))
        val query = "用户的偏好"
        val keyword = engine.recallForPrompt(query).map { it.line }
        val hybrid = engine.recallForPromptHybrid(query).map { it.line }
        assertTrue(keyword.isNotEmpty())
        assertEquals(keyword, hybrid)
    }

    @Test
    fun `hybrid appends semantic hits below keyword hits`() = runBlocking {
        val dir = tempDir()
        val support = FakeSupport(listOf(hit("2026-11-12.md", "数据库用 Postgres", 0.9f)))
        val engine = engineWith(dir, support)
        // "中午的咖啡店" keyword-matches exactly ONE fixture line, so the
        // per-file diversity cap (MAX_HITS_PER_FILE = 2) leaves room for the
        // semantic hit from the same file to be appended.
        val hits = engine.recallForPromptHybrid("中午的咖啡店")
        val lines = hits.map { it.line }
        assertTrue("keyword hit must come first", lines.first().contains("咖啡店"))
        assertTrue("semantic hit must be appended", lines.contains("数据库用 Postgres"))
        assertEquals(1, lines.indexOf("数据库用 Postgres"))
        assertEquals(1, support.backfills)
    }

    @Test
    fun `hybrid semantic hits obey the repeat cap across turns`() = runBlocking {
        val dir = tempDir()
        // A semantic-only line: it does not exist on disk, so keyword FTS can
        // never match it — the streak cap below is exercised by the semantic
        // path alone.
        val support = FakeSupport(listOf(hit("2026-11-01.md", "部署脚本在服务器上", 0.9f)))
        val engine = engineWith(dir, support)
        val query = "服务器部署的问题"
        repeat(MemoryRecallEngine.RECALL_REPEAT_CAP) {
            val hits = engine.recallForPromptHybrid(query)
            assertTrue(
                "semantic hit should surface before the cap",
                hits.any { it.line == "部署脚本在服务器上" },
            )
        }
        // One turn past the cap: the streak bookkeeping applies to semantic
        // hits exactly like keyword hits.
        val capped = engine.recallForPromptHybrid(query)
        assertTrue(capped.none { it.line == "部署脚本在服务器上" })
    }
}
