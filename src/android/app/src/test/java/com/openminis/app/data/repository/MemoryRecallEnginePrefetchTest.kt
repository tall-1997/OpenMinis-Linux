package com.openminis.app.data.repository

import com.openminis.app.data.memory.MemoryEmbeddingSupport
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

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

    @get:Rule val tmp = TemporaryFolder()

    private fun tempDir(): File = tmp.newFolder()

    private fun seed(dir: File) {
        File(dir, "2026-11-12.md").writeText(
            "# 项目偏好\n用户偏好安静模式\n中午的咖啡店在楼下\n数据库用 Postgres\n$semanticOnlyLine\n",
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
        val query = "autumn markdown"

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
        val query = "autumn markdown"

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

    @Test
    fun `delivery cache keeps BM25 order and scores without rescoring changed corpus`() = runBlocking {
        val fixture = DeliveryFixture()
        File(fixture.dir, "second.md").writeText("quartz crystal samples\n")
        val edited = File(fixture.dir, "edited.md").apply { writeText("lemon grove\n") }
        val engine = fixture.newEngine()
        fixture.prefetch(engine)
        val cached = fixture.candidates(engine)
        assertEquals(setOf(fixture.keywordLine, "quartz crystal samples", fixture.vectorHit.line), cached.map { it.line }.toSet())
        assertEquals(1, fixture.reranks.get())

        // Guest edits bypass the host generation. Neither file was in the shortlist.
        val added = File(fixture.dir, "new.md").apply { writeText("fresh figs\n") }
        edited.writeText("lime grove\n")
        assertEquals(cached, fixture.candidates(engine))
        // Turn both into keyword matches to make a hidden rescan observable in the result.
        added.writeText("new quartz ore\n")
        edited.writeText("edited quartz deposit\n")
        repeat(2) { assertEquals(cached, fixture.candidates(engine)) }
        assertEquals(1, fixture.reranks.get())
        assertEquals(1, fixture.lookups.get())
        assertEquals(
            setOf(fixture.keywordLine, "quartz crystal samples", "new quartz ore", "edited quartz deposit"),
            engine.recall(fixture.query).map { it.line }.toSet(),
        )
        assertEquals(2, fixture.reranks.get())
    }

    @Test
    fun `disabling a cached source falls back to current BM25`() = assertGovernedFallback {
        semanticFile.writeText("---\ndisabled: true\n---\n${vectorHit.line}\n")
    }

    @Test
    fun `crossing a cached source TTL falls back within cache lifetime`() = assertGovernedFallback(
        seed = {
            semanticFile.writeText("---\nttl_days: 1\n---\n${vectorHit.line}\n")
            assertTrue(semanticFile.setLastModified(now - MemoryLifecycle.DAY_MS + 2_000))
        },
    ) { now += 2_001 }

    @Test
    fun `a new replacement header outside the shortlist forces fallback`() = assertGovernedFallback {
        File(dir, "correction.md").writeText("---\nsupersedes: ${semanticFile.name}\n---\nreplacement note\n")
    }

    private fun assertGovernedFallback(seed: DeliveryFixture.() -> Unit = {}, change: DeliveryFixture.() -> Unit) = runBlocking {
        val fixture = DeliveryFixture().apply(seed)
        val engine = fixture.newEngine()
        fixture.prefetch(engine)
        assertEquals(listOf(fixture.keywordLine, fixture.vectorHit.line), fixture.candidates(engine).map { it.line })
        assertEquals(1, fixture.reranks.get())

        val newKeyword = "new quartz ore"
        File(fixture.dir, "alternate.md").writeText("$newKeyword\n")
        fixture.change()
        assertEquals(setOf(fixture.keywordLine, newKeyword), fixture.candidates(engine).map { it.line }.toSet())
        assertEquals(2, fixture.reranks.get())
        assertEquals(1, fixture.lookups.get())
    }

    @Test
    fun `delivery uses exact query and turn and reopens after generation changes`() = runBlocking {
        val fixture = DeliveryFixture()
        val engine = fixture.newEngine()
        val projectionStartedAt = fixture.now
        fixture.now += 1
        fixture.prefetch(engine)
        val cachedLines = listOf(fixture.keywordLine, fixture.vectorHit.line)
        assertEquals(cachedLines, engine.recallCandidatesForDelivery(fixture.query, projectionStartedAt, fixture.turn).map { it.line })
        assertEquals(1, fixture.reranks.get())
        // Both alternative keys still have a real keyword hit; neither may reuse the vector.
        assertEquals(listOf(fixture.keywordLine), fixture.candidates(engine, query = "quartz quartz").map { it.line })
        assertEquals(listOf(fixture.keywordLine), fixture.candidates(engine, turn = "persisted-user-2").map { it.line })
        assertEquals(listOf(fixture.keywordLine), fixture.candidates(engine, turn = null).map { it.line })
        MemoryLifecycle.invalidateRecall()
        assertEquals(listOf(fixture.keywordLine), fixture.candidates(engine).map { it.line })
        assertEquals(1, fixture.lookups.get())
        fixture.prefetch(engine)
        assertEquals(cachedLines, fixture.candidates(engine).map { it.line })
        assertEquals(2, fixture.lookups.get())
    }

    @Test
    fun `an older in-flight generation cannot overwrite the current prefetch`() = runBlocking {
        val fixture = DeliveryFixture()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val engine = fixture.newEngine { attempt ->
            if (attempt == 1) {
                started.complete(Unit)
                release.await()
            }
            listOf(fixture.vectorHit)
        }
        val older = launch { fixture.prefetch(engine) }
        try {
            withTimeout(5_000) { started.await() }
            assertEquals(listOf(fixture.keywordLine), fixture.candidates(engine).map { it.line })
            fixture.prefetch(engine) // Same in-flight key must not launch another lookup.
            assertEquals(1, fixture.lookups.get())
            MemoryLifecycle.invalidateRecall()
            withTimeout(5_000) { fixture.prefetch(engine) }
        } finally {
            release.complete(Unit)
            older.join()
        }
        assertEquals(2, fixture.lookups.get())
        assertEquals(listOf(fixture.keywordLine, fixture.vectorHit.line), fixture.candidates(engine).map { it.line })
    }

    @Test
    fun `failed vector lookup with BM25 hits permits a retry after the short backoff`() = runBlocking {
        val fixture = DeliveryFixture()
        val engine = fixture.newEngine { attempt ->
            if (attempt == 1) error("embedding unavailable")
            listOf(fixture.vectorHit)
        }
        fixture.prefetch(engine)
        assertEquals(listOf(fixture.keywordLine), fixture.candidates(engine).map { it.line })
        fixture.now += 4_999
        fixture.prefetch(engine)
        assertEquals(1, fixture.lookups.get())
        fixture.now += 1
        assertEquals(listOf(fixture.keywordLine), fixture.candidates(engine).map { it.line })
        assertEquals(1, fixture.lookups.get()) // A preview does not retry.
        fixture.prefetch(engine)
        assertEquals(2, fixture.lookups.get())
        assertEquals(listOf(fixture.keywordLine, fixture.vectorHit.line), fixture.candidates(engine).map { it.line })
    }

    @Test
    fun `cancelled prefetch neither ranks nor backfills and the same key can retry`() = runBlocking {
        val fixture = DeliveryFixture()
        val backfilled = AtomicBoolean()
        val engine = fixture.newEngine(onBackfill = { backfilled.set(true) }) { attempt ->
            if (attempt == 1) throw CancellationException("cancelled lookup")
            listOf(fixture.vectorHit)
        }
        fixture.prefetch(engine)
        assertFalse(backfilled.get())
        assertEquals(0, fixture.reranks.get())
        fixture.prefetch(engine)
        assertEquals(2, fixture.lookups.get())
        assertEquals(listOf(fixture.keywordLine, fixture.vectorHit.line), fixture.candidates(engine).map { it.line })
    }

    private inner class DeliveryFixture {
        val dir = tempDir()
        val query = "quartz"
        val turn = "persisted-user-1"
        var now = 1_798_200_000_000L
        val lookups = AtomicInteger()
        val reranks = AtomicInteger()
        val keywordLine = "quartz reference"
        val semanticFile = File(dir, "2026-10-22.md")
        val vectorHit = MemoryEmbeddingSupport.SemanticHit(semanticFile.name, "kiwifruit private vector", 0.9f, dir.canonicalPath)

        init {
            File(dir, "2026-10-20.md").writeText("$keywordLine\n")
            semanticFile.writeText("${vectorHit.line}\n")
        }

        fun newEngine(
            onBackfill: () -> Unit = {},
            lookup: suspend (Int) -> List<MemoryEmbeddingSupport.SemanticHit> = { listOf(vectorHit) },
        ): MemoryRecallEngine = MemoryRecallEngine(
            { dir },
            embeddingSupport = object : MemoryEmbeddingSupport {
                override suspend fun semanticHits(query: String, limit: Int, scopes: Collection<String>) = lookup(lookups.incrementAndGet())
                override fun backfillAsync(dirs: List<File>) = onBackfill()
            },
            clock = { now },
            rerankerProvider = { MemoryReranker { _, hits -> reranks.incrementAndGet(); hits.reversed() } },
        )

        suspend fun prefetch(engine: MemoryRecallEngine) = engine.prefetchSemanticForTest(query, turn)

        fun candidates(engine: MemoryRecallEngine, query: String = this.query, turn: String? = this.turn) =
            engine.recallCandidatesForDelivery(query, now, turn)
    }

    // ── 3. default OFF is byte-identical ───────────────────────────────────

    @Test
    fun `no support - recallForPromptCached equals recallForPrompt on an identical engine`() = runBlocking {
        val dirA = tempDir()
        seed(dirA)
        val cachedEngine = engine(dirA, support = null)
        val plainEngine = engine(dirA, support = null)
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
