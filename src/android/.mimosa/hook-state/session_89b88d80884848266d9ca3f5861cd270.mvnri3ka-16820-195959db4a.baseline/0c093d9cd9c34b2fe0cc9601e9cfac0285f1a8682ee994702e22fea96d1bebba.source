package com.openminis.app.provider.thinking

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.ThinkingLevelCatalog
import com.openminis.app.provider.catalogMaxThinkingLevel
import com.openminis.app.provider.selectableThinkingLevels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-thinking-levels-data-driven] Pins the contract of
 * [LLMModel.selectableThinkingLevels]: every rung it offers must produce a
 * DISTINCT wire request for that model.
 *
 * The reason this suite exists at all: `ThinkingRulesRegressionTest` asserts
 * only that the returned levels are distinct ENUM values, which a collapsed
 * ladder trivially satisfies — offering [HIGH, XHIGH, MAX] for a family that
 * folds all three onto one `thinking_budget` number is distinct as a list and
 * identical on the wire. "The slider changes nothing" is therefore invisible to
 * the old suite, and that is the bug this one locks down.
 *
 * Deliberately NOT tested here: ULTRA reaching the wire. ULTRA is a client-side
 * "MAX + orchestration" concept and serializes as `"max"`; asserting it is a
 * distinct wire value would assert a falsehood. What matters is that it is
 * never *offered* as a second spelling of MAX — asserted below.
 */
class ThinkingLevelSelectionTest {

    private fun model(
        id: String,
        supportsReasoning: Boolean? = true,
        tiers: List<String>? = null,
    ): LLMModel {
        val base = LLMModel(
            id = id,
            displayName = id,
            provider = "test",
            contextWindow = 128_000,
            maxOutputTokens = 32_768,
            supportsReasoning = supportsReasoning,
        )
        // Mirror how the repository actually populates the tier list: a model
        // that declares nothing must stay `null`, because OpenAIRequestBodies
        // reads `declaresNoEffortTiers == true` as "omit the field entirely".
        return if (tiers == null) base else base.copy(reasoningEffortValues = tiers)
    }

    // ── the shipped fix: sparse declared sets must stay reachable ─────────────

    /**
     * Field report cited in `catalogMaxThinkingLevel`: 79 of 339 catalog
     * entries declare the sparse set `["high","max"]` (deepseek-v4, zhipuai
     * glm-5.2). Before the ceiling fix the picker topped out at XHIGH, which is
     * not in that set, so `clampEffort` snapped it DOWN to "high" and max was
     * unreachable. These two rungs ARE genuinely distinct on the wire, so the
     * sparse ladder must be preserved, not flattened.
     */
    @Test
    fun `sparse high-max declaration yields both rungs and a MAX ceiling`() {
        listOf("deepseek-v4", "zhipuai/glm-5.2", "glm-5.2").forEach { id ->
            val m = model(id, tiers = listOf("high", "max"))
            assertEquals(
                "$id must keep both declared rungs — they are distinct wire values",
                listOf(ThinkingLevel.HIGH, ThinkingLevel.MAX),
                m.selectableThinkingLevels,
            )
            assertEquals(
                "$id ceiling must reach MAX so the user is not pinned below it",
                ThinkingLevel.MAX,
                m.catalogMaxThinkingLevel,
            )
        }
    }

    // ── families whose wire path folds its top rungs together ────────────────

    /**
     * MiMo/Agnes: `clampEffortForModel` demotes "xhigh"->"high" (the backend
     * 400s on xhigh) and the catalog ceiling rule caps the family at HIGH, so
     * HIGH/XHIGH/MAX/ULTRA are one wire value. Matching the FAMILY rather than
     * one spelling matters: docs say "mimo-2.5" while the live API serves
     * "mimo-v2.5" / "mimo-v2.5-pro" (iOS 72968c4f).
     */
    @Test
    fun `mimo and agnes stop at high`() {
        listOf("mimo-2.5", "mimo-v2.5", "mimo-v2.5-pro", "agnes-1.0").forEach { id ->
            val levels = model(id, tiers = fullLadder()).selectableThinkingLevels
            assertTrue(
                "$id must never offer XHIGH/MAX (clampEffortForModel folds them onto high): $levels",
                levels.none { it.rank > ThinkingLevel.HIGH.rank },
            )
            assertEquals(
                "$id must still offer LOW/MEDIUM/HIGH",
                listOf(ThinkingLevel.LOW, ThinkingLevel.MEDIUM, ThinkingLevel.HIGH),
                levels,
            )
        }
    }

