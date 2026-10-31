package com.openminis.app.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-image-cache-mtime-ttl] Unit tests for the 2-second in-memory TTL over
 * MinisImageFetcher's uri→mtime cache-key composition.
 *
 * The JVM probe path is deterministic: with no PRootKernel bind mounts and no
 * booted rootfs, `resolveHostPath` returns null for every candidate, so an
 * uncached compose always yields the sentinel key `"<uri>?mt=0"`. That makes
 * "cache hit" and "recompute" directly distinguishable without touching the
 * filesystem: a HIT returns whatever was seeded, a MISS recomputes the
 * sentinel.
 */
class MinisImageFetcherMtimeTtlTest {

    private val uri = "minis://attachments/__ttl_probe__.jpg"
    private val sentinel = "$uri?mt=0"

    @Before
    fun clearCache() = MinisImageFetcher.clearMtimeCache()

    @After
    fun cleanup() = MinisImageFetcher.clearMtimeCache()

    /** First compose resolves (mt=0 sentinel here) and populates the cache. */
    @Test
    fun firstCompose_populatesTheCache() {
        assertEquals(sentinel, MinisImageFetcher.composeMtimeKey(uri))
        assertEquals(1, MinisImageFetcher.mtimeCacheSize())
        assertEquals(sentinel, MinisImageFetcher.mtimeCache[uri]?.first)
    }

    /** A second compose within the TTL is served from the cache, not restated. */
    @Test
    fun secondComposeWithinTtl_isServedFromCache() {
        MinisImageFetcher.composeMtimeKey(uri)
        // Overwrite the entry with a marker key: if the second call returned a
        // freshly computed value it would be the sentinel, not the marker.
        MinisImageFetcher.mtimeCache[uri] = "marker-key" to System.currentTimeMillis()
        assertEquals("marker-key", MinisImageFetcher.composeMtimeKey(uri))
        assertEquals(1, MinisImageFetcher.mtimeCacheSize())
    }

    /** An entry older than the TTL is recomputed and the cache entry refreshed. */
    @Test
    fun staleEntry_isRecomputedAfterTtlExpiry() {
        MinisImageFetcher.mtimeCache[uri] = "stale-key" to (System.currentTimeMillis() - 2_100L)
        val key = MinisImageFetcher.composeMtimeKey(uri)
        assertEquals("stale entry must not be served", sentinel, key)
        val refreshed = MinisImageFetcher.mtimeCache[uri]
        assertTrue("cache entry must be refreshed after expiry", refreshed != null)
        assertEquals("recomputed key replaces the stale one", sentinel, refreshed?.first)
        assertTrue(
            "refresh stamps a new resolve time",
            refreshed?.second!! > System.currentTimeMillis() - 60_000L,
        )
    }

    /** Distinct uris get distinct cache entries and distinct keys. */
    @Test
    fun differentUris_getSeparateEntries() {
        val other = "minis://attachments/__other_probe__.jpg"
        assertEquals(sentinel, MinisImageFetcher.composeMtimeKey(uri))
        assertEquals("$other?mt=0", MinisImageFetcher.composeMtimeKey(other))
        assertEquals(2, MinisImageFetcher.mtimeCacheSize())
    }
}
