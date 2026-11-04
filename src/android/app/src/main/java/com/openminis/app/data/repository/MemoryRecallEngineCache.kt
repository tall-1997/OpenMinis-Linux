package com.openminis.app.data.repository

import java.io.File

/**
 * [T-memory-recall-io] Per-directory cache of [MemoryRecallEngine].
 *
 * The prompt builder called `MemoryRecallEngine.fromDir(...)` on every build,
 * and `fromDir` re-reads and re-parses `.recall-counts.json`. The engine's own
 * "cache" is per-instance, so a fresh instance per turn meant the sidecar was
 * read on every request and the in-memory counts never survived a turn.
 *
 * Keying by the resolved memory directory keeps one engine (and its recall-count
 * map) alive across turns. The LRU bound is what stops a session sweep from
 * pinning one engine per chat for the life of the process — engines hold no
 * files open, so eviction only costs the next re-read.
 */
object MemoryRecallEngineCache {

    /** Engines kept alive at once. */
    const val CAPACITY = 8

    private val engines = object : LinkedHashMap<String, MemoryRecallEngine>(CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, MemoryRecallEngine>?,
        ): Boolean = size > CAPACITY
    }

    /**
     * The engine for [memoryDir], created on first use. Returns null only when
     * the directory cannot be prepared (`fromDir`'s contract).
     */
    fun forSession(memoryDir: File): MemoryRecallEngine? {
        val key = runCatching { memoryDir.canonicalPath }.getOrElse { memoryDir.absolutePath }
        synchronized(engines) {
            engines[key]?.let { return it }
        }
        val engine = MemoryRecallEngine.fromDir(memoryDir) ?: return null
        synchronized(engines) {
            // Re-check: another thread may have inserted while we were building.
            engines[key]?.let { return it }
            engines[key] = engine
            return engine
        }
    }

    /** Test seam: drop every cached engine. */
    internal fun clearForTest() {
        synchronized(engines) { engines.clear() }
    }
}
