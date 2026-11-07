package com.openminis.app.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM tests for the #283 stable-identity key slots: the pure migration pickers
 * and key construction. Context-backed paths stay untested here (no Robolectric
 * in this module); the pickers carry the migration semantics.
 */
class OnlinePluginStoreTest {
    private fun plugin(baseUrl: String) = PluginRegistry.RemotePlugin(
        id = "dsh-our-free-model",
        name = "Dsh",
        description = "",
        icon = "",
        authType = "api_key",
        authHeader = "Authorization",
        baseUrl = baseUrl,
        tools = listOf(PluginRegistry.RemoteTool("read", "read", "GET", "/v1", emptyList())),
    )

    @Test fun `api key slot survives catalog contract changes`() {
        val original = plugin("https://api.example.com/v1/")
        val reworded = original.copy(
            tools = listOf(PluginRegistry.RemoteTool("read", "READ SUMMARY CHANGED", "GET", "/v1", emptyList())),
        )
        assertNotEquals(original.configurationKey(), reworded.configurationKey())
        // Same id → same recovery view, regardless of the fingerprint drift above.
        assertEquals(
            OnlinePluginStore.legacyApiKey(mapOf(original.configurationKey() to "sk-orphan"), "dsh-our-free-model"),
            OnlinePluginStore.legacyApiKey(mapOf(reworded.configurationKey() to "sk-orphan"), "dsh-our-free-model"),
        )
    }

    @Test fun `legacy api key is recovered from fingerprint orphan slot`() {
        val orphan = plugin("https://api.example.com/v1/").configurationKey()
        assertEquals("sk-orphan", OnlinePluginStore.legacyApiKey(mapOf(orphan to "sk-orphan"), "dsh-our-free-model"))
    }

    @Test fun `stable key slot is never picked as legacy`() {
        val stableKey = connectorScopePrefix("dsh-our-free-model") + "apikey"
        assertNull(OnlinePluginStore.legacyApiKey(mapOf(stableKey to "sk-current"), "dsh-our-free-model"))
    }

    @Test fun `migration never crosses connector scopes`() {
        val otherScope = connectorScopePrefix("agent2api") + "deadbeef"
        assertNull(OnlinePluginStore.legacyApiKey(mapOf(otherScope to "sk-other"), "dsh-our-free-model"))
    }

    @Test fun `blank legacy values are skipped`() {
        val orphan = plugin("https://api.example.com/v1/").configurationKey()
        assertNull(OnlinePluginStore.legacyApiKey(mapOf(orphan to ""), "dsh-our-free-model"))
        assertNull(OnlinePluginStore.legacyApiKey(mapOf(orphan to "   "), "dsh-our-free-model"))
        assertEquals(
            "sk-real",
            OnlinePluginStore.legacyApiKey(mapOf(orphan to "", (orphan + "2") to "sk-real"), "dsh-our-free-model"),
        )
    }
}
