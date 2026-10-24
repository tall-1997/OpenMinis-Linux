package com.openminis.app.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cache exists so a dead network never empties the free lane.
 *
 * Two rules carry that: an empty remote payload is a FAILURE (never written
 * over a good cache), and the hardcoded first-install seed is retired for good
 * the moment a real fetch succeeds — otherwise a stale two-model seed would
 * keep shadowing a live 85-model catalogue.
 *
 * A null context puts the cache in memory-only mode, which is what makes these
 * runnable on the JVM without Robolectric.
 */
class FreeLaneCatalogCacheTest {

    private val key = "zen-free"

    private fun snapshot(vararg ids: String) = FreeLaneCatalogSnapshot(
        ids = ids.toList(),
        learned = FreeLaneLearned(),
        fetchedAtMs = 1_700_000_000_000L,
    )

    @Test
    fun emptyRemoteDataIsTreatedAsAFailureAndNeverOverwritesTheCache() {
        val cache = FreeLaneCatalogCache(null)
        assertTrue(cache.save(key, snapshot("alive-free")))

        val accepted = cache.save(key, FreeLaneCatalogSnapshot(emptyList(), FreeLaneLearned(), 2L))
        assertFalse("an empty catalogue must be rejected", accepted)

        val kept = cache.load(key)
        assertNotNull(kept)
        assertEquals(listOf("alive-free"), kept!!.ids)
    }

    @Test
    fun firstInstallWithNoNetworkFallsBackToTheOfflineSeed() {
        val seeded = FreeLaneCatalogCache(null).loadOrSeed(ZenFreeLane, key)
        assertTrue("first install has nothing better than the seed", seeded.isOfflineSeed)
        assertTrue(seeded.ids.isNotEmpty())
        seeded.ids.forEach { id ->
            assertTrue("seed ids must satisfy the rule: $id", ZenFreeLaneRule.isFreeLaneModelId(id))
        }
    }

    @Test
    fun loadAloneReturnsNullWhenNothingWasEverCached() {
        assertNull(FreeLaneCatalogCache(null).load(key))
    }

    @Test
    fun theOfflineSeedIsRetiredPermanentlyAfterTheFirstSuccessfulFetch() {
        val cache = FreeLaneCatalogCache(null)
        assertTrue(cache.save(key, snapshot("x-free", "y-free")))

        val live = cache.loadOrSeed(ZenFreeLane, key)
        assertFalse("a real fetch retires the seed", live.isOfflineSeed)
        assertEquals(listOf("x-free", "y-free"), live.ids)
    }

    @Test
    fun learnedEvidenceSurvivesARoundTrip() {
        val cache = FreeLaneCatalogCache(null)
        val learned = FreeLaneLearned(free = setOf("mystery-model"), paid = setOf("gpt-5.5"))
        assertTrue(
            cache.save(
                key,
                FreeLaneCatalogSnapshot(
                    ids = listOf("mystery-model", "x-free"),
                    learned = learned,
                    fetchedAtMs = 1_700_000_000_000L,
                ),
            ),
        )
        val back = cache.load(key)
        assertNotNull(back)
        assertEquals(setOf("mystery-model"), back!!.learned.free)
        assertEquals(setOf("gpt-5.5"), back.learned.paid)
    }

    @Test
    fun separateKeysDoNotShareACacheEntry() {
        val cache = FreeLaneCatalogCache(null)
        assertTrue(cache.save("instance-a", snapshot("a-free")))
        assertTrue(cache.save("instance-b", snapshot("b-free")))
        assertEquals(listOf("a-free"), cache.load("instance-a")?.ids)
        assertEquals(listOf("b-free"), cache.load("instance-b")?.ids)
    }

    @Test
    fun anExpiredSnapshotIsStillBetterThanTheSeedButMarkedStale() {
        val cache = FreeLaneCatalogCache(null)
        val old = FreeLaneCatalogSnapshot(
            ids = listOf("old-free"),
            learned = FreeLaneLearned(),
            fetchedAtMs = 1L,
        )
        assertTrue(cache.save(key, old))
        val loaded = cache.loadOrSeed(ZenFreeLane, key, nowMs = 1L + FreeLaneCatalogCache.TTL_MS + 1)
        assertFalse("a stale real catalogue still beats the hardcoded seed", loaded.isOfflineSeed)
        assertTrue("but the caller must be able to tell it is stale", loaded.isStale)
    }
}
