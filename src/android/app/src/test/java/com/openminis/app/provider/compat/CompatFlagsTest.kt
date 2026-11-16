package com.openminis.app.provider.compat

import com.openminis.app.data.model.BailianEndpoint
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.openai.OpenAIProvider

/**
 * [T-compat-flags] Pins for the declarative compat switch table
 * (pi ai/src/providers/compat-schema.ts:196-367 port).
 *
 * Three layers are pinned here:
 *  1. family-table resolution (per provider family defaults),
 *  2. the per-model override overlay (pi models.json `compat` equivalent),
 *  3. WIRE-LEVEL GOLDEN: for every provider family the request body produced by
 *     the two wired branches ([OpenAIRequestBodies.buildRequestBody]) is
 *     byte-identical to what the pre-flag `isOpenRouter || isDashScope` /
 *     `!isOpenRouter` branches emitted. This is the behaviour-conservation pin
 *     the migration requires before more branches move onto the table.
 */
class CompatFlagsTest {

    // ------------------------------------------------------------------
    // 1. Default-table (family) resolution
    // ------------------------------------------------------------------

    @Test
    fun `generic family is the default for plain and relay endpoints`() {
        for (base in listOf(
            "https://api.openai.com/v1",
            "https://api.mistral.ai/v1",
            "https://api.x.ai/v1",
            "https://example.invalid/v1",
            "https://api.venice.ai/v1",
            "https://ark.cn-beijing.volces.com/v3",
        )) {
            assertEquals(FamilyCase(base, CompatResolver.Family.GENERIC), CompatResolver.familyOf(base).let { FamilyCase(base, it) })
            val flags = CompatResolver.forBasePath(base)
            assertEquals(MaxTokensField.MAX_COMPLETION_TOKENS, flags.maxTokensField)
            assertTrue(flags.supportsUsageInStreaming)
            assertEquals(CacheControlFormat.NONE, flags.cacheControlFormat)
        }
    }

    @Test
    fun `openrouter family uses legacy max_tokens and no usage streaming`() {
        val flags = CompatResolver.forBasePath("https://openrouter.ai/api/v1")
        assertEquals(CompatResolver.Family.OPENROUTER, CompatResolver.familyOf("https://openrouter.ai/api/v1"))
        assertEquals(MaxTokensField.MAX_TOKENS, flags.maxTokensField)
        assertFalse(flags.supportsUsageInStreaming)
    }

    @Test
    fun `dashscope family covers legacy dashscope and maas hosts`() {
        for (base in listOf(
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "https://x.maas.aliyuncs.com/v1",
        )) {
            assertTrue(BailianEndpoint.matches(base))
            val flags = CompatResolver.forBasePath(base)
            assertEquals(MaxTokensField.MAX_TOKENS, flags.maxTokensField)
            assertTrue(flags.supportsUsageInStreaming)
        }
    }

    // ------------------------------------------------------------------
    // 2. Resolver-equals-legacy-predicate equivalence (the migration pin)
    // ------------------------------------------------------------------

    /** Legacy predicates transcribed from the pre-flag branches — the oracle. */
    private fun legacyMaxTokens(path: String) =
        if (path.contains("openrouter.ai") || BailianEndpoint.matches(path)) "max_tokens"
        else "max_completion_tokens"

    private fun legacyUsageStreaming(path: String) = !path.contains("openrouter.ai")

    @Test
    fun `resolver output equals legacy predicates across the endpoint matrix`() {
        val paths = listOf(
            "https://api.openai.com/v1",
            "https://openrouter.ai/api/v1",
            "https://openrouter.ai",  // substring predicate, no path
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "https://partner.maas.aliyuncs.com/v1",
            "https://api.mistral.ai/v1",
            "https://api.x.ai/v1",
            "https://relay.example.com/v1",
            "https://x.maas.aliyuncs.com.evil.example/v1", // host-scoped: NOT Bailian
            "https://not-openrouter.ai.example.com/v1",    // substring false positive kept
        )
        for (path in paths) {
            val flags = CompatResolver.forBasePath(path)
            assertEquals(
                "maxTokensField mismatch for $path",
                legacyMaxTokens(path),
                flags.maxTokensField.wireName,
            )
            assertEquals(
                "supportsUsageInStreaming mismatch for $path",
                legacyUsageStreaming(path),
                flags.supportsUsageInStreaming,
            )
        }
    }

    // ------------------------------------------------------------------
    // 3. Per-model override overlay
    // ------------------------------------------------------------------

    @Test
    fun `override wins for the exact model id only`() {
        CompatResolver.clearAllOverrides()
        try {
            val custom = CompatFlags(maxTokensField = MaxTokensField.MAX_TOKENS, supportsUsageInStreaming = false)
            CompatResolver.override("gpt-5.6", custom)
            // Overridden model on a GENERIC endpoint reads the overlay.
            assertEquals(custom, CompatResolver.forBasePath("https://api.openai.com/v1", "gpt-5.6"))
            // A sibling model on the SAME endpoint still reads the family table.
            assertEquals(
                CompatResolver.Family.GENERIC.flags,
                CompatResolver.forBasePath("https://api.openai.com/v1", "gpt-5.5"),
            )
            // Null model id never sees the overlay.
            assertEquals(
                CompatResolver.Family.GENERIC.flags,
                CompatResolver.forBasePath("https://api.openai.com/v1"),
            )
        } finally {
            CompatResolver.clearAllOverrides()
        }
    }

