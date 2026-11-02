package com.openminis.app.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-credential-pool-x] Wire contract of the credential-pool backup surface
 * on `secrets.json` `ProviderSecret` entries.
 *
 * The pool's slot 0 IS the primary key, which already travels as `apiKey`;
 * `pooledKeys` therefore carries ONLY the extra keys (indices 1..n), each
 * base64-encoded with the same convention as `apiKey`. `quotaGroupId` rides
 * along so a restored instance lands in the same cooling group as its
 * siblings. Both fields are additive: packages written before them decode
 * untouched, and a single-key instance emits no pool key at all (byte-identical
 * to a pre-pool export).
 */
class BackupProviderSecretPoolWireTest {

    private fun secret(
        apiKey: String? = "cGs=", // base64("pk")
        pooledKeys: List<String> = emptyList(),
        quotaGroupId: String? = null,
    ) = BackupSecrets.ProviderSecret(
        instanceId = "i1",
        label = "L",
        providerType = "openAI",
        apiKey = apiKey,
        pooledKeys = pooledKeys,
        quotaGroupId = quotaGroupId,
    )

    /** New fields round-trip through the real BackupFormat parser. */
    @Test
    fun `pooledKeys and quotaGroupId round-trip on the wire`() {
        val s = secret(pooledKeys = listOf("azI=", "azM="), quotaGroupId = "grp-1")
        val encoded = BackupFormat.json.encodeToString(BackupSecrets.ProviderSecret.serializer(), s)
        assertTrue("pooledKeys must be present: $encoded", "\"pooledKeys\"" in encoded)
        assertTrue("quotaGroupId must be present: $encoded", "\"quotaGroupId\":\"grp-1\"" in encoded)
        assertEquals(s, BackupFormat.json.decodeFromString(BackupSecrets.ProviderSecret.serializer(), encoded))
    }

    /** A single-key instance (pool off) emits NO pool key — old bytes stay old. */
    @Test
    fun `empty pool fields are omitted from the wire`() {
        val encoded = BackupFormat.json.encodeToString(
            BackupSecrets.ProviderSecret.serializer(), secret(),
        )
        assertTrue("pooledKeys" !in encoded)
        assertTrue("quotaGroupId" !in encoded)
    }

    /** Packages written before the pool fields decode with defaults. */
    @Test
    fun `pre-pool secrets still decode`() {
        val old = """{"instanceId":"i1","label":"L","providerType":"openAI","apiKey":"cGs="}"""
        val s = BackupFormat.json.decodeFromString(BackupSecrets.ProviderSecret.serializer(), old)
        assertEquals("cGs=", s.apiKey)
        assertEquals(emptyList<String>(), s.pooledKeys)
        assertEquals(null, s.quotaGroupId)
    }

    /** A future writer may add more fields; the parse must not sink. */
    @Test
    fun `unknown future fields on a provider secret are ignored`() {
        val fut = """{"instanceId":"i1","apiKey":"cGs=","futureThing":{"x":[1]},"pooledKeys":["azI="]}"""
        val s = BackupFormat.json.decodeFromString(BackupSecrets.ProviderSecret.serializer(), fut)
        assertEquals(listOf("azI="), s.pooledKeys)
        assertEquals("cGs=", s.apiKey)
    }

    /**
     * Collection drops entries whose credentials are all empty. Pool metadata
     * does not keep an entry alive: quotaGroupId-only instances are restored
     * from `provider_config.json` (which carries quotaGroupId as ordinary
     * config), so losing the secrets.json copy loses nothing.
     */
    @Test
    fun `pool metadata does not keep a credentialless entry alive`() {
        val quotaOnly = secret(apiKey = null, quotaGroupId = "grp-1")
        assertTrue(quotaOnly.isEmpty)
        val poolOnly = secret(apiKey = null, pooledKeys = listOf("azI="))
        assertTrue(poolOnly.isEmpty)
        assertFalse(secret().isEmpty)
    }
}
