package com.openminis.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelEntryDefaultsTest {

    @Test
    fun `id with no params gets 256k 128k thinking max and text`() {
        val entry = ModelEntry(
            providerInstanceId = "p",
            baseModel = LLMModel("relay-foo-v2", "Foo V2", "Relay"),
        )
        // [T-custom-model-enrich-wiring] entry.model is the READ path: it
        // stamps unknown-model defaults at read time, so runtime behavior is
        // unchanged even though the persisted baseModel carries nulls.
        val m = entry.model
        assertEquals(256_000, m.contextWindow)
        assertEquals(128_000, m.maxOutputTokens)
        assertEquals(true, m.supportsReasoning)
        assertEquals(listOf("low", "medium", "high", "xhigh", "max"), m.reasoningEffortValues)
        assertEquals(listOf("text"), m.inputModalities)
        assertEquals(listOf("text"), m.outputModalities)
    }

    @Test
    fun `persisted baseModel carries catalog truth not the stamp`() {
        // [T-custom-model-enrich-wiring] The PERSIST path must NOT stamp:
        // a stamped baseModel masks later catalog refreshes (enrichWithDevData
        // prefers a non-null model field over the dev row) and presents a
        // guess as fact in the params page.
        val entry = ModelEntry(
            providerInstanceId = "p",
            baseModel = LLMModel("relay-foo-v2", "Foo V2", "Relay"),
        )
        assertNull(entry.baseModel.contextWindow)
        assertNull(entry.baseModel.maxOutputTokens)
        assertNull(entry.baseModel.reasoningEffortValues)
    }

    @Test
    fun `catalog and user overrides win over defaults`() {
        val entry = ModelEntry(
            providerInstanceId = "p",
            baseModel = LLMModel(
                id = "gpt-5.4",
                displayName = "GPT-5.4",
                provider = "OpenAI",
                contextWindow = 400_000,
                maxOutputTokens = 16_384,
                supportsReasoning = false,
                reasoningEffortValues = listOf("low", "high"),
                inputModalities = listOf("text", "image"),
                outputModalities = listOf("text"),
            ),
            overrides = ModelOverrides(maxOutputTokens = 32_000),
        )
        val m = entry.model
        assertEquals(400_000, m.contextWindow)
        assertEquals(32_000, m.maxOutputTokens)
        assertEquals(false, m.supportsReasoning)
        assertEquals(listOf("low", "high"), m.reasoningEffortValues)
        assertEquals(listOf("text", "image"), m.inputModalities)
    }
}