    /** ByteDance seed: Ark rejects "xhigh", so the ladder tops out at high too. */
    @Test
    fun `bytedance seed stops at high`() {
        listOf("seed-1.6-plus", "seed-2.0", "bytedance-seed-2.0").forEach { id ->
            val levels = model(id, tiers = fullLadder()).selectableThinkingLevels
            assertTrue(
                "$id must never offer XHIGH/MAX (backend 400s on xhigh): $levels",
                levels.none { it.rank > ThinkingLevel.HIGH.rank },
            )
        }
    }

    /**
     * Gemini 2.5 Pro/Flash drive a NUMERIC thinkingBudget, not an effort
     * string: OFF/LOW/MEDIUM/HIGH are distinct but everything from XHIGH up
     * saturates the model's top budget (Pro 16384, Flash 8192). Three rungs,
     * one number.
     */
    @Test
    fun `gemini-2-5 numeric budget stops at high`() {
        listOf("gemini-2.5-pro", "gemini-2.5-flash").forEach { id ->
            val levels = model(id, tiers = fullLadder()).selectableThinkingLevels
            assertTrue(
                "$id thinkingBudget saturates above HIGH; offering $levels is a no-op slider",
                levels.none { it.rank > ThinkingLevel.HIGH.rank },
            )
        }
    }

    /** Qwen/DashScope thinking_budget: same saturation shape as Gemini. */
    @Test
    fun `qwen thinking budget stops at high`() {
        listOf("qwen3-max", "qwen-plus").forEach { id ->
            val levels = model(id, tiers = fullLadder()).selectableThinkingLevels
            assertTrue(
                "$id thinking_budget saturates above HIGH; offering $levels is a no-op slider",
                levels.none { it.rank > ThinkingLevel.HIGH.rank },
            )
        }
    }

    // ── families with no thinking control at all ─────────────────────────────

    /**
     * Nothing is emitted for these ids in any branch, so EVERY level produces a
     * byte-identical request. The honest answer is an empty list — offering a
     * gradient would be fabricating one. Empty also leaves `catalogMaxThinkingLevel`
     * on its legacy id-rule path exactly as before, so this is additive.
     *
     * Note "2.5-flash-lite" must be matched BEFORE the "gemini-2.5-flash"
     * rule that would otherwise catch it (first match wins), and these suffixes
     * contain no Gemini substring at all.
     */
    @Test
    fun `no-control families offer no levels at all`() {
        // Real ids from assets/models-dev-api.json, not invented ones — the
        // marker sits in the MIDDLE of these, which is exactly what a suffix
        // match misses.
        listOf(
            "gemini-2.5-flash-preview-tts",
            "gemini-embedding-001",
            "text-embedding-004",
            "text-embedding-3-large",
            "qwen3-embedding-8b",
            "gemini-2.5-flash-lite",
            "gemini-2.5-flash-image",
        ).forEach { id ->
            assertEquals(
                "$id emits no thinking field; every level is the same request",
                emptyList<ThinkingLevel>(),
                model(id, tiers = fullLadder()).selectableThinkingLevels,
            )
        }
    }

    // ── the invariants that must hold for every model ────────────────────────

