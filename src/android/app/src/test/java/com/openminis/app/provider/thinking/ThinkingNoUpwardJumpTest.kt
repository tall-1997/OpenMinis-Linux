package com.openminis.app.provider.thinking

import com.openminis.app.data.model.ThinkingLevel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-thinking-no-upward-jump] (P2 3-9) Pins the ONE direction the wire clamp
 * may move a request: DOWN. The old `?: declared.first().second` fallback in
 * [ThinkingEffort.clampEffort] lifted a below-floor ask onto the declared
 * floor — a LOW request against a `[high,max]` catalog set went out as
 * `reasoning_effort:"high"` — the opposite of clamping, and invisible to the
 * user because the composer had already shown LOW as selected. The fix: the
 * clamp returns null and every emitter OMITS the thinking field, the same
 * policy the OFF branch already shipped for "no legal off tier"
 * (OpenMinis#377).
 *
 * These tests pin the invariant from both sides: the pure clamp (null, never
 * the floor) and the two emitter branches that consume it (generic
 * ReasoningEffort omits the tier; DeepSeekSibling keeps its explicit toggle
 * but omits the tier). A regression that re-introduces an upward jump fails
 * here before it can reach a provider.
 */
class ThinkingNoUpwardJumpTest {

    private fun ctx(
        modelId: String,
        declared: List<String>?,
        level: ThinkingLevel,
        offEffort: String? = null,
    ) = ThinkingResolveContext(
        modelId = modelId,
        instanceId = null,
        supportsReasoning = true,
        declaredEffortValues = declared,
        level = level,
        maxTokens = 4096,
        isOpenRouter = false,
        usesUnifiedReasoningEffort = false,
        isMistral = false,
        isDashScope = false,
        offEffort = offEffort,
    )

    // ---- pure clamp: null below the floor, never the declared floor ----

    @Test
    fun `clampEffort returns null when the request sits below the entire declared set`() {
        // The exact case the old fallback turned into "high".
        assertNull(ThinkingEffort.clampEffort("low", listOf("high", "max")))
        assertNull(ThinkingEffort.clampEffort("medium", listOf("high", "max")))
        // Down-walk stays intact: nearest declared tier at or below the ask.
        assertEquals("high", ThinkingEffort.clampEffort("xhigh", listOf("high", "max")))
        assertEquals("medium", ThinkingEffort.clampEffort("high", listOf("low", "medium")))
        // Exact hits stay intact (case-insensitive, lowercased).
        assertEquals("low", ThinkingEffort.clampEffort("low", listOf("low", "high")))
        assertEquals("max", ThinkingEffort.clampEffort("Max", listOf("high", "max")))
    }

    @Test
    fun `clampEffort never returns a tier above the request across the ladder`() {
        val ladder = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra")
        val declaredSets = listOf(
            listOf("high", "max"),
            listOf("medium", "high"),
            listOf("low", "high", "max"),
            listOf("xhigh"),
        )
        for (declared in declaredSets) {
            for (ask in ladder) {
                val got = ThinkingEffort.clampEffort(ask, declared) ?: continue
                val askRank = ladder.indexOf(ask)
                val gotRank = ladder.indexOf(got)
                assertTrue(
                    "upward jump: ask=$ask declared=$declared -> $got",
                    gotRank <= askRank,
                )
            }
        }
    }

    @Test
    fun `clampRequested keeps the manual ultra bypass and nulls below the floor`() {
        // Manual override is a vocabulary assertion — passes through unchanged.
        assertEquals(
            "ultra",
            ThinkingEffort.clampRequested("ultra", listOf("high", "max"), ThinkingLevel.ULTRA),
        )
        // Without the override, below-floor stays null (no upward jump).
        assertNull(ThinkingEffort.clampRequested("low", listOf("high", "max"), null))
    }

    // ---- OFF branch semantics unchanged (within-off-ladder snap, no reasoning tier) ----

    @Test
    fun `clampOffEffort still snaps none onto the lowest declared off-family tier`() {
        // "none" unreadable by the endpoint degrades to the OFF ladder, never
        // upward onto a reasoning tier, and never to null while a legal off
        // tier exists.
        assertEquals("minimal", ThinkingEffort.clampOffEffort("none", listOf("minimal", "high")))
        assertEquals("low", ThinkingEffort.clampOffEffort("none", listOf("low", "high", "max")))
        assertEquals("none", ThinkingEffort.clampOffEffort("none", listOf("none", "high")))
        // No legal off tier at all: omitted (pre-existing #377 behaviour).
        assertNull(ThinkingEffort.clampOffEffort("none", listOf("high", "max")))
    }

    // ---- emitter branches: omit the field, never lift ----

    @Test
    fun `generic branch omits reasoning_effort when the ask sits below the declared floor`() {
        val body = JSONObject()
        val trace = ThinkingRuleResolver.apply(
            body,
            ctx(modelId = "glm-5.2", declared = listOf("high", "max"), level = ThinkingLevel.LOW),
        )
        assertFalse(
            "a LOW ask must never surface as the declared floor 'high': $body",
            body.has("reasoning_effort"),
        )
        assertNull(trace.clampedTo)
    }

    @Test
    fun `generic branch keeps exact hits and down-walks unchanged`() {
        val exact = JSONObject()
        ThinkingRuleResolver.apply(
            exact,
            ctx(modelId = "glm-5.2", declared = listOf("low", "high", "max"), level = ThinkingLevel.LOW),
        )
        assertEquals("low", exact.optString("reasoning_effort", null))

        val down = JSONObject()
        ThinkingRuleResolver.apply(
            down,
            ctx(modelId = "glm-5.2", declared = listOf("high", "max"), level = ThinkingLevel.XHIGH),
        )
        assertEquals("high", down.optString("reasoning_effort", null))
    }

    @Test
    fun `deepseek sibling keeps the toggle but omits the tier below the declared floor`() {
        val body = JSONObject()
        ThinkingRuleResolver.apply(
            body,
            ctx(
                modelId = "deepseek-v4-pro",
                declared = listOf("high", "max"),
                level = ThinkingLevel.LOW,
            ),
        )
        // V4 thinks by default: the explicit toggle must survive so the
        // request stays gated...
        assertEquals(
            "enabled",
            body.optJSONObject("thinking")?.optString("type"),
        )
        // ...but the tier is omitted rather than lifted onto the floor.
        assertFalse(
            "a LOW ask must not surface as reasoning_effort:'high': $body",
            body.has("reasoning_effort"),
        )
    }

    @Test
    fun `deepseek sibling keeps sibling tiers at or above the declared floor`() {
        val body = JSONObject()
        ThinkingRuleResolver.apply(
            body,
            ctx(
                modelId = "deepseek-v4-pro",
                declared = listOf("high", "max"),
                level = ThinkingLevel.HIGH,
            ),
        )
        assertEquals("enabled", body.optJSONObject("thinking")?.optString("type"))
        assertEquals("high", body.optString("reasoning_effort", null))
    }
}
