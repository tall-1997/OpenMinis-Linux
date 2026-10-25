package com.openminis.app.provider

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.applyUnrecognizedModelDefaults
import com.openminis.app.data.model.hasGptFamily
import com.openminis.app.data.model.inferredMaxOutputTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelAliasMatcherTest {

    private data class Cand(val id: String, val name: String)

    private fun pick(queryId: String, queryName: String, vararg ids: String): String? {
        val cands = ids.map { Cand(it, it) }
        return ModelAliasMatcher.pickBest(
            queryId,
            queryName,
            cands,
            tokensOf = { ModelAliasMatcher.tokens("${it.id} ${it.name}").toSet() },
            idOf = { it.id },
        )?.id
    }

    @Test
    fun `resolveBest handles dirty coordinator model labels`() {
        val candidates = listOf(Cand("gpt-6-astra-pro", "GPT-6 Astra Pro"), Cand("gpt-6-astra", "GPT-6 Astra"))
        assertEquals(
            "gpt-6-astra",
            ModelAliasMatcher.resolveBest("relay-name · 免费GPT-6 Astra", candidates, { it.id }, { it.name })?.id,
        )
    }

    @Test
    fun `stripNoise drops 免费 and free`() {
        assertEquals("GPT-6 Astra", ModelAliasMatcher.stripNoise("免费GPT-6 Astra"))
        assertEquals("GPT-6", ModelAliasMatcher.stripNoise("GPT-6免费"))
        assertEquals("gpt-6-astra", ModelAliasMatcher.stripNoise("gpt-6-astra-free"))
    }

    @Test
    fun `GPT-6免费 prefers astra over pro and gpt-5`() {
        assertEquals(
            "gpt-6-astra",
            pick("GPT-6免费", "GPT-6免费", "gpt-5.5", "gpt-6-astra", "gpt-6-astra-pro"),
        )
    }

    @Test
    fun `免费GPT-6 Astra prefers astra not pro`() {
        assertEquals(
            "gpt-6-astra",
            pick("relay-1", "免费GPT-6 Astra", "gpt-6-astra-pro", "gpt-6-astra", "gpt-5.5"),
        )
    }

    @Test
    fun `query with Pro picks the pro sibling`() {
        assertEquals(
            "gpt-6-astra-pro",
            pick("GPT-6 Astra Pro", "GPT-6 Astra Pro", "gpt-6-astra", "gpt-6-astra-pro"),
        )
    }

    @Test
    fun `glm-5-2 does not collapse onto glm-5-1`() {
        assertEquals(
            "glm-5.2",
            pick("GLM-5.2免费", "GLM-5.2免费", "glm-5.1", "glm-5.2"),
        )
    }

    @Test
    fun `brand-only GPT免费 does not match`() {
        assertNull(pick("GPT免费", "GPT免费", "gpt-4o", "gpt-5.5", "gpt-6-astra"))
    }

    @Test
    fun `DataLearner pickSearchHit fuzzy-matches dirty relay names`() {
        val hits = listOf(
            DataLearnerParser.SearchHit("gpt-6-astra-pro", "GPT-6 Astra Pro"),
            DataLearnerParser.SearchHit("gpt-6-astra", "GPT-6 Astra"),
            DataLearnerParser.SearchHit("gpt-5-5", "GPT-5.5"),
        )
        assertEquals(
            "gpt-6-astra",
            DataLearnerParser.pickSearchHit("gpt-6免费", "GPT-6免费", hits)?.modelCode,
        )
        assertEquals(
            "gpt-6-astra",
            DataLearnerParser.pickSearchHit("relay-x", "免费GPT-6 Astra", hits)?.modelCode,
        )
    }

    @Test
    fun `searchQueries add noise-stripped display name`() {
        val q = DataLearnerParser.searchQueries("m-1", "免费GPT-6 Astra")
        assertTrue(q.contains("免费GPT-6 Astra"))
        assertTrue(q.contains("GPT-6 Astra"))
    }

    @Test
    fun `resolveDevModel stage-3 fuzzy hits catalog by 字 overlap`() {
        val astra = ModelsDevApi.ModelDevEntry(
            id = "gpt-6-astra",
            name = "GPT-6 Astra",
            family = "gpt",
            contextWindow = 1_050_000,
            maxOutputTokens = 128_000,
            reasoning = true,
            interleavedField = null,
            inputModalities = listOf("text"),
            outputModalities = listOf("text"),
            reasoningEffortValues = listOf("low", "medium", "high", "xhigh", "max"),
            releaseDate = null,
            outputCost = null,
        )
        val gpt55 = astra.copy(id = "gpt-5.5", name = "GPT-5.5", contextWindow = 400_000)
        val registry = mapOf(
            "openai" to ModelsDevApi.ProviderEntry(
                "openai",
                "OpenAI",
                null,
                mapOf("gpt-6-astra" to astra, "gpt-5.5" to gpt55),
            ),
        )
        val match = ModelsDevApi.resolveDevModel(
            LLMModel("GPT-6免费", "GPT-6免费", "MyRelay"),
            registry,
        )!!
        assertEquals("gpt-6-astra", match.model.id)
        assertEquals(1_050_000, match.model.contextWindow)
        assertEquals(128_000, match.model.maxOutputTokens)
        assertFalse(match.authoritative)
    }

    @Test
    fun `family heuristic covers gpt-6 even with 免费 in the id`() {
        assertEquals(128_000, inferredMaxOutputTokens("GPT-6免费"))
        assertEquals(128_000, inferredMaxOutputTokens("relay-1", "免费GPT-6 Astra"))
        assertTrue(hasGptFamily("免费gpt-6 astra", 6))
        assertFalse(hasGptFamily("gpt-60", 6))
        assertEquals(128_000, inferredMaxOutputTokens("totally-unknown-local-gguf"))
    }

    @Test
    fun `unrecognized id gets 256k 128k thinking max and text`() {
        val m = applyUnrecognizedModelDefaults(LLMModel("llama3.1:8b", "Local Llama", "Ollama"))
        assertEquals(256_000, m.contextWindow)
        assertEquals(128_000, m.maxOutputTokens)
        assertEquals(true, m.supportsReasoning)
        assertEquals(listOf("low", "medium", "high", "xhigh", "max"), m.reasoningEffortValues)
        assertEquals(listOf("text"), m.inputModalities)
        assertEquals(listOf("text"), m.outputModalities)
        val claude = applyUnrecognizedModelDefaults(
            LLMModel("claude-opus-4-8", "Claude Opus 4.8", "Anthropic"),
        )
        assertEquals(256_000, claude.contextWindow)
        assertEquals(128_000, claude.maxOutputTokens)
        assertEquals(true, claude.supportsReasoning)
        val catalogued = applyUnrecognizedModelDefaults(
            LLMModel(
                id = "gpt-5.4",
                displayName = "GPT-5.4",
                provider = "OpenAI",
                contextWindow = 400_000,
                maxOutputTokens = 128_000,
                supportsReasoning = true,
                reasoningEffortValues = listOf("low", "medium", "high"),
            ),
        )
        assertEquals(400_000, catalogued.contextWindow)
        assertEquals(listOf("low", "medium", "high"), catalogued.reasoningEffortValues)
    }
}
