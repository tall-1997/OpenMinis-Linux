package com.openminis.app.provider

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.inferredMaxOutputTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Relays spell the same model a dozen ways. Exact-id matching against models.dev
 * used to drop those aliases onto provider defaults (16k output). These tests
 * lock the normalized lookup that iOS already ships.
 */
class ModelsDevIdNormalizationTest {

    @Test
    fun `normalizedModelKey strips vendor path and unifies punctuation`() {
        assertEquals("glm-5-2", ModelsDevApi.normalizedModelKey("glm-5.2"))
        assertEquals("glm-5-2", ModelsDevApi.normalizedModelKey("z-ai/glm-5.2"))
        assertEquals("glm-5-2", ModelsDevApi.normalizedModelKey("zai-org/GLM-5.2"))
        assertEquals("glm-5-2", ModelsDevApi.normalizedModelKey("accounts/fireworks/models/glm_5.2"))
        assertEquals("gpt-5-5", ModelsDevApi.normalizedModelKey("gpt-5.5"))
        assertEquals("claude-opus-4-7", ModelsDevApi.normalizedModelKey("claude-opus-4.7"))
        assertTrue(
            ModelsDevApi.normalizedModelKey("glm-5.2") !=
                ModelsDevApi.normalizedModelKey("glm-5.1"),
        )
    }

    private fun entry(
        id: String,
        ctx: Int? = 128_000,
        out: Int? = 16_384,
        effort: List<String>? = null,
    ) = ModelsDevApi.ModelDevEntry(
        id = id,
        name = id,
        family = null,
        contextWindow = ctx,
        maxOutputTokens = out,
        reasoning = effort != null,
        interleavedField = null,
        inputModalities = null,
        outputModalities = null,
        reasoningEffortValues = effort,
        releaseDate = null,
        outputCost = null,
    )

    @Test
    fun `majority vote prefers the most common effort set and its limits`() {
        val sparse = entry("glm-5.2", effort = null, out = 8_192)
        val richA = entry("glm-5.2", effort = listOf("high", "max"), out = 131_072, ctx = 200_000)
        val richB = entry("glm-5.2", effort = listOf("high", "max"), out = 131_072, ctx = 200_000)
        val other = entry("glm-5.2", effort = listOf("low", "medium", "high"), out = 32_768)
        val winner = ModelsDevApi.pickStage2Winner(listOf(sparse, other, richA, richB))
        assertEquals(listOf("high", "max"), winner!!.reasoningEffortValues)
        assertEquals(131_072, winner.maxOutputTokens)
        assertEquals(200_000, winner.contextWindow)
    }

    @Test
    fun `tie keeps first declaring candidate in scan order`() {
        val a = entry("m", effort = listOf("low"), out = 1)
        val b = entry("m", effort = listOf("high"), out = 2)
        val winner = ModelsDevApi.pickStage2Winner(listOf(a, b))
        assertEquals(listOf("low"), winner!!.reasoningEffortValues)
        assertEquals(1, winner.maxOutputTokens)
    }

    @Test
    fun `no effort declarations still return first candidate for context and output`() {
        val first = entry("m", ctx = 256_000, out = 32_768, effort = null)
        val second = entry("m", ctx = 8_000, out = 4_096, effort = null)
        val winner = ModelsDevApi.pickStage2Winner(listOf(first, second))
        assertEquals(256_000, winner!!.contextWindow)
        assertEquals(32_768, winner.maxOutputTokens)
    }

    @Test
    fun `stage-2 index matches relay-prefixed ids to catalog tails`() {
        val registry = mapOf(
            "zhipuai" to ModelsDevApi.ProviderEntry(
                id = "zhipuai",
                name = "Zhipu",
                api = null,
                models = mapOf(
                    "glm-5.2" to entry(
                        "glm-5.2",
                        ctx = 200_000,
                        out = 131_072,
                        effort = listOf("high", "max"),
                    ),
                ),
            ),
        )
        val index = ModelsDevApi.buildStage2Index(registry)
        val match = index[ModelsDevApi.normalizedModelKey("z-ai/GLM-5.2")]
        assertNotNull(match)
        assertEquals(200_000, match!!.model.contextWindow)
        assertEquals(131_072, match.model.maxOutputTokens)
        assertFalse(match.authoritative)
    }

    @Test
    fun `own provider exact id is authoritative over a sparser relay copy`() {
        val openai = entry("gpt-5.5", ctx = 1_050_000, out = 128_000, effort = listOf("low", "medium", "high"))
        val relay = entry("gpt-5.5", ctx = 32_000, out = 4_096, effort = listOf("high"))
        val registry = mapOf(
            "openai" to ModelsDevApi.ProviderEntry("openai", "OpenAI", null, mapOf("gpt-5.5" to openai)),
            "openrouter" to ModelsDevApi.ProviderEntry(
                "openrouter",
                "OpenRouter",
                null,
                mapOf("openai/gpt-5.5" to relay),
            ),
        )
        val match = ModelsDevApi.resolveDevModel(
            LLMModel("gpt-5.5", "GPT-5.5", "OpenAI"),
            registry,
        )!!
        assertTrue(match.authoritative)
        assertEquals(1_050_000, match.model.contextWindow)
        assertEquals(128_000, match.model.maxOutputTokens)
    }

    @Test
    fun `custom relay with no mapped provider still resolves via stage-2`() {
        val catalog = entry("glm-5.2", ctx = 200_000, out = 131_072, effort = listOf("high", "max"))
        val registry = mapOf(
            "zhipuai" to ModelsDevApi.ProviderEntry("zhipuai", "Zhipu", null, mapOf("glm-5.2" to catalog)),
        )
        val match = ModelsDevApi.resolveDevModel(
            LLMModel("z-ai/glm-5.2", "GLM 5.2", "MyRelay"),
            registry,
        )!!
        assertFalse(match.authoritative)
        assertEquals(200_000, match.model.contextWindow)
        assertEquals(131_072, match.model.maxOutputTokens)
        assertEquals(listOf("high", "max"), match.model.reasoningEffortValues)
    }

    @Test
    fun `family heuristic covers new-model last resort without catalog`() {
        assertEquals(128_000, inferredMaxOutputTokens("gpt-5.5"))
        assertEquals(128_000, inferredMaxOutputTokens("GPT-6免费"))
        assertEquals(128_000, inferredMaxOutputTokens("claude-opus-4.8"))
        assertEquals(64_000, inferredMaxOutputTokens("claude-sonnet-4.6"))
        assertEquals(65_536, inferredMaxOutputTokens("gemini-3-flash"))
        assertEquals(128_000, inferredMaxOutputTokens("totally-unknown-local-gguf"))
    }
}
