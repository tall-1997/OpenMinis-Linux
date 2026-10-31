package com.openminis.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Process-level cache for provider-row snapshots. The provider list screen
 * used to recompute every row's encrypted-prefs / OAuth / visible-entry
 * reads from an empty map on every navigation entry, so each row flashed
 * "加载中" until the IO job landed.
 *
 * The cache lives inside ProviderRepository (an app-process singleton), so
 * a re-entry with the same config revision, credential revision and
 * instance-id set renders the first frame from the cache. Any real change
 * bumps one of the revisions or the id set, which changes the key and
 * invalidates the cache.
 */
class ProviderRowSnapshotCacheTest {

    private val cache = ProviderRowSnapshotCache()
    private val keyA = ProviderRowSnapshotCache.keyOf(
        configRevision = 1L,
        credentialRevision = 2L,
        instanceIds = listOf("i1", "i2"),
    )
    private val snapA = mapOf("i1" to ProviderRowSnapshot(loaded = true, modelCount = 3))

    @Test
    fun `miss before first store`() {
        assertNull(cache.cached(keyA))
    }

    @Test
    fun `hit after store with the same key`() {
        cache.store(keyA, snapA)
        assertSame(snapA, cache.cached(keyA))
    }

    @Test
    fun `config revision change invalidates`() {
        cache.store(keyA, snapA)
        val other = ProviderRowSnapshotCache.keyOf(9L, 2L, listOf("i1", "i2"))
        assertNull(cache.cached(other))
    }

    @Test
    fun `credential revision change invalidates`() {
        cache.store(keyA, snapA)
        val other = ProviderRowSnapshotCache.keyOf(1L, 9L, listOf("i1", "i2"))
        assertNull(cache.cached(other))
    }

    @Test
    fun `instance id set change invalidates`() {
        cache.store(keyA, snapA)
        val added = ProviderRowSnapshotCache.keyOf(1L, 2L, listOf("i1", "i2", "i3"))
        val removed = ProviderRowSnapshotCache.keyOf(1L, 2L, listOf("i1"))
        val reordered = ProviderRowSnapshotCache.keyOf(1L, 2L, listOf("i2", "i1"))
        assertNull(cache.cached(added))
        assertNull(cache.cached(removed))
        assertNull(cache.cached(reordered))
    }

    @Test
    fun `later store replaces the cached snapshot`() {
        cache.store(keyA, snapA)
        val snapB = mapOf("i1" to ProviderRowSnapshot(loaded = true, modelCount = 7))
        cache.store(keyA, snapB)
        assertSame(snapB, cache.cached(keyA))
    }

    @Test
    fun `key is deterministic across calls`() {
        assertEquals(
            keyA,
            ProviderRowSnapshotCache.keyOf(1L, 2L, listOf("i1", "i2")),
        )
    }
}
