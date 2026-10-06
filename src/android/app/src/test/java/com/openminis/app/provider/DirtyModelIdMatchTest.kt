package com.openminis.app.provider

import com.openminis.app.data.model.LLMModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-modelsdev-relay-noise-catalog-driven] Relay stations append markers to model
 * ids — `kimi-k3-oc`, `grok4.6破甲`, `gpt-5.5-vx`, `qwen3-max尊享版`. Before this
 * rule they fell through to hardcoded provider defaults instead of resolving to
 * the catalog entry, because nothing in the chain tolerated a dirty tail.
 *
 * The fix is data-driven rather than a word list: the catalog's own vocabulary
 * (every alphanumeric token in every catalog id) DEFINES which words belong to a
 * real model. Across all 8389 models.dev ids not one token falls outside that
 * 895-word vocabulary, so a trailing token the catalog never uses is relay noise
 * and may be trimmed — while `free`, `fast`, `thinking`, `turbo`, `plus`,
 * `latest` and `max`, which ARE real ids, are always preserved.
 *
 * The corpus below deliberately contains real catalog variants (`k3-fast`,
 * `3-flash-lite`, `qwen3-max`) so a regression to word-list stripping fails here.
 */
class DirtyModelIdMatchTest {

    private fun entry(
        id: String,
        name: String = id,
        out: Int = 131_072,
        effort: List<String>? = listOf("low", "medium", "high"),
    ) = ModelsDevApi.ModelDevEntry(
        id = id,
        name = name,
        family = null,
        contextWindow = 1_048_576,
        maxOutputTokens = out,
        reasoning = true,
        interleavedField = null,
        inputModalities = null,
        outputModalities = null,
        reasoningEffortValues = effort,
        releaseDate = null,
        outputCost = null,
    )

    /** A miniature registry holding the exact catalog spellings the rules key on. */
    private val registry = mapOf(
        "moonshotai" to ModelsDevApi.ProviderEntry(
            "moonshotai", "Moonshot", null,
            mapOf(
                "kimi-k3" to entry("kimi-k3", "Kimi K3", out = 131_072),
                "kimi-k3-fast" to entry("kimi-k3-fast", "Kimi K3 Fast", out = 131_072),
                "kimi-k2.5-free" to entry("kimi-k2.5-free", "Kimi K2.5 (Free)", out = 65_536),
            ),
        ),
        "xai" to ModelsDevApi.ProviderEntry(
            "xai", "xAI", null,
            mapOf(
                "grok-4.6" to entry("grok-4.6", "Grok 4.6", out = 500_000),
                "grok-4-fast-reasoning" to entry(
                    "grok-4-fast-reasoning",
                    "Grok 4 Fast Reasoning",
                    out = 500_000,
                ),
            ),
        ),
        "google" to ModelsDevApi.ProviderEntry(
            "google", "Google", null,
            mapOf(
                "gemini-3-flash" to entry("gemini-3-flash", "Gemini 3 Flash", out = 65_536),
                "gemini-3-flash-lite-preview" to entry(
                    "gemini-3-flash-lite-preview",
                    "Gemini 3 Flash Lite Preview",
                    out = 65_536,
                ),
            ),
        ),
        "alibaba" to ModelsDevApi.ProviderEntry(
            "alibaba", "Alibaba", null,
            mapOf(
                "qwen3-max" to entry("qwen3-max", "Qwen3 Max", out = 32_768),
                "qwen3-max-thinking" to entry("qwen3-max-thinking", "Qwen3 Max Thinking", out = 32_768),
                "qwen3.6-plus-free" to entry("qwen3.6-plus-free", "Qwen3.6 Plus Free", out = 32_768),
                "glm-5-turbo" to entry("glm-5-turbo", "GLM-5 Turbo", out = 131_072),
            ),
        ),
        "openai" to ModelsDevApi.ProviderEntry(
            "openai", "OpenAI", null,
            mapOf(
                "gpt-5.5" to entry("gpt-5.5", "GPT-5.5", out = 128_000),
                "gpt-5.5-fast" to entry("gpt-5.5-fast", "GPT-5.5 Fast", out = 128_000),
            ),
        ),
        "deepseek" to ModelsDevApi.ProviderEntry(
            "deepseek", "DeepSeek", null,
            mapOf(
                // Display name carries a DIFFERENT version than the id — the trap
                // that let `deepseek-v4` inherit a 393216 output cap.
                "deepseek-flash" to entry("deepseek-flash", "DeepSeek V4.1 Flash", out = 393_216),
                "deepseek-v4" to entry("deepseek-v4", "DeepSeek V4", out = 131_072),
            ),
        ),
        "llmgateway" to ModelsDevApi.ProviderEntry(
            "llmgateway", "LLM Gateway", null,
            mapOf("custom" to entry("custom", "Custom Model", out = 8_192)),
        ),
    )

    private fun resolve(id: String, name: String = id) =
        ModelsDevApi.resolveDevModel(LLMModel(id, name, "MyRelay"), registry)

    // ---- the reported failures ------------------------------------------

    @Test
    fun `kimi-k3-oc resolves to kimi-k3 instead of falling back to defaults`() {
        val match = resolve("kimi-k3-oc")!!
        assertEquals("kimi-k3", match.model.id)
        assertEquals(131_072, match.model.maxOutputTokens)
        assertFalse(match.authoritative)
    }

