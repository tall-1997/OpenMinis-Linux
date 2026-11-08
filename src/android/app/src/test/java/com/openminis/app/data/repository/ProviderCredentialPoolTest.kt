package com.openminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * [T-credential-pool-x] Pure rotation math for the multi-account credential
 * pool: round-robin order, cooldown skipping, all-cooled fallback, and the
 * mutation invariants (legacy migration, primary-slot sync, sub-2 collapse).
 */
class ProviderCredentialPoolTest {

    // ── poolPickKey rotation ────────────────────────────────────────────

    @Test
    fun `round robin walks the pool in order`() {
        val keys = listOf("k1", "k2", "k3")
        val cursor = AtomicInteger(0)
        val seen = mutableListOf<String>()
        repeat(keys.size) {
            val (key, _) = poolPickKey(keys, cursor, { _ -> 0L }, 0L)!!
            seen += key
        }
        assertEquals(listOf("k1", "k2", "k3"), seen)
    }

    @Test
    fun `pick advances the cursor to the next slot`() {
        val keys = listOf("k1", "k2", "k3")
        val cursor = AtomicInteger(0)
        // [T-credential-pick-cas] The cursor is the raw ticket counter now —
        // it grows forever; the pick maps it onto the pool with a modulo.
        poolPickKey(keys, cursor, { _ -> 0L }, 0L)
        assertEquals(1, cursor.get())
        poolPickKey(keys, cursor, { _ -> 0L }, 0L)
        assertEquals(2, cursor.get())
        poolPickKey(keys, cursor, { _ -> 0L }, 0L)
        assertEquals(3, cursor.get())
    }

    @Test
    fun `a cooled key is skipped for the next live one`() {
        val keys = listOf("k1", "k2", "k3")
        val cursor = AtomicInteger(0)
        val until = 10_000L
        // k1 cooled until 10s in the future; now = 0.
        val cooldown = mapOf("k1" to until)
        val (key, idx) = poolPickKey(keys, cursor, { cooldown[it] ?: 0L }, 0L)!!
        assertEquals("k2", key)
        assertEquals(1, idx)
        // Next pick: k2 was already taken, k3 follows.
        val (key2, idx2) = poolPickKey(keys, cursor, { cooldown[it] ?: 0L }, 0L)!!
        assertEquals("k3", key2)
        assertEquals(2, idx2)
    }

    @Test
    fun `two cooled keys leave only the last slot`() {
        val keys = listOf("k1", "k2", "k3")
        val cursor = AtomicInteger(0)
        val cooldown = mapOf("k1" to 10_000L, "k2" to 10_000L)
        val (key, idx) = poolPickKey(keys, cursor, { cooldown[it] ?: 0L }, 0L)!!
        assertEquals("k3", key)
        assertEquals(2, idx)
    }

    @Test
    fun `expired cooldown is served again`() {
        val keys = listOf("k1", "k2")
        val cursor = AtomicInteger(0)
        // k1 cooled until 5000; now = 6000 → already expired.
        val (key, idx) = poolPickKey(keys, cursor, { if (it == "k1") 5_000L else 0L }, 6_000L)!!
        assertEquals("k1", key)
        assertEquals(0, idx)
    }

    @Test
    fun `all cooled keys return null so the caller falls back`() {
        val keys = listOf("k1", "k2")
        val cursor = AtomicInteger(0)
        val cooldown = mapOf("k1" to 10_000L, "k2" to 10_000L)
        assertNull(poolPickKey(keys, cursor, { cooldown[it] ?: 0L }, 0L))
    }

    @Test
    fun `empty pool returns null`() {
        val cursor = AtomicInteger(0)
        assertNull(poolPickKey(emptyList(), cursor, { _ -> 0L }, 0L))
    }

    @Test
    fun `single key pool always returns that key with index 0`() {
        val keys = listOf("only")
        val cursor = AtomicInteger(0)
        val (key, idx) = poolPickKey(keys, cursor, { _ -> 0L }, 0L)!!
        assertEquals("only", key)
        assertEquals(0, idx)
    }

    // ── poolAfterAdd / poolAfterRemove (store mutation invariants) ──────

