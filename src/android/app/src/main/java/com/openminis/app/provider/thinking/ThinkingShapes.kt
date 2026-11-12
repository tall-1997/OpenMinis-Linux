package com.openminis.app.provider.thinking

import com.openminis.app.data.model.ThinkingLevel
import org.json.JSONObject

/**
 * [T-thinking-rules-phase2] Gemini / Anthropic thinking SHAPE emitters,
 * extracted from ThinkingRuleResolver for the architecture-policy line
 * ceiling. The resolver exposes thin delegates so call sites are unchanged.
 */
internal object ThinkingShapes {
    // ---- Gemini / Anthropic (Phase 2 §1) ----
    //
    // These two providers do NOT share the OpenAI body shape — Gemini writes into
    // `generationConfig.thinkingConfig` and Anthropic builds its own `thinking` object —
    // so rather than routing them through [apply], the resolver owns their SHAPE
    // decisions as pure functions and each provider asks for the shape it needs.
    //
    // That still achieves the Phase 2 goal (one place owns every vendor's thinking
    // contract) without pretending three body formats are one. Both functions were lifted
    // verbatim from the provider-local implementations they replace and are pinned
    // byte-for-byte by ThinkingWireGeminiAnthropicSnapshotTest.
    //
    // ⚠️ These intentionally differ from the iOS versions in two places, because the
    // Android originals did: 3.x at MAX/ULTRA falls through to "low" here (iOS: "high"),
    // and unknown/latest ids return null here (iOS: a 128-floor fallback table). Phase 2
    // §1 is a pure refactor — do not "align" them without a deliberate decision.

    /**
     * The `generationConfig.thinkingConfig` object for a Gemini request, or null when the
     * model takes no thinking config at all (specialized -tts/-image/-embedding/-vision
     * modalities, 2.5 Flash Lite, and any id matching none of the families).
     */
    /**
     * [T-gemini37-minimal-400][OpenMinis#470] Gemini 3.x AND newer Flash models
     * that reject `thinkingLevel: "minimal"` with a 400 and must use "low" as
     * their OFF floor.
     *
     * Matched by MAJOR.MINOR version rather than an exact-id list. 3.7 is where
     * Google dropped the level, and the 4.x generation follows the same
     * contract (gemini-4-argon accepts thinkingLevel, not thinkingBudget), so
     * anything from 3.7 up — including every 4.x — is assumed to have dropped
     * it. Guessing "low" for a model that would have accepted "minimal" costs a
     * slightly higher thinking floor; guessing "minimal" for one that rejects it
     * makes the model unusable outright. The asymmetry decides the default.
     */
    private fun rejectsMinimalLevel(lowerId: String): Boolean {
        val (major, minor) = geminiVersion(lowerId) ?: return false
        return major > 3 || (major == 3 && minor >= 7)
    }

    /**
     * [OpenMinis#470] Parse the `gemini-<major>[.<minor>]` version, or null for
     * an id without one. Used to decide the thinking WIRE SHAPE by generation:
     * >= 3 speaks `thinkingLevel`, 2.x speaks the numeric `thinkingBudget`.
     *
     * The old check was `modelId.contains("gemini-3")`, so `gemini-4-argon` fell
     * through to the unknown branch and (in the build that reported the bug) was
     * answered with a `thinkingBudget`, which Gemini 4 rejects with a 400. A
     * regex on the numeric version keeps every future major on the correct
     * shape without touching this file again.
     */
    private fun geminiVersion(lowerId: String): Pair<Int, Int>? {
        val m = Regex("""gemini-(\d+)(?:\.(\d+))?""").find(lowerId) ?: return null
        val major = m.groupValues[1].toIntOrNull() ?: return null
        val minor = m.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
        return major to minor
    }

