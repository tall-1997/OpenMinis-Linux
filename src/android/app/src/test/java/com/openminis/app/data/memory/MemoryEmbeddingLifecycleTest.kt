package com.openminis.app.data.memory

import com.openminis.app.data.db.MemoryEmbeddingDao
import com.openminis.app.data.db.MemoryEmbeddingEntity
import com.openminis.app.data.repository.MemoryEntryOrigin
import com.openminis.app.data.repository.MemoryLifecycle
import com.openminis.app.data.repository.MemoryRecallEngine
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.util.IsoTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MemoryEmbeddingLifecycleTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun staleRoomRowsAndPrefetchCannotRecallExpiredContent() = runBlocking {
        val root = tmp.newFolder("memory")
        val start = 1_798_200_000_000L
        var now = start
        val repository = MemoryRepository(root) { now }
        val diary = File(root, "${IsoTime.formatLocalDate(now)}.md")
        assertTrue(repository.writeHostMemory("kiwifruit secret", origin = MemoryEntryOrigin.MEMORY_WRITE, ttlDays = 1, autoArchive = true).startsWith("Memory saved"))
        val dao = FakeDao()
        val provider = object : EmbeddingProvider {
            override suspend fun embed(texts: List<String>) = texts.map { floatArrayOf(1f, 0f) }
        }
        val indexer = MemoryEmbeddingIndexer(provider, "model", dao, this, clock = { now })
        indexer.indexFileNow(root, diary)
        val scopes = listOf(root.canonicalPath)
        assertEquals("kiwifruit secret", indexer.semanticHits("autumn harvest", 4, scopes).single().line)
        val support = object : MemoryEmbeddingSupport {
            override suspend fun semanticHits(query: String, limit: Int, scopes: Collection<String>) = indexer.semanticHits(query, limit, scopes)
            override fun backfillAsync(dirs: List<File>) = Unit
        }
        val engine = MemoryRecallEngine({ root }, embeddingSupport = support, clock = { now })
        engine.prefetchSemanticForTest("autumn harvest")
        assertEquals("kiwifruit secret", engine.recallCandidatesForDelivery("autumn harvest", now).single().line)
        now += MemoryLifecycle.DAY_MS
        assertEquals("still waiting for vector backfill", 1, dao.count())
        assertTrue(indexer.semanticHits("autumn harvest", 4, scopes).isEmpty())
        assertTrue(engine.recallCandidatesForDelivery("autumn harvest", now).isEmpty())
        indexer.indexFileNow(root, diary)
        assertEquals(0, dao.count())
        assertTrue(repository.restoreArchivedMemory(repository.archivedEntries("kiwifruit", 1).single().token))
        indexer.indexFileNow(root, diary)
        assertEquals("kiwifruit secret", indexer.semanticHits("autumn harvest", 4, scopes).single().line)
    }

    @Test fun emptyPrefetchReopensOnRestoreAndVectorCommitWithoutAnyPreviewWrites() = runBlocking {
        val root = tmp.newFolder("prefetch")
        val start = 1_798_200_000_000L
        var now = start
        val repo = MemoryRepository(root) { now }
        val diary = File(root, "${IsoTime.formatLocalDate(start)}.md")
        assertTrue(repo.writeHostMemory("kiwifruit secret", origin = MemoryEntryOrigin.MEMORY_WRITE, ttlDays = 1, autoArchive = true).startsWith("Memory saved"))
        val dao = FakeDao()
        val provider = object : EmbeddingProvider {
            override suspend fun embed(texts: List<String>) = texts.map { floatArrayOf(1f, 0f) }
        }
        val indexer = MemoryEmbeddingIndexer(provider, "model", dao, this, clock = { now })
        indexer.indexFileNow(root, diary)
        val lookups = java.util.concurrent.atomic.AtomicInteger()
        val support = object : MemoryEmbeddingSupport {
            override suspend fun semanticHits(query: String, limit: Int, scopes: Collection<String>): List<MemoryEmbeddingSupport.SemanticHit> {
                lookups.incrementAndGet()
                return indexer.semanticHits(query, limit, scopes)
            }
            override fun backfillAsync(dirs: List<File>) = Unit
        }
        val engine = MemoryRecallEngine({ root }, embeddingSupport = support, clock = { now })
        val query = "autumn harvest" // Intentionally no keyword matches.
        val turn = "database-user-1"
        now += MemoryLifecycle.DAY_MS
        indexer.indexFileNow(root, diary)
        assertEquals(0, dao.count())
        engine.prefetchSemanticForTest(query, turn)
        repeat(2) { assertTrue(engine.recallCandidatesForDelivery(query, now, turn).isEmpty()) }
        assertEquals(1, lookups.get())

        val archive = repo.archivedEntries("kiwifruit", 1).single()
        assertTrue(repo.restoreArchivedMemory(archive.token))
        engine.prefetchSemanticForTest(query, turn) // No clock change, same query and persisted user turn.
        assertEquals(2, lookups.get())
        assertTrue(engine.recallCandidatesForDelivery(query, now, turn).isEmpty()) // Indexing has not landed yet.
        indexer.indexFileNow(root, diary)
        assertEquals(1, dao.count())
        engine.prefetchSemanticForTest(query, turn)
        repeat(2) { assertEquals("kiwifruit secret", engine.recallCandidatesForDelivery(query, now, turn).single().line) }
        engine.prefetchSemanticForTest(query, turn)
        assertEquals("preview neither launched nor consumed the result", 3, lookups.get())

        val nextTurn = "database-user-2"
        assertTrue(engine.recallCandidatesForDelivery(query, now, nextTurn).isEmpty())
        assertEquals(3, lookups.get())
        engine.prefetchSemanticForTest(query, nextTurn)
        assertEquals(4, lookups.get())
        assertEquals("kiwifruit secret", engine.recallCandidatesForDelivery(query, now, nextTurn).single().line)
        val managed = MemoryLifecycle.read(root, diary.name)!!.managed().single()
        assertEquals(0, managed.state.usage)
        assertNull(managed.state.lastUsedAt)
    }

    @Test fun fileGovernanceRevalidatesStoredVectorsAndFinishedPrefetchInTheSameTurn() = runBlocking {
        val root = tmp.newFolder("file-governance")
        val start = 1_798_200_000_000L
        var now = start
        val source = "2026-10-22.md"
        val diary = File(root, source).apply {
            writeText("\uFEFF---\r\nttl_days: 1\r\ntype: hiddenheader\r\n---\r\nkiwifruit private vector\r\n")
            assertTrue(setLastModified(start - MemoryLifecycle.DAY_MS + 5))
        }
        val dao = FakeDao()
        val embedded = mutableListOf<String>()
        val indexer = MemoryEmbeddingIndexer(object : EmbeddingProvider {
            override suspend fun embed(texts: List<String>): List<FloatArray> {
                embedded += texts
                return texts.map { floatArrayOf(1f, 0f) }
            }
        }, "model", dao, this, clock = { now })
        indexer.indexFileNow(root, diary)
        assertEquals(listOf("kiwifruit private vector"), embedded)
        val support = object : MemoryEmbeddingSupport {
            override suspend fun semanticHits(query: String, limit: Int, scopes: Collection<String>) = indexer.semanticHits(query, limit, scopes)
            override fun backfillAsync(dirs: List<File>) = Unit
        }
        val engine = MemoryRecallEngine({ root }, embeddingSupport = support, clock = { now })
        val query = "zzzz qqqq" // No BM25 token or bigram appears in the candidate.
        val turn = "wire-turn"
        engine.prefetchSemanticForTest(query, turn)
        assertEquals("kiwifruit private vector", engine.recallCandidatesForDelivery(query, now, turn).single().line)
        assertTrue(engine.recallCandidatesForDelivery(query, now, "other-turn").isEmpty())
        val corrector = File(root, "correction.md")
        corrector.writeText("---\nsupersedes: $source\n---\nreplacement\n")
        assertTrue(indexer.semanticHits(query, 4, listOf(root.canonicalPath)).isEmpty())
        assertTrue(engine.recallCandidatesForDelivery(query, now, turn).isEmpty())
        corrector.writeText("---\ndisabled: true\nsupersedes: $source\n---\nreplacement\n")
        assertTrue(engine.recallCandidatesForDelivery(query, now, turn).isEmpty())
        assertTrue(corrector.delete())
        assertEquals("kiwifruit private vector", engine.recallCandidatesForDelivery(query, now, turn).single().line)
        now += 6 // Cross file TTL while the same completed prefetch is still fresh and SQLite has the old vector.
        assertEquals(1, dao.count())
        assertTrue(indexer.semanticHits(query, 4, listOf(root.canonicalPath)).isEmpty())
        assertTrue(engine.recallCandidatesForDelivery(query, now, turn).isEmpty())
        indexer.indexFileNow(root, diary)
        assertEquals(0, dao.count())
    }

    private class FakeDao : MemoryEmbeddingDao {
        private val entries = LinkedHashMap<String, MemoryEmbeddingEntity>()
        override suspend fun upsertAll(items: List<MemoryEmbeddingEntity>) { items.forEach { entries[it.memoryId] = it } }
        override suspend fun loadForModel(model: String, limit: Int) = entries.values.filter { it.model == model }.take(limit)
        override suspend fun loadForModelInScopes(model: String, scopes: Collection<String>, limit: Int) =
            entries.values.filter { it.model == model && it.scope in scopes }.take(limit)
        override suspend fun findExisting(ids: List<String>, model: String) = ids.filter { entries[it]?.model == model }
        override suspend fun listIdsForSource(scope: String, source: String) = entries.values.filter { it.scope == scope && it.source == source }.map { it.memoryId }
        override suspend fun deleteForScope(scope: String) { entries.values.removeIf { it.scope == scope } }
        override suspend fun deleteByIds(ids: List<String>) { ids.forEach { entries.remove(it) } }
        override suspend fun count(): Int = entries.size
    }
}