    @Test
    fun `clearOverride restores the family default`() {
        CompatResolver.clearAllOverrides()
        try {
            CompatResolver.override("m-1", CompatFlags(maxTokensField = MaxTokensField.MAX_TOKENS))
            assertEquals(MaxTokensField.MAX_TOKENS, CompatResolver.forBasePath("https://api.openai.com/v1", "m-1").maxTokensField)
            CompatResolver.clearOverride("m-1")
            assertEquals(MaxTokensField.MAX_COMPLETION_TOKENS, CompatResolver.forBasePath("https://api.openai.com/v1", "m-1").maxTokensField)
        } finally {
            CompatResolver.clearAllOverrides()
        }
    }

    // ------------------------------------------------------------------
    // 4. Wire-level golden: the two wired branches are byte-identical
    // ------------------------------------------------------------------

    private fun model(id: String) = LLMModel(
        id = id,
        displayName = id,
        provider = "test",
        inputModalities = listOf("text"),
    )

    private fun chatBody(basePath: String, modelId: String, stream: Boolean): JSONObject {
        val provider = OpenAIProvider(
            apiKey = "test-key",
            model = model(modelId),
            basePath = basePath,
        )
        return provider.buildRequestBody(
            messages = listOf(LLMTestFactory.userMessage("hi")),
            systemPrompt = null,
            maxTokens = 2048,
            stream = stream,
            temperature = null,
            imageParts = emptyList(),
        )
    }

    @Test
    fun `wire golden - max tokens key matches legacy branch per endpoint`() {
        // (endpoint, model, expected key) — transcribed from the pre-flag branch.
        val cases = listOf(
            "https://api.openai.com/v1" to "max_completion_tokens",
            "https://openrouter.ai/api/v1" to "max_tokens",
            "https://dashscope.aliyuncs.com/compatible-mode/v1" to "max_tokens",
            "https://partner.maas.aliyuncs.com/v1" to "max_tokens",
            "https://api.mistral.ai/v1" to "max_completion_tokens",
            "https://api.x.ai/v1" to "max_completion_tokens",
        )
        for ((base, expectedKey) in cases) {
            val body = chatBody(base, "wire-model", stream = false)
            assertTrue("$base must carry $expectedKey", body.has(expectedKey))
            assertEquals(2048, body.getInt(expectedKey))
            val other = if (expectedKey == "max_tokens") "max_completion_tokens" else "max_tokens"
            assertFalse("$base must not carry the other key $other", body.has(other))
        }
    }

    @Test
    fun `wire golden - stream_options presence matches legacy branch per endpoint`() {
        // OpenRouter: no stream_options (legacy !isOpenRouter gate).
        assertFalse(chatBody("https://openrouter.ai/api/v1", "wire-model", stream = true).has("stream_options"))
        // Every other family: include_usage present.
        for (base in listOf(
            "https://api.openai.com/v1",
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "https://api.mistral.ai/v1",
        )) {
            val body = chatBody(base, "wire-model", stream = true)
            assertTrue("$base must carry stream_options", body.has("stream_options"))
            assertTrue(body.getJSONObject("stream_options").getBoolean("include_usage"))
        }
        // Non-streaming requests never carry it on any family.
        assertFalse(chatBody("https://api.openai.com/v1", "wire-model", stream = false).has("stream_options"))
    }

    @Test
    fun `wire golden - per-model override reaches the live request body`() {
        CompatResolver.clearAllOverrides()
        try {
            // api.openai.com normally emits max_completion_tokens…
            assertTrue(chatBody("https://api.openai.com/v1", "relay-model", stream = false).has("max_completion_tokens"))
            // …and an override flips the very same call site to the legacy key.
            CompatResolver.override(
                "relay-model",
                CompatFlags(maxTokensField = MaxTokensField.MAX_TOKENS, supportsUsageInStreaming = false),
            )
            val body = chatBody("https://api.openai.com/v1", "relay-model", stream = true)
            assertTrue(body.has("max_tokens"))
            assertFalse(body.has("stream_options"))
        } finally {
            CompatResolver.clearAllOverrides()
        }
    }
}

/** Tiny value holder so the family assertion reads as one comparison. */
private data class FamilyCase(val base: String, val family: CompatResolver.Family)

/** Minimal history factory shared by the wire-level golden cases. */
private object LLMTestFactory {
    fun userMessage(text: String) = com.openminis.app.data.model.LLMMessage(
        role = com.openminis.app.data.model.LLMMessage.Role.USER,
        content = text,
    )
}