    fun geminiThinkingConfig(
        modelId: String,
        level: ThinkingLevel,
        budgetTokensMax: Int? = null,
        // [T-gemini-budget-min-dead] No budgetTokensMin here: the Gemini wire
        // API has no minimum-thinking field (2.5 sends a single thinkingBudget,
        // 3.x sends thinkingLevel). The parameter used to exist and was never
        // consumed — models.dev's declared min is a catalog bound, not wire.
        //
        // [OpenMinis#226] `hasAudioOutput` is the catalog's AUDIO-OUTPUT
        // modality (TTS). Gemini TTS rejects the thinking config outright, and
        // id-suffix matching alone misses audio-output models whose id does not
        // say "-tts" — this makes the modality the first-class signal instead of
        // a naming convention.
        hasAudioOutput: Boolean = false,
    ): JSONObject? {
        // [T-gemini-tts-thinking-400 / OpenMinis#226] Specialized modalities take
        // precedence over EVERY family rule and over the requested level: these models
        // reject the thinking parameter outright, so sending one is a hard 400
        // ("Thinking level is not supported for this model.").
        //
        // Checked FIRST because these ids also match a family pattern —
        // `gemini-3.1-flash-tts-preview` contains "gemini-3", so any later placement is
        // shadowed. Android previously had no such test at all, so all three Gemini TTS
        // models were unusable; iOS had one but below the family branches, equally dead.
        //
        // Lowercased for the same reason iOS lowercases the whole id: catalog and live
        // API spellings differ in case. The family checks above deliberately keep their
        // original raw-`modelId` form (Phase 2 §1 is a pure refactor).
        val lowerId = modelId.lowercase()
        if (hasAudioOutput) return null
        val noThinkingSuffixes = listOf("-tts", "-image", "-embedding", "-vision")
        if (noThinkingSuffixes.any { lowerId.endsWith(it) || lowerId.contains("$it-") }) {
            return null
        }

        // [OpenMinis#470] Generation test, not a "gemini-3" substring: 4.x
        // takes the same thinkingLevel shape as 3.x, and a `modelId.contains(...)`
        // check silently routed gemini-4-argon to the unknown branch.
        val geminiMajor = geminiVersion(lowerId)?.first ?: 0
        val isGemini3Plus = geminiMajor >= 3
        val is25Pro = modelId.contains("gemini-2.5-pro")
        val is25Flash = modelId.contains("gemini-2.5-flash") && !modelId.contains("lite")
        val is25FlashLite = modelId.contains("gemini-2.5-flash-lite")

        if (is25FlashLite) return null

        return when {
            isGemini3Plus -> JSONObject().apply {
                if (level == ThinkingLevel.OFF) {
                    // 3.x cannot fully disable thinking; the floor is the weakest
                    // level the model will accept.
                    //
                    // [T-gemini37-minimal-400] "minimal" is NOT universal across the
                    // 3.x Flash family. Verified on-device (Pixel 4a, Gemini API):
                    // gemini-3-flash-preview / 3.5-flash / 3.6-flash accept it, but
                    // gemini-3.7-flash returns a hard
                    //   400 "Thinking level MINIMAL is not supported for this model."
                    // on EVERY request — 5/5 consecutive, "all fallbacks exhausted".
                    // So with 思考 set to Off, 3.7 Flash was completely unusable, not
                    // merely un-thinking. "low" is accepted by the whole family and is
                    // the same floor 3.x Pro already used, so fall back to it for the
                    // models that reject minimal rather than probing at runtime.
                    val acceptsMinimal = modelId.contains("flash") && !rejectsMinimalLevel(lowerId)
                    put("thinkingLevel", if (acceptsMinimal) "minimal" else "low")
                } else {
                    put(
                        "thinkingLevel",
                        when (level) {
                            ThinkingLevel.LOW -> "low"
                            ThinkingLevel.MEDIUM -> "medium"
                            // MAX/ULTRA were appended to ThinkingLevel after this
                            // branch was written (for GPT-5.6) and fell through the
                            // old `else -> "low"`, silently sending the WEAKEST level
                            // when the user asked for the strongest. Gemini's ladder
                            // tops out at "high", so every tier at or above HIGH maps
                            // there. Latent today — no catalog rule lifts a Gemini
                            // ceiling above XHIGH, so the picker doesn't offer
                            // MAX/ULTRA — but a per-entry maxThinkingLevel override
                            // reaches it, and a future catalog rule would too.
                            ThinkingLevel.HIGH, ThinkingLevel.XHIGH,
                            ThinkingLevel.MAX, ThinkingLevel.ULTRA,
                            -> "high"
                            ThinkingLevel.OFF -> "low" // unreachable; OFF handled above
                        },
                    )
                    put("includeThoughts", true)
                }
            }
            is25Pro -> JSONObject().apply {
                    val cap = budgetTokensMax?.takeIf { it > ThinkingLadder.FLOOR }
                        ?: ThinkingLadder.DEFAULT_CAPACITY
                    put(
                        "thinkingBudget",
                        if (level == ThinkingLevel.OFF) 128 // minimum; 0 is rejected (df8a823d)
                        else ThinkingLadder.budgetFor(level, cap),
                    )
                    if (level.isEnabled) put("includeThoughts", true)
                }
                is25Flash -> JSONObject().apply {
                    val cap = budgetTokensMax?.takeIf { it > ThinkingLadder.FLOOR }
                        ?: ThinkingLadder.DEFAULT_CAPACITY
                    put(
                        "thinkingBudget",
                        if (level == ThinkingLevel.OFF) 0
                        else ThinkingLadder.budgetFor(level, cap),
                    )
                    if (level.isEnabled) put("includeThoughts", true)
                }
            else -> null
        }
    }

    /**
     * The abstract Anthropic thinking shape, as a small map the caller turns into its
     * `thinking` object:
     *   `{effort:"<tier>"}`   → adaptive thinking (Claude 4.6+)
     *   `{budget_tokens:N}`   → legacy budget thinking (≤4.5)
     *   `{disabled:true}`     → adaptive model at OFF; must be explicit because those
     *                           models think by DEFAULT when no thinking field is sent
     *   `{}`                  → send nothing
     */
    fun anthropicThinkingShape(
        modelId: String,
        supportsReasoning: Boolean?,
        level: ThinkingLevel,
        maxTokens: Int,
    ): Map<String, Any> {
        val adaptive = com.openminis.app.provider.anthropic.AnthropicProvider
            .modelUsesAdaptiveThinking(modelId)
        if (level.isEnabled && supportsReasoning != false) {
            if (adaptive) {
                return mapOf(
                    "effort" to com.openminis.app.provider.anthropic.AnthropicProvider
                        .thinkingEffort(level),
                )
            }
            val budget = com.openminis.app.provider.anthropic.AnthropicProvider
                .thinkingBudget(maxTokens, level)
            return if (budget > 0) mapOf("budget_tokens" to budget) else emptyMap()
        }
        return if (adaptive) mapOf("disabled" to true) else emptyMap()
    }
}

