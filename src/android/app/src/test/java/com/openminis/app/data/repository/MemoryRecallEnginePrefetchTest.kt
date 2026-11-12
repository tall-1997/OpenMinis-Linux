package com.openminis.app.data.repository

import com.openminis.app.data.memory.MemoryEmbeddingSupport
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-memory-embedding-wiring] The read-side wiring of #27: the send pipeline
 * prefetches the hybrid (FTS + semantic) result for the exact query the
 * synchronous prompt render will build, and [MemoryRecallEngine.recallForPromptCached]
 * serves that cache without ever blocking on network.
 *
 * The red lines pinned here:
 *  1. **Cache hit** — a prefetched query returns the HYBRID result (including a
 *     semantic-only line that keyword FTS can never produce, proving the hit).
 *  2. **One-shot consumption + query invalidation** — a different query (or a
 *     second render of the same query after consumption) degrades to the plain
 *     keyword path, never to a stale hybrid.
 *  3. **Default OFF is byte-identical** — an engine built without embedding
 *     support produces the exact same [MemoryRecallEngine.RecallHit] list from
 *     [MemoryRecallEngine.recallForPromptCached] as [MemoryRecallEngine.recallForPrompt]
 *     does, so wiring the cached entry point cannot perturb the feature-off
 *     sessions (the pre-#27 behaviour).
 */
class MemoryRecallEnginePrefetchTest {

    private fun tempDir(): File = Files.createTempDirectory("recall-prefetch").toFile()

    private fun seed(dir: File) {
        File(dir, "2026-11-12.md").writeText(
            "# 项目偏好\n用户偏好安静模式\n中午的咖啡店在楼下\n数据库用 Postgres\n",
        )
    }

    private fun engine(dir: File, support: MemoryEmbeddingSupport?): MemoryRecallEngine =
        MemoryRecallEngine({ dir }, null, support).also { it.loadRecallCounts() }

    private class FakeSupport(
        private val hits: List<MemoryEmbeddingSupport.SemanticHit>,
    ) : MemoryEmbeddingSupport {
        override suspend fun semanticHits(
            query: String,
            limit: Int,
            scopes: Collection<String>,
        ): List<MemoryEmbeddingSupport.SemanticHit> = hits.take(limit)

        override fun backfillAsync(dirs: List<File>) = Unit
    }

    /** A line ONLY the fake embedding support serves — keyword FTS cannot match it. */
    private val semanticOnlyLine = "部署脚本在服务器上"

    // ── 1. cache hit ───────────────────────────────────────────────────────

    @Test
    fun `prefetched query returns the hybrid result including semantic-only hits`() = runBlocking {
        val dir = tempDir()
        seed(dir)
        val support = FakeSupport(listOf(MemoryEmbeddingSupport.SemanticHit("2026-11-12.md", semanticOnlyLine, 0.9f)))
        val engine = engine(dir, support)
        val query = "服务器部署的问题"

        engine.prefetchSemanticForTest(query)
        val cached = engine.recallForPromptCached(query).map { it.line }

        // The semantic-only line is the cache-hit proof: no keyword path can
        // produce it, so its presence means the prefetched hybrid was served.
        // (The fixture deliberately has no keyword match for this query, so
        // the semantic hit is the ENTIRE result — fusion keeps cosine scores
        // when keyword search found nothing.)
        assertTrue("cache hit must carry the semantic hit", cached.contains(semanticOnlyLine))
    }

    // ── 2. consumption + invalidation ──────────────────────────────────────

    @Test
    fun `second render of the same query degrades to the keyword path`() = runBlocking {
        val dir = tempDir()
        seed(dir)
        val support = FakeSupport(listOf(MemoryEmbeddingSupport.SemanticHit("2026-11-12.md", semanticOnlyLine, 0.9f)))
        val engine = engine(dir, support)
        val query = "服务器部署的问题"

        engine.prefetchSemanticForTest(query)
        assertTrue(engine.recallForPromptCached(query).map { it.line }.contains(semanticOnlyLine))
        // The slot is consumed: the next render of the SAME query must be the
        // synchronous keyword fallback (empty cache), not a replayed hybrid.
        // (The keyword path finds nothing for this query — the empty result is
        // exactly what the render would have computed without the prefetch.)
        val second = engine.recallForPromptCached(query).map { it.line }
        assertFalse("consumed cache must not replay the hybrid", second.contains(semanticOnlyLine))
    }

    @Test
    fun `a changed query never reads another query's prefetch`() = runBlocking {
        val dir = tempDir()
        seed(dir)
        val support = FakeSupport(listOf(MemoryEmbeddingSupport.SemanticHit("2026-11-12.md", semanticOnlyLine, 0.9f)))
        val engine = engine(dir, support)
        val prefetchQuery = "服务器部署的问题"
        val otherQuery = "数据库用哪个"

        engine.prefetchSemanticForTest(prefetchQuery)
        val other = engine.recallForPromptCached(otherQuery).map { it.line }
        assertFalse(
            "query identity is the cache key: q1's prefetch must not serve q2",
            other.contains(semanticOnlyLine),
        )
        // The keyword path still works for the other query.
        assertTrue(other.any { it.contains("Postgres") })
    }

    // ── 3. default OFF is byte-identical ───────────────────────────────────

    @Test
    fun `no support - recallForPromptCached equals recallForPrompt on an identical engine`() = runBlocking {
        val dirA = tempDir()
        val dirB = tempDir()
        seed(dirA)
        seed(dirB)
        val cachedEngine = engine(dirA, support = null)
        val plainEngine = engine(dirB, support = null)
        val query = "用户的偏好"

        // The public fire-and-forget entry must be a hard no-op with no support
        // wired (the default), not merely "usually skipped".
        cachedEngine.prefetchSemantic(query)

        assertEquals(
            plainEngine.recallForPrompt(query),
            cachedEngine.recallForPromptCached(query),
        )
    }

    @Test
    fun `no support - prefetchSemantic never fills the cache`() = runBlocking {
        val dir = tempDir()
        seed(dir)
        val engine = engine(dir, support = null)
        val query = "中午的咖啡店"
        engine.prefetchSemantic(query)
        // Whatever the async timing, with no support there is nothing to fetch:
        // the result is the plain keyword path by construction.
        assertEquals(
            engine.recallForPrompt(query),
            engine.recallForPromptCached(query),
        )
    }
}
