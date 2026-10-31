package com.openminis.app.provider

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-provider-factory-lru] The cache cap used to be enforced with
 * `cache.clear()` — all 64 memoized providers (each with its own
 * OkHttpClient) dropped at once. These tests pin the LRU contract: the cap is
 * respected (≤ [ProviderFactory.CACHE_CAP] entries) and eviction takes the
 * LEAST-recently-ACCESSED entry, not the whole map.
 */
class ProviderFactoryLruTest {

    private fun instance(id: String) = ProviderInstance(
        id = id,
        label = id,
        providerType = ProviderType.anthropic,
        credentialType = ProviderCredential.apiKey,
    )

    private fun create(id: String) {
        // apiKey/basePath shape only matters for cache-key uniqueness; the
        // anthropic direct path (no basePath) builds without touching network.
        ProviderFactory.create(instance(id), "key-$id", LLMModel.claudeHaiku45)
    }

    private fun cachedKeys(): Set<String> = ProviderFactory.cachedKeysForTest()

    private fun isCached(id: String): Boolean = cachedKeys().any { it.startsWith("$id|") }

    @After
    fun tearDown() {
        ProviderFactory.invalidateAll()
    }

    @Test
    fun `cache size never exceeds the cap`() {
        repeat(ProviderFactory.CACHE_CAP * 2) { i -> create("inst-%03d".format(i)) }
        assertTrue(
            "expected <= ${ProviderFactory.CACHE_CAP}, got ${cachedKeys().size}",
            cachedKeys().size <= ProviderFactory.CACHE_CAP,
        )
        // The most recent insert survived.
        assertTrue(isCached("inst-%03d".format(ProviderFactory.CACHE_CAP * 2 - 1)))
    }

    @Test
    fun `eviction takes the oldest entry, not the whole map`() {
        repeat(ProviderFactory.CACHE_CAP) { i -> create("inst-%03d".format(i)) }
        // Cache is exactly at cap; the first entry is still warm.
        assertTrue(isCached("inst-000"))
        assertFalse(isCached("inst-%03d".format(ProviderFactory.CACHE_CAP)))

        // One more insert evicts exactly the least-recently-used entry.
        create("inst-%03d".format(ProviderFactory.CACHE_CAP))
        assertFalse("oldest entry should have been evicted", isCached("inst-000"))
        assertTrue(isCached("inst-001"))
        assertTrue(cachedKeys().size <= ProviderFactory.CACHE_CAP)
    }

    @Test
    fun `a hit refreshes recency and protects the entry from eviction`() {
        repeat(ProviderFactory.CACHE_CAP) { i -> create("inst-%03d".format(i)) }
        // Re-access the oldest entry — its lastAccess becomes the newest.
        create("inst-000")
        create("inst-%03d".format(ProviderFactory.CACHE_CAP))

        assertTrue("refreshed entry must survive", isCached("inst-000"))
        assertFalse("next-oldest entry is the LRU victim", isCached("inst-001"))
    }
}