    @Test
    fun `grok dirty cjk suffix normalizes straight onto the catalog id`() {
        assertEquals("grok-4-6", ModelsDevApi.normalizedModelKey("grok4.6破甲"))
        val match = resolve("grok4.6破甲", "grok4.6破甲")!!
        assertEquals("grok-4.6", match.model.id)
        assertEquals(500_000, match.model.maxOutputTokens)
    }

    // ---- the same rules across other families and other marker styles ----

    @Test
    fun `ascii relay suffixes trim for every family, not just the reported two`() {
        assertEquals("gpt-5.5", resolve("gpt-5.5-vx")!!.model.id)
        assertEquals("qwen3-max", resolve("qwen3-max-oc")!!.model.id)
        assertEquals("qwen3-max", resolve("qwen3-max尊享版")!!.model.id)
        assertEquals("grok-4.6", resolve("grok-4.6-vx")!!.model.id)
        assertEquals("kimi-k3", resolve("kimi-k3-内部版")!!.model.id)
    }

    @Test
    fun `non-ascii relay decoration is noise in any position`() {
        assertEquals("grok-4-6", ModelsDevApi.normalizedModelKey("grok4.6破甲"))
        assertEquals("qwen-3-max", ModelsDevApi.normalizedModelKey("qwen3-max尊享版"))
        assertEquals("glm-5-2", ModelsDevApi.normalizedModelKey("glm-5.2白嫖"))
    }

    // ---- real catalog variants must NOT be trimmed ------------------------

    @Test
    fun `real catalog suffixes survive because the catalog uses those words`() {
        assertEquals("kimi-k3-fast", resolve("kimi-k3-fast")!!.model.id)
        assertEquals("gpt-5.5-fast", resolve("gpt-5.5-fast")!!.model.id)
        assertEquals("qwen3-max-thinking", resolve("qwen3-max-thinking")!!.model.id)
        assertEquals("qwen3.6-plus-free", resolve("qwen3.6-plus-free")!!.model.id)
        assertEquals("glm-5-turbo", resolve("glm-5-turbo")!!.model.id)
        assertEquals("kimi-k2.5-free", resolve("kimi-k2.5-free")!!.model.id)
    }

    @Test
    fun `dirty tail on a real variant still keeps the variant`() {
        // `gemini-3-flash-lite-preview` exists, so nothing may be trimmed.
        assertEquals("gemini-3-flash-lite-preview", resolve("gemini-3-flash-lite-preview")!!.model.id)
        // Only the relay marker goes; the `lite` variant stays.
        assertEquals("gemini-3-flash-lite-preview", resolve("gemini-3-flash-lite-preview-oc")!!.model.id)
    }

    // ---- regressions the rules must not introduce ------------------------

    @Test
    fun `version mismatch is rejected even when the name matches the version`() {
        // `deepseek-flash`'s display name is "DeepSeek V4.1 Flash".
        assertEquals("deepseek-v4", resolve("deepseek-v4", "DeepSeek V4")!!.model.id)
        assertNotNull(resolve("deepseek-v4", "DeepSeek V4"))
        assertEquals(
            131_072,
            resolve("deepseek-v4", "DeepSeek V4")!!.model.maxOutputTokens,
        )
    }

    @Test
    fun `distinct families never collapse onto each other`() {
        assertEquals("grok-4.6", resolve("grok-4.6")!!.model.id)
        // A trailing token the catalog never uses is trimmed, so this one lands
        // inside its own family rather than crossing into another one.
        resolve("gemini-3-flash-1")?.let {
            assertTrue(it.model.id.startsWith("gemini-3-flash"))
        }
        // A dirty tail cannot rescue an id whose body is unknown.
        assertNull(resolve("kimi-k9-oc"))
    }

    @Test
    fun `local runtime ids never inherit catalog limits`() {
        assertTrue(ModelsDevApi.isLocalModelId("llama3.1:8b"))
        assertTrue(ModelsDevApi.isLocalModelId("qwen2.5:7b-instruct-q4"))
        assertTrue(ModelsDevApi.isLocalModelId("models/llama-3.1-8b-Q4_K_M.gguf"))
        assertFalse(ModelsDevApi.isLocalModelId("grok-4.6"))
        // Normalization would otherwise collide `llama3.1:8b` with llama-3.1-8b.
        assertEquals("llama-3-1-8-b", ModelsDevApi.normalizedModelKey("llama3.1:8b"))
        assertNull(resolve("llama3.1:8b", "Local Llama"))
    }

    @Test
    fun `a brand-only name cannot borrow another model's limits`() {
        // Token `model` alone used to satisfy the acceptance bar and hand this
        // query llmgateway/custom's 8k output cap.
        assertNull(resolve("my-own-model-x", "My Own Model"))
        assertNull(resolve("GPT免费", "GPT免费"))
    }

    @Test
    fun `stage-2 vocabulary is derived from the loaded registry`() {
        val vocab = ModelsDevApi.buildCatalogVocabulary(registry)
        assertTrue("fast" in vocab)
        assertTrue("thinking" in vocab)
        assertTrue("turbo" in vocab)
        assertTrue("free" in vocab)
        assertFalse("oc" in vocab)
        assertFalse("vx" in vocab)
        assertFalse("破甲" in vocab)
    }
}