    /**
     * ULTRA serializes as `"max"` — same bytes as MAX — so offering it as a
     * separate rung is exactly the lie this suite exists to prevent.
     */
    @Test
    fun `ultra is never offered as a distinct rung`() {
        val ids = listOf(
            "deepseek-v4", "glm-5.2", "mimo-v2.5", "gemini-2.5-pro",
            "qwen3-max", "gpt-5.6-sol", "claude-opus-4-8", "seed-1.6",
            "gemini-2.5-flash-lite", "some-unknown-model",
        )
        ids.forEach { id ->
            val levels = model(id, tiers = fullLadder()).selectableThinkingLevels
            assertFalse(
                "ULTRA is a client-side concept, never a wire effort: $id offered $levels",
                levels.contains(ThinkingLevel.ULTRA),
            )
        }
    }

    /**
     * No returned list may contain duplicates — a repeated level in the picker
     * is a slider detent that provably changes nothing. This mirrors the
     * existing assertion in ThinkingRulesRegressionTest and is kept here so a
     * truncated ladder can never introduce one.
     */
    @Test
    fun `offered levels are distinct and ordered weakest to strongest`() {
        val ids = listOf(
            "deepseek-v4", "glm-5.2", "mimo-v2.5", "gemini-2.5-pro",
            "qwen3-max", "gpt-5.6-sol", "seed-1.6", "some-unknown-model",
        )
        ids.forEach { id ->
            val levels = model(id, tiers = fullLadder()).selectableThinkingLevels
            assertEquals(
                "$id must not repeat a level: $levels",
                levels.size,
                levels.toSet().size,
            )
            assertEquals(
                "$id must be ordered weakest to strongest (callers take lastOrNull): $levels",
                levels.sortedBy { it.rank },
                levels,
            )
        }
    }

    /** OFF is a toggle, never an effort tier, so it must never appear here. */
    @Test
    fun `off is never offered as a rung`() {
        listOf("deepseek-v4", "mimo-v2.5", "gemini-2.5-pro").forEach { id ->
            assertFalse(
                "OFF is owned by the separate toggle: $id",
                model(id, tiers = fullLadder()).selectableThinkingLevels
                    .contains(ThinkingLevel.OFF),
            )
        }
    }

    /** A model that cannot reason stays at OFF regardless of any family rule. */
    @Test
    fun `non-reasoning model keeps an OFF ceiling`() {
        val m = model("mimo-v2.5-tts", supportsReasoning = false, tiers = fullLadder())
        assertEquals(ThinkingLevel.OFF, m.catalogMaxThinkingLevel)
        assertEquals(emptyList<ThinkingLevel>(), m.selectableThinkingLevels)
    }

    /**
     * A model that declares no tiers keeps the legacy path untouched: the
     * family ladder rule still applies where one exists, and otherwise the
     * conservative XHIGH default stands. This guards against the new table
     * silently becoming the ONLY source of truth.
     */
    @Test
    fun `model with no declared tiers falls back to legacy ceiling`() {
        assertEquals(
            ThinkingLevel.XHIGH,
            model("some-unknown-reasoner").catalogMaxThinkingLevel,
        )
        // MiMo declares no tiers here, but the family rule still caps it at HIGH
        // so the xhigh clamp can never reach a backend that 400s on it.
        assertEquals(
            ThinkingLevel.HIGH,
            model("mimo-v2.5").catalogMaxThinkingLevel,
        )
    }

    /**
     * `NoControl` must stay a true no-op rather than silently degrading into a
     * truncated gradient — that distinction is the whole point of the sealed
     * hierarchy and is invisible if the branches are ever merged.
     */
    @Test
    fun `no-control is distinct from collapses-above`() {
        assertEquals(
            ThinkingLevelCatalog.WireLadder.NoControl,
            ThinkingLevelCatalog.declaredWireLadder("gemini-2.5-flash-lite"),
        )
        assertEquals(
            ThinkingLevelCatalog.WireLadder.CollapsesAbove(ThinkingLevel.HIGH),
            ThinkingLevelCatalog.declaredWireLadder("gemini-2.5-pro"),
        )
        assertEquals(null, ThinkingLevelCatalog.declaredWireLadder("gpt-5.6-sol"))
    }

    private fun fullLadder() =
        listOf("low", "medium", "high", "xhigh", "max")
}