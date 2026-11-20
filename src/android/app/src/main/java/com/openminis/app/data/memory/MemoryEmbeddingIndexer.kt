package com.openminis.app.data.memory

import com.openminis.app.data.db.MemoryEmbeddingDao
import com.openminis.app.data.db.MemoryEmbeddingEntity
import com.openminis.app.data.repository.MemoryLifecycle
import com.openminis.app.data.repository.MemorySources
import com.openminis.app.data.repository.memoryTextPrefix
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * [T-memory-embedding-recall] Indexes memory lines into `memory_embeddings`
 * (#27) and serves cosine top-K lookups. One instance per process, shared by
 * the write hooks ([MemoryEmbeddingRuntime]) and the recall engine's
 * [MemoryEmbeddingSupport].
 *
 * Identity: a memory row is one non-blank diary LINE (the recall unit), keyed
 * by sha-256 of `scope|day|line` where `scope` is the canonical host
 * directory of the memory file and `day` matches the recall engine's key
 * (`2026-11-12` from `2026-11-12.md`). Re-indexing a file re-embeds only
 * lines whose id is missing under the CURRENT model, and drops rows whose
 * line disappeared (file edited / entry revoked).
 *
 * Failure policy: every failure is silent (log only). A failed write leaves
 * rows missing; the next [backfillAsync] or semantic lookup re-detects them
 * ("失败静默，下次检索触发补算"). Nothing here ever throws past the
 * async boundaries.
 */
class MemoryEmbeddingIndexer(
    private val provider: EmbeddingProvider,
    private val model: String,
    private val dao: MemoryEmbeddingDao,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    // ── indexing (write side) ──────────────────────────────────────────────

    /**
     * Fire-and-forget (re)index of one memory file. [file] must live under a
     * memory directory; its name is the recall `source`, its parent the scope.
     */
    fun indexFileAsync(file: File) {
        if (!isIndexable(file)) return
        scope.launch(Dispatchers.IO) {
            runCatching { indexFileNow(file.parentFile ?: return@launch, file) }
        }
    }

    /**
     * Backfill over whole memory directories: every indexable file that still
     * has missing vectors is (re)indexed. Throttled to one run per
     * [BACKFILL_THROTTLE_MS] — the recall path calls this opportunistically,
     * so a burst of turns must not trigger a burst of full-file scans.
     */
    fun backfillAsync(dirs: List<File>) {
        val now = System.currentTimeMillis()
        val last = lastBackfillAt.get()
        if (now - last < BACKFILL_THROTTLE_MS) return
        if (!lastBackfillAt.compareAndSet(last, now)) return
        scope.launch(Dispatchers.IO) {
            runCatching {
                for (dir in dirs.filter { it.isDirectory }) {
                    val names = dir.listFiles().orEmpty().filter(::isIndexable).map { it.name }
                    val active = MemorySources.withScope(dir, clock(), loadBody = { true }) { sources ->
                        sources.associate { it.name to indexableLines(it.view()) }
                    } ?: continue
                    val scopeKey = dir.canonicalPath
                    for (name in names) indexLines(scopeKey, name, active[name].orEmpty())
                }
            }
        }
    }

    /**
     * Drop every vector recorded under [dir]'s scope — called when a session
     * tree is deleted so its vectors do not linger.
     */
    fun deleteScopeAsync(dir: File) {
        val scopeKey = runCatching { dir.canonicalPath }.getOrElse { dir.absolutePath }
        scope.launch(Dispatchers.IO) {
            runCatching { dao.deleteForScope(scopeKey) }
        }
    }

    /**
     * Drop the vectors of one deleted memory file (scope = [dir], source =
     * [fileName]). Without this a deleted diary stays semantically
     * recallable — a privacy and correctness gap, since there is no file
     * left to re-index the rows away.
     */
    fun deleteSourceAsync(dir: File, fileName: String) {
        val scopeKey = runCatching { dir.canonicalPath }.getOrElse { dir.absolutePath }
        scope.launch(Dispatchers.IO) {
            runCatching {
                val stale = dao.listIdsForSource(scopeKey, fileName)
                deleteInChunks(stale)
            }
        }
    }

    /** Core, blocking; callers run it on IO. */
    suspend fun indexFileNow(dir: File, file: File) {
        if (!isIndexable(file) || file.parentFile?.canonicalFile != dir.canonicalFile) return
        val lines = MemorySources.withScope(dir, clock(), loadBody = { it == file.name }) { sources ->
            indexableLines(sources.firstOrNull { it.name == file.name }?.view())
        } ?: return
        indexLines(dir.canonicalPath, file.name, lines)
    }

    private suspend fun indexLines(scopeKey: String, source: String, lines: List<Line>) {
        val ids = lines.map { entryId(scopeKey, source, it.line) }
        val currentIds = ids.toHashSet()
        // Prune even when embeddings for newly added lines are temporarily unavailable.
        val stale = dao.listIdsForSource(scopeKey, source).filter { it !in currentIds }
        deleteInChunks(stale)
        if (stale.isNotEmpty()) MemoryLifecycle.invalidateRecall()
        if (ids.isEmpty()) return
        // Existing under the CURRENT model only — a model switch re-embeds.
        val existing = HashSet<String>()
        for (chunk in ids.chunked(IN_CHUNK)) {
            existing.addAll(dao.findExisting(chunk, model))
        }
        val missing = ArrayList<Line>(lines.size)
        for ((i, l) in lines.withIndex()) {
            if (ids[i] !in existing) missing.add(l)
        }
        if (missing.isNotEmpty()) {
            val vectors = provider.embed(missing.map { it.line }) ?: return
            if (vectors.size != missing.size) return
            val now = System.currentTimeMillis()
            dao.upsertAll(missing.mapIndexed { i, l ->
                MemoryEmbeddingEntity(
                    memoryId = entryId(scopeKey, source, l.line),
                    scope = scopeKey,
                    source = source,
                    line = l.line,
                    vector = FloatVectorBlob.encode(vectors[i]),
                    model = model,
                    dim = vectors[i].size,
                    updatedAt = now,
                )
            })
            // Restore may have triggered a lookup before backfill commits these vectors.
            MemoryLifecycle.invalidateRecall()
        }
    }

    private suspend fun deleteInChunks(ids: List<String>) {
        for (chunk in ids.chunked(IN_CHUNK)) dao.deleteByIds(chunk)
    }

    // ── lookup (recall side) ───────────────────────────────────────────────

    /**
     * Cosine top-K over stored vectors for [query] **restricted to
     * [scopes]** — the caller passes only the memory directories this
     * conversation may read (its private scope + the shared one). Without
     * the filter the shared table would leak another session's private
     * diary lines across conversations. Empty (never throws, never blocks
     * the caller beyond the suspend) when the query cannot be embedded or
     * nothing usable is stored.
     */
    suspend fun semanticHits(
        query: String,
        limit: Int,
        scopes: Collection<String>,
    ): List<MemoryEmbeddingSupport.SemanticHit> {
        if (query.isBlank() || limit <= 0 || scopes.isEmpty()) return emptyList()
        val queryVectors = provider.embed(listOf(query)) ?: return emptyList()
        if (queryVectors.isEmpty()) return emptyList()
        val queryVector = queryVectors[0]
        val scopeSet = LinkedHashSet<String>(scopes.size)
        for (s in scopes) scopeSet.add(runCatching { File(s).canonicalPath }.getOrElse { s })
        val rows = dao.loadForModelInScopes(model, scopeSet, MAX_LOADED_ROWS)
        if (rows.isEmpty()) return emptyList()
        val sources = MemorySources.recallSources(scopeSet.map(::File), clock(), rows.mapTo(HashSet()) { it.source })
        val activeLines = HashMap<String, Set<String>>()
        data class Scored(val hit: MemoryEmbeddingSupport.SemanticHit, val score: Float)
        val scored = ArrayList<Scored>(rows.size)
        for (row in rows) {
            val source = sources[row.source] ?: continue
            if (row.scope != source.directory.path) continue
            val visible = activeLines.getOrPut(row.source) { indexableLines(source.view).mapTo(HashSet()) { it.line } }
            if (row.line !in visible) continue
            val vector = FloatVectorBlob.decode(row.vector) ?: continue
            val score = VectorMath.cosine(queryVector, vector)
            // MIN_SEMANTIC_SCORE: high-dim cosine for unrelated text is a
            // small POSITIVE number — without a floor the semantic channel
            // would fill the recall budget with weakly-related memories and
            // reintroduce exactly the recall-noise class the cross-turn
            // dedup work removed.
            if (score < MIN_SEMANTIC_SCORE) continue
            scored.add(Scored(MemoryEmbeddingSupport.SemanticHit(row.source, row.line, score, scope = row.scope), score))
        }
        return scored.sortedByDescending { it.score }.take(limit).map { it.hit }
    }

    // ── line identity ──────────────────────────────────────────────────────

    private data class Line(val line: String)

    private fun indexableLines(view: MemoryLifecycle.View?): List<Line> =
        view?.let { MemorySources.recallLines(it).map { owned -> Line(memoryTextPrefix(owned.text, MAX_LINE_CHARS)) } }
            .orEmpty().distinct()

    /** sha-256 hex of `scope|day|line` — stable for an unchanged line. */
    internal fun entryId(scopeKey: String, source: String, line: String): String {
        val day = source.removeSuffix(".md")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$scopeKey|$day|$line".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun isIndexable(file: File): Boolean = file.isFile && MemorySources.isRecallSource(file.name)

    companion object {

        /** Recall is line-based; vectors for longer lines are poor signal anyway. */
        internal const val MAX_LINE_CHARS = 500

        /** Row cap for a cosine scan — bounded so recall stays O(cap). */
        private const val MAX_LOADED_ROWS = 4000

        /**
         * Below this cosine a hit is noise, not signal (unrelated text in
         * high-dim embedding space still scores ~0.05–0.25).
         */
        internal const val MIN_SEMANTIC_SCORE = 0.32f

        private const val BACKFILL_THROTTLE_MS = 30_000L

        private val lastBackfillAt = AtomicLong(0L)

        private const val IN_CHUNK = MemoryEmbeddingDao.IN_CHUNK
    }
}
