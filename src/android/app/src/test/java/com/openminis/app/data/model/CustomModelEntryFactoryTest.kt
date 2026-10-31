package com.openminis.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Add Custom Model screen used to construct the entry inline and stamp
 * it with [applyUnrecognizedModelDefaults] directly — the models.dev →
 * DataLearner enrich chain was never consulted, so catalog-exact ids like
 * `kimi-k3` landed with the unknown-model stamp (256k ctx / 128k out).
 *
 * These tests lock the shared factory the screen now uses: the enrich step
 * is injected (production passes ModelsDevApi::enrichModel), and the
 * enriched result — not the raw input — is what gets persisted.
 */
class CustomModelEntryFactoryTest {

    private val passthrough: (LLMModel) -> LLMModel = { it }

    @Test
    fun `trims model id and falls back to model id when display name is blank`() {
        val entry = buildCustomModelEntry(
            modelId = "  kimi-k3  ",
            displayName = "   ",
            providerInstanceId = "inst-1",
            providerTypeDisplayName = "天翼云",
            enrich = passthrough,
        )
        assertEquals("kimi-k3", entry.baseModel.id)
        assertEquals("kimi-k3", entry.baseModel.displayName)
        assertEquals("inst-1", entry.providerInstanceId)
        assertEquals("天翼云", entry.baseModel.provider)
        assertTrue(entry.isCustom)
    }

    @Test
    fun `uses the provided display name when it is not blank`() {
        val entry = buildCustomModelEntry(
            modelId = "kimi-k3",
            displayName = " Kimi K3 ",
            providerInstanceId = "inst-2",
            providerTypeDisplayName = "天翼云",
            enrich = passthrough,
        )
        assertEquals("kimi-k3", entry.baseModel.id)
        assertEquals("Kimi K3", entry.baseModel.displayName)
    }

    @Test
    fun `enriched model is what gets persisted, not the raw input`() {
        val enrich: (LLMModel) -> LLMModel = { raw ->
            raw.copy(contextWindow = 1_048_576, maxOutputTokens = 131_072)
        }
        val entry = buildCustomModelEntry(
            modelId = "kimi-k3",
            displayName = "",
            providerInstanceId = "inst-3",
            providerTypeDisplayName = "天翼云",
            enrich = enrich,
        )
        assertEquals(1_048_576, entry.baseModel.contextWindow)
        assertEquals(131_072, entry.baseModel.maxOutputTokens)
    }

    @Test
    fun `enrich receives the exact model the screen builds`() {
        var seen: LLMModel? = null
        val entry = buildCustomModelEntry(
            modelId = " glm-5.3-oc ",
            displayName = "",
            providerInstanceId = "inst-4",
            providerTypeDisplayName = "天翼云",
            enrich = { seen = it; it },
        )
        assertEquals("glm-5.3-oc", seen?.id)
        assertEquals("glm-5.3-oc", seen?.displayName)
        assertEquals("天翼云", seen?.provider)
        // The persisted model is the stamped form of what enrich saw, not
        // the raw object itself: unknown-model defaults fill the fields the
        // (passthrough) enrich left empty.
        assertEquals(256_000, entry.baseModel.contextWindow)
        assertEquals(128_000, entry.baseModel.maxOutputTokens)
    }
}
