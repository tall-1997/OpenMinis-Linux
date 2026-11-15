package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-prior-read] JVM contract for the qwen-ported read cache. Tests construct
 * [FileIdentity]s directly — no filesystem involved.
 */
class FileReadCacheTest {

    private var nowMs = 1_000L
    private val cache = FileReadCache(clock = { nowMs })

    private fun id(mtime: Long, size: Long, dev: Long = 1L, ino: Long = 42L) =
        FileIdentity(dev, ino, mtime, size)

    @Test
    fun `read then check is fresh`() {
        cache.recordRead(id(100L, 10L), cacheable = true)
        val result = cache.check(id(100L, 10L))
        assertEquals(ReadCacheState.FRESH, result.state)
        assertTrue(result.cacheable)
    }

    @Test
    fun `untracked file is unknown`() {
        assertEquals(ReadCacheState.UNKNOWN, cache.check(id(100L, 10L)).state)
    }

    @Test
    fun `zero ino is unverifiable and never cached`() {
        cache.recordRead(id(100L, 10L, ino = 0L), cacheable = true)
        val result = cache.check(id(100L, 10L, ino = 0L))
        assertEquals(ReadCacheState.UNVERIFIABLE, result.state)
    }

    @Test
    fun `mtime drift is stale`() {
        cache.recordRead(id(100L, 10L), cacheable = true)
        assertEquals(ReadCacheState.STALE, cache.check(id(101L, 10L)).state)
    }

    @Test
    fun `size drift is stale`() {
        cache.recordRead(id(100L, 10L), cacheable = true)
        assertEquals(ReadCacheState.STALE, cache.check(id(100L, 11L)).state)
    }

    @Test
    fun `sticky cacheable - weaker later read keeps stronger earlier permission`() {
        cache.recordRead(id(100L, 10L), cacheable = true)
        cache.recordRead(id(100L, 10L), cacheable = false)
        val result = cache.check(id(100L, 10L))
        assertEquals(ReadCacheState.FRESH, result.state)
        assertTrue("a preview must not strip a real read's edit permission", result.cacheable)
    }

    @Test
    fun `weaker first read stays weak until drift`() {
        cache.recordRead(id(100L, 10L), cacheable = false)
        cache.recordRead(id(100L, 10L), cacheable = false)
        assertFalse(cache.check(id(100L, 10L)).cacheable)
        // A fresh read AFTER drift re-arms with the new value.
        cache.recordRead(id(102L, 12L), cacheable = true)
        assertTrue(cache.check(id(102L, 12L)).cacheable)
    }

    @Test
    fun `recordWrite seeds freshness without any read`() {
        cache.recordWrite(id(200L, 20L))
        val result = cache.check(id(200L, 20L))
        assertEquals(ReadCacheState.FRESH, result.state)
        assertTrue("the model authored the bytes, so they are text-backed", result.cacheable)
    }

    @Test
    fun `write to edit chain passes without re-read`() {
        cache.recordWrite(id(200L, 20L))
        assertEquals(ReadCacheState.FRESH, cache.check(id(200L, 20L)).state)
        cache.recordWrite(id(210L, 22L)) // the edit changed the file again
        assertEquals(ReadCacheState.FRESH, cache.check(id(210L, 22L)).state)
    }

    @Test
    fun `fifo eviction drops the oldest read`() {
        for (i in 0 until FileReadCache.MAX_ENTRIES) {
            cache.recordRead(id(100L, 1L, ino = i.toLong() + 1), cacheable = true)
        }
        assertEquals(ReadCacheState.FRESH, cache.check(id(100L, 1L, ino = 1L)).state)
        cache.recordRead(id(100L, 1L, ino = FileReadCache.MAX_ENTRIES.toLong() + 1), cacheable = true)
        assertEquals("oldest read is evicted FIFO", ReadCacheState.UNKNOWN, cache.check(id(100L, 1L, ino = 1L)).state)
        assertEquals("newest read survives", ReadCacheState.FRESH, cache.check(id(100L, 1L, ino = FileReadCache.MAX_ENTRIES.toLong() + 1)).state)
    }

    @Test
    fun `clear empties the cache`() {
        cache.recordRead(id(100L, 10L), cacheable = true)
        cache.clear()
        assertEquals(ReadCacheState.UNKNOWN, cache.check(id(100L, 10L)).state)
    }

    @Test
    fun `different dev or ino are different files`() {
        cache.recordRead(id(100L, 10L, dev = 1L, ino = 42L), cacheable = true)
        assertEquals(ReadCacheState.UNKNOWN, cache.check(id(100L, 10L, dev = 2L, ino = 42L)).state)
        assertEquals(ReadCacheState.UNKNOWN, cache.check(id(100L, 10L, dev = 1L, ino = 43L)).state)
    }

    @Test
    fun `sessions are isolated and stable`() {
        val a = SessionFileReadCaches.forSession("sess-a")
        val b = SessionFileReadCaches.forSession("sess-b")
        a.recordRead(id(100L, 10L), cacheable = true)
        assertEquals(ReadCacheState.UNKNOWN, b.check(id(100L, 10L)).state)
        assertSame("same session id returns the same cache", a, SessionFileReadCaches.forSession("sess-a"))
        SessionFileReadCaches.clear("sess-a")
        assertEquals("cleared session starts from scratch", ReadCacheState.UNKNOWN, SessionFileReadCaches.forSession("sess-a").check(id(100L, 10L)).state)
    }
}