    @Test
    fun `first add seeds the pool with the legacy primary key`() {
        assertEquals(listOf("k1", "k2"), poolAfterAdd(emptyList(), "k1", "k2"))
    }

    @Test
    fun `first add with no legacy key creates a single-slot pool`() {
        assertEquals(listOf("k2"), poolAfterAdd(emptyList(), null, "k2"))
    }

    @Test
    fun `duplicate add is a no-op`() {
        assertEquals(listOf("k1", "k2"), poolAfterAdd(listOf("k1", "k2"), "k1", "k2"))
    }

    @Test
    fun `add extends an existing pool`() {
        assertEquals(
            listOf("k1", "k2", "k3"),
            poolAfterAdd(listOf("k1", "k2"), "k1", "k3"),
        )
    }

    @Test
    fun `remove shrinks the pool and promotes the survivor as primary when index 0 goes`() {
        val removal = poolAfterRemove(listOf("k1", "k2", "k3"), 0)
        assertEquals(listOf("k2", "k3"), removal.keys)
        assertEquals("k2", removal.primaryOverride)
    }

    @Test
    fun `remove of a secondary slot does not shift the primary`() {
        val removal = poolAfterRemove(listOf("k1", "k2", "k3"), 1)
        assertEquals(listOf("k1", "k3"), removal.keys)
        assertNull(removal.primaryOverride)
    }

    @Test
    fun `remove down to a single key collapses the pool keeping the primary`() {
        val removal = poolAfterRemove(listOf("k1", "k2"), 1)
        assertEquals(emptyList<String>(), removal.keys)
        assertNull(removal.primaryOverride)
    }

    @Test
    fun `remove of the primary from a two-key pool promotes the survivor`() {
        val removal = poolAfterRemove(listOf("k1", "k2"), 0)
        assertEquals(emptyList<String>(), removal.keys)
        assertEquals("k2", removal.primaryOverride)
    }

    @Test
    fun `remove of an out-of-range index leaves the pool intact`() {
        val removal = poolAfterRemove(listOf("k1", "k2"), 5)
        assertEquals(listOf("k1", "k2"), removal.keys)
        assertNull(removal.primaryOverride)
    }

    // ── cooldownFor (error classification) ──────────────────────────────

    @Test
    fun `rate limit without retry-after cools for the default window`() {
        val err = com.openminis.app.data.model.LLMError.RateLimited(null, "429")
        assertEquals(
            ProviderCredentialPool.DEFAULT_COOLDOWN_MS,
            credentialCooldownFor(err),
        )
    }

    @Test
    fun `rate limit with retry-after honors and caps it`() {
        val small = com.openminis.app.data.model.LLMError.RateLimited(5, "429")
        assertEquals(5_000L, credentialCooldownFor(small))

        val huge = com.openminis.app.data.model.LLMError.RateLimited(3_600, "429")
        assertEquals(
            ProviderCredentialPool.RATE_LIMIT_MAX_COOLDOWN_MS,
            credentialCooldownFor(huge),
        )
    }

    @Test
    fun `invalid key cools for the long window`() {
        val err = com.openminis.app.data.model.LLMError.InvalidApiKey("revoked")
        assertEquals(
            ProviderCredentialPool.INVALID_KEY_COOLDOWN_MS,
            credentialCooldownFor(err),
        )
    }

    @Test
    fun `other errors do not cool the credential`() {
        val err = com.openminis.app.data.model.LLMError.TransientError("500 hiccup")
        assertNull(credentialCooldownFor(err))
    }

    // ── sanity guards on exported constants ─────────────────────────────

    @Test
    fun `cooldown windows are strictly ordered`() {
        assertNotEquals(
            ProviderCredentialPool.DEFAULT_COOLDOWN_MS,
            ProviderCredentialPool.INVALID_KEY_COOLDOWN_MS,
        )
        assertEquals(30_000L, ProviderCredentialPool.DEFAULT_COOLDOWN_MS)
        assertEquals(30 * 60_000L, ProviderCredentialPool.INVALID_KEY_COOLDOWN_MS)
        assertEquals(10 * 60_000L, ProviderCredentialPool.RATE_LIMIT_MAX_COOLDOWN_MS)
    }
}
