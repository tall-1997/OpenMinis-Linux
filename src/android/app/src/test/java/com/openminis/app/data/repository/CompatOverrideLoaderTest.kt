package com.openminis.app.data.repository

import com.openminis.app.data.model.ModelEntry
import com.openminis.app.provider.compat.CacheControlFormat
import com.openminis.app.provider.compat.CompatFlags
import com.openminis.app.provider.compat.CompatResolver
import com.openminis.app.provider.compat.MaxTokensField
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-compat-flags-persist] Read side of the persisted per-model compat overlay:
 * JSON round-trip fidelity of the [CompatFlags] blob, decode degradation for
 * corrupt blobs, and the load-path sync into [CompatResolver] (override wins,
 * stale ids fall back to the family default).
 */
class CompatOverrideLoaderTest {

    /** Same settings as the provider store's Json instance. */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun entry(modelId: String, compatJson: String?): ModelEntry =
        ModelEntry(
            providerInstanceId = "inst-1",
            baseModel = com.openminis.app.data.model.LLMModel(
                id = modelId,
                displayName = modelId,
                provider = "openAI",
                inputModalities = listOf("text"),
            ),
            compatJson = compatJson,
        )

    // ── JSON round-trip ─────────────────────────────────────────────────────

    @Test
    fun `compat flags encode and decode round-trip`() {
        val flags = CompatFlags(
            maxTokensField = MaxTokensField.MAX_TOKENS,
            supportsUsageInStreaming = false,
            cacheControlFormat = CacheControlFormat.ANTHROPIC_EPHEMERAL,
        )
        val encoded = json.encodeToString(CompatFlags.serializer(), flags)
        assertEquals(flags, json.decodeFromString(CompatFlags.serializer(), encoded))
    }

    @Test
    fun `missing keys decode to family defaults`() {
        val decoded = json.decodeFromString(CompatFlags.serializer(), """{"maxTokensField":"MAX_TOKENS"}""")
        assertEquals(MaxTokensField.MAX_TOKENS, decoded.maxTokensField)
        assertTrue(decoded.supportsUsageInStreaming)
        assertEquals(CacheControlFormat.NONE, decoded.cacheControlFormat)
    }

    @Test
    fun `unknown keys from a newer build are ignored`() {
        val decoded = json.decodeFromString(
            CompatFlags.serializer(),
            """{"maxTokensField":"MAX_TOKENS","futureFlag":true}""",
        )
        assertEquals(MaxTokensField.MAX_TOKENS, decoded.maxTokensField)
    }

    // ── decode degradation ──────────────────────────────────────────────────

    @Test
    fun `null blank and corrupt blobs degrade to no overlay`() {
        assertNull(CompatOverrideLoader.decodeCompatFlags(json, null))
        assertNull(CompatOverrideLoader.decodeCompatFlags(json, ""))
        assertNull(CompatOverrideLoader.decodeCompatFlags(json, "   "))
        assertNull(CompatOverrideLoader.decodeCompatFlags(json, "{not-json"))
    }

    // ── load-path sync into the resolver ────────────────────────────────────

    @Test
    fun `applyCompatOverrides feeds the resolver for the exact model id`() {
        CompatResolver.clearAllOverrides()
        try {
            val flags = CompatFlags(maxTokensField = MaxTokensField.MAX_TOKENS, supportsUsageInStreaming = false)
            val blob = json.encodeToString(CompatFlags.serializer(), flags)
            CompatOverrideLoader.applyCompatOverrides(
                json,
                listOf(entry("relay-model", blob), entry("plain-model", null)),
            )
            // Overridden model bypasses family detection entirely (GENERIC endpoint).
            assertEquals(flags, CompatResolver.forBasePath("https://api.openai.com/v1", "relay-model"))
            // Sibling model without a blob keeps the family table.
            assertEquals(
                CompatResolver.Family.GENERIC.flags,
                CompatResolver.forBasePath("https://api.openai.com/v1", "plain-model"),
            )
        } finally {
            CompatResolver.clearAllOverrides()
        }
    }

    @Test
    fun `overlay dropped from the store falls back to the family default`() {
        CompatResolver.clearAllOverrides()
        try {
            CompatResolver.override("stale-model", CompatFlags(maxTokensField = MaxTokensField.MAX_TOKENS))
            assertEquals(
                MaxTokensField.MAX_TOKENS,
                CompatResolver.forBasePath("https://api.openai.com/v1", "stale-model").maxTokensField,
            )
            // A load that no longer carries the blob retires the stale overlay.
            CompatOverrideLoader.applyCompatOverrides(json, listOf(entry("other-model", null)))
            assertEquals(
                MaxTokensField.MAX_COMPLETION_TOKENS,
                CompatResolver.forBasePath("https://api.openai.com/v1", "stale-model").maxTokensField,
            )
        } finally {
            CompatResolver.clearAllOverrides()
        }
    }

    @Test
    fun `corrupt blob keeps the family default instead of failing the load`() {
        CompatResolver.clearAllOverrides()
        try {
            CompatOverrideLoader.applyCompatOverrides(json, listOf(entry("relay-model", "{not-json")))
            assertEquals(
                CompatResolver.Family.GENERIC.flags,
                CompatResolver.forBasePath("https://api.openai.com/v1", "relay-model"),
            )
        } finally {
            CompatResolver.clearAllOverrides()
        }
    }
}
