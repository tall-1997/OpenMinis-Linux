package com.openminis.app.provider

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelOverrides
import com.openminis.app.data.model.ThinkingLevel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-thinking-ultra-manual] Pins the resolution contract of
 * [ModelEntry.effectiveMaxThinkingLevel]:
 *
 *   1. supportsReasoning == false → OFF, checked BEFORE the manual override
 *      so a dead ceiling can never sit on a non-reasoning model (mirrors the
 *      first rule of catalogMaxThinkingLevel, which the override previously
 *      bypassed).
 *   2. overrides.maxThinkingLevel (the operator's assertion) wins.
 *   3. null override → the catalog ceiling of the resolved model.
 */
class EffectiveMaxThinkingLevelTest {

    private fun entry(
        tiers: List<String>? = null,
        supportsReasoning: Boolean? = true,
        override: ThinkingLevel? = null,
    ): ModelEntry = ModelEntry(
        providerInstanceId = "p",
        baseModel = LLMModel(
            id = "test-model",
            displayName = "Test",
            provider = "Test",
            supportsReasoning = supportsReasoning,
            reasoningEffortValues = tiers,
        ),
        overrides = ModelOverrides(maxThinkingLevel = override),
    )

    @Test
    fun `manual override wins over the catalog ceiling`() {
        val e = entry(tiers = listOf("low", "medium", "high", "xhigh", "max"), override = ThinkingLevel.HIGH)
        assertEquals(ThinkingLevel.HIGH, e.effectiveMaxThinkingLevel)
    }

    @Test
    fun `manual ULTRA override is honored`() {
        // The operator's assertion that this model reaches ULTRA — the exact
        // escape hatch a custom/unknown model (UNKNOWN 5-tier set) needs.
        val e = entry(tiers = listOf("low", "high"), override = ThinkingLevel.ULTRA)
        assertEquals(ThinkingLevel.ULTRA, e.effectiveMaxThinkingLevel)
    }

    @Test
    fun `null override falls to the catalog ceiling`() {
        val e = entry(tiers = listOf("high", "max"), override = null)
        assertEquals(ThinkingLevel.MAX, e.effectiveMaxThinkingLevel)
    }

    @Test
    fun `declared ultra tier reaches the ceiling without an override`() {
        val e = entry(tiers = listOf("low", "medium", "high", "xhigh", "max", "ultra"))
        assertEquals(ThinkingLevel.ULTRA, e.effectiveMaxThinkingLevel)
    }

    @Test
    fun `non-reasoning model is OFF even with a manual override`() {
        // [T-thinking-ultra-manual] capability guard: the override previously
        // bypassed the supportsReasoning check, letting a dead ceiling sit on
        // a non-reasoning model.
        val e = entry(supportsReasoning = false, override = ThinkingLevel.ULTRA)
        assertEquals(ThinkingLevel.OFF, e.effectiveMaxThinkingLevel)
    }

    @Test
    fun `non-reasoning model is OFF without an override`() {
        val e = entry(supportsReasoning = false, override = null)
        assertEquals(ThinkingLevel.OFF, e.effectiveMaxThinkingLevel)
    }
}
