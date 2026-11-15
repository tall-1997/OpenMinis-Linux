package com.openminis.app.tools

import android.system.Os
import java.io.File

/**
 * [T-prior-read] On-disk identity + fingerprint of one file, the unit the read
 * cache is keyed on. Identity is (dev, ino) — a path can be reached under
 * several spellings (symlink, bind mount, case variant) but the same bytes
 * collapse to one entry; the fingerprint is (mtimeMs, sizeBytes), which is the
 * ONLY staleness safety net (qwen #3932→#4002: demanding a full read as well
 * is a deadlock on huge files). A rewrite that preserves BOTH mtime and size
 * reads as fresh — the residual case is caught by TextReplacers' "0
 * occurrences" failure, which sends the model back to file_read.
 *
 * Pure JVM: Android FS access lives in [of]; tests construct identities
 * directly.
 */
data class FileIdentity(
    val dev: Long,
    val ino: Long,
    val mtimeMs: Long,
    val sizeBytes: Long,
) {
    /** Zero ino means the filesystem cannot answer "same file?" — fail closed. */
    val verifiable: Boolean get() = ino != 0L

    companion object {
        /**
         * Best-effort lstat; null on ANY failure. Callers route a null through
         * the enforcement fail-closed branch rather than improvising.
         */
        fun of(file: File): FileIdentity? = try {
            val st = Os.lstat(file.absolutePath)
            FileIdentity(st.st_dev, st.st_ino, st.st_mtime * 1000L, st.st_size)
        } catch (_: Exception) {
            null
        }
    }
}

/** [T-prior-read] Four-state verdict of [FileReadCache.check]. */
enum class ReadCacheState { FRESH, STALE, UNVERIFIABLE, UNKNOWN }

/**
 * [T-prior-read] Session-scoped cache of files the model has READ or AUTHORED,
 * ported from qwen-code services/fileReadCache.ts (Apache-2.0). An edit or
 * overwrite of an existing file is only allowed when the cached fingerprint
 * for that identity is [ReadCacheState.FRESH] and the entry is text-backed —
 * see [PriorReadEnforcement] for the decision tree.
 *
 * Contract highlights carried over from qwen:
 *  - ANY read counts (partial line windows included). Full-read requirements
 *    deadlock on huge files; drift is the safety net.
 *  - Sticky-on-true: a weaker later read (preview, narrower window) must not
 *    strip the edit permission a stronger earlier read earned.
 *  - Unverifiable identities (ino = 0) are never cached — honest failure beats
 *    a cache that cannot answer "same file?".
 *  - FIFO eviction at [MAX_ENTRIES]; a re-read re-arms an evicted file.
 *
 * Pure JVM (clock injected) so the JVM test suite can pin the contract.
 */
class FileReadCache(private val clock: () -> Long = System::currentTimeMillis) {

    /** @param lastReadCacheable false = binary/preview-only read; cannot back an edit. */
    data class Entry(
        var mtimeMs: Long,
        var sizeBytes: Long,
        var lastReadAt: Long,
        var lastWriteAt: Long,
        var lastReadCacheable: Boolean,
    )

    data class CheckResult(val state: ReadCacheState, val cacheable: Boolean = false)

    // LinkedHashMap in insertion order = FIFO eviction, matching qwen's
    // MAX_ENTRIES policy: oldest reads expire first.
    private val byInode = LinkedHashMap<String, Entry>()

    /** Register a successful file_read. Partial windows count; binary/preview reads set cacheable=false. */
    @Synchronized
    fun recordRead(id: FileIdentity, cacheable: Boolean) {
        if (!id.verifiable) return
        val now = clock()
        val existing = byInode[identityKey(id)]
        if (existing != null && existing.mtimeMs == id.mtimeMs && existing.sizeBytes == id.sizeBytes) {
            existing.lastReadAt = now
            existing.lastReadCacheable = existing.lastReadCacheable || cacheable
        } else {
            byInode[identityKey(id)] = Entry(id.mtimeMs, id.sizeBytes, now, 0L, cacheable)
        }
        evictIfNeeded()
    }

    /**
     * Register a successful file_write / file_edit / multi_edit: the model
     * authored the current bytes, so seed the entry text-backed and fresh —
     * a write→edit chain must not demand a re-read of what the model just
     * wrote. Call with the identity of the file AS WRITTEN (post-write lstat).
     */
    @Synchronized
    fun recordWrite(id: FileIdentity) {
        if (!id.verifiable) return
        val now = clock()
        val existing = byInode[identityKey(id)]
        if (existing != null && existing.mtimeMs == id.mtimeMs && existing.sizeBytes == id.sizeBytes) {
            existing.lastWriteAt = now
            existing.lastReadCacheable = true
        } else {
            byInode[identityKey(id)] = Entry(id.mtimeMs, id.sizeBytes, 0L, now, true)
        }
        evictIfNeeded()
    }

    @Synchronized
    fun check(id: FileIdentity): CheckResult {
        if (!id.verifiable) return CheckResult(ReadCacheState.UNVERIFIABLE)
        val entry = byInode[identityKey(id)] ?: return CheckResult(ReadCacheState.UNKNOWN)
        return if (entry.mtimeMs == id.mtimeMs && entry.sizeBytes == id.sizeBytes) {
            CheckResult(ReadCacheState.FRESH, entry.lastReadCacheable)
        } else {
            CheckResult(ReadCacheState.STALE)
        }
    }

    /** Session teardown / test reset. */
    @Synchronized
    fun clear() {
        byInode.clear()
    }

    private fun identityKey(id: FileIdentity) = "${id.dev}:${id.ino}"

    private fun evictIfNeeded() {
        while (byInode.size > MAX_ENTRIES) {
            val oldest = byInode.keys.firstOrNull() ?: break
            byInode.remove(oldest)
        }
    }

    companion object {
        const val MAX_ENTRIES = 4096
    }
}

/**
 * One cache per chat session (qwen: per-Config). Sub-agent lanes share the
 * main session id (ChatViewModelSubAgentRun routes through ChatViewModel's
 * executeTool), so the whole model family of a session shares one cache: a
 * file read by any lane backs edits by any lane. Strictness against external
 * writers is the drift check, not per-lane bookkeeping — per-lane caches would
 * only add false refusals between collaborating lanes.
 */
object SessionFileReadCaches {
    private val caches = java.util.concurrent.ConcurrentHashMap<String, FileReadCache>()

    fun forSession(sessionId: String): FileReadCache =
        caches.computeIfAbsent(sessionId) { FileReadCache() }

    /** Session teardown; also handy for tests that need a pristine cache. */
    fun clear(sessionId: String) {
        caches.remove(sessionId)
    }
}
