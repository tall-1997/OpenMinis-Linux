package com.openminis.app.data.model

/**
 * T-ctxslider 54ab8e93: context-window ceiling for the model-group slider.
 * Delegates to [LLMModel.contextWindowTokens] so the slider, the agent loop
 * and token accounting share one heuristic (the old copy here was stuck on
 * gpt-5 → 128K while the getter already knew 400K).
 */
internal fun inferContextWindowTokens(model: LLMModel): Int = model.contextWindowTokens

internal const val UNKNOWN_CONTEXT_WINDOW = 256_000
internal const val UNKNOWN_MAX_OUTPUT = 128_000
internal val UNKNOWN_REASONING_EFFORT = listOf("low", "medium", "high", "xhigh", "max")
internal val UNKNOWN_TEXT_MODALITY = listOf("text")

/**
 * Family-only max-output. Null means the id is not a known cloud family.
 */
internal fun inferredFamilyMaxOutputTokens(modelId: String, displayName: String = ""): Int? {
    val lid = "$modelId $displayName".lowercase()
    if ("claude" in lid) {
        return if ("haiku" in lid || "sonnet" in lid) 64_000 else 128_000
    }
    if ("gemini" in lid) return 65_536
    if (hasGptFamily(lid, 6) || "gpt-5" in lid || "o3" in lid || "o4" in lid || "codex" in lid) {
        return 128_000
    }
    if ("grok" in lid) {
        return if ("grok-2" in lid || "grok-3" in lid) 8_192 else 64_000
    }
    if ("deepseek" in lid) return 64_000
    if ("glm" in lid || "kimi" in lid || "moonshot" in lid) return 32_768
    if ("qwen" in lid || "minimax" in lid) return 32_768
    return null
}

internal fun isRecognizedModelFamily(modelId: String, displayName: String = ""): Boolean =
    inferredFamilyMaxOutputTokens(modelId, displayName) != null

/**
 * Last-resort max-output when models.dev / DataLearner have never heard of this id.
 * Unknown ids get 128k (with 256k context, thinking max, text modalities) rather
 * than the provider's 16k default.
 */
fun inferredMaxOutputTokens(modelId: String, displayName: String = ""): Int? {
    return inferredFamilyMaxOutputTokens(modelId, displayName) ?: UNKNOWN_MAX_OUTPUT
}

/**
 * Fill holes when the catalog / DataLearner / provider left a field empty.
 * Unknown ids and "we have an id but no params" share the same stamp:
 * 256k context, 128k output, thinking on (ceiling max), text modalities.
 * Never overwrites a field that is already set (catalog, family overlay, user).
 *
 * Video/image inference runs first. Stamping `["text"]` before that made a
 * catalog-silent Sora/Seedream look like an explicit text model, so chat never
 * took the generation path (1.36.4 / 1.36.6).
 */
fun applyUnrecognizedModelDefaults(model: LLMModel): LLMModel {
    val inferred = model
        .withInferredVoiceModality()
        .withInferredVideoModality()
        .withInferredImageModality()
    return inferred.copy(
        contextWindow = inferred.contextWindow?.takeIf { it > 0 } ?: UNKNOWN_CONTEXT_WINDOW,
        maxOutputTokens = inferred.maxOutputTokens?.takeIf { it > 0 } ?: UNKNOWN_MAX_OUTPUT,
        supportsReasoning = inferred.supportsReasoning ?: true,
        reasoningEffortValues = inferred.reasoningEffortValues?.takeIf { it.isNotEmpty() }
            ?: UNKNOWN_REASONING_EFFORT,
        inputModalities = inferred.inputModalities ?: UNKNOWN_TEXT_MODALITY,
        outputModalities = inferred.outputModalities ?: UNKNOWN_TEXT_MODALITY,
    )
}

/**
 * Undo the pre-1.36.50 text stamp on a dedicated generator whose other fields
 * are still the empty-catalog defaults. An explicit catalog modality, or any
 * non-default context/output budget, is left alone.
 */
fun stripDefaultedGeneratorModality(model: LLMModel): LLMModel {
    if (model.outputModalities != UNKNOWN_TEXT_MODALITY) return model
    if (model.contextWindow != null && model.contextWindow != UNKNOWN_CONTEXT_WINDOW) return model
    if (model.maxOutputTokens != null && model.maxOutputTokens != UNKNOWN_MAX_OUTPUT) return model
    val generator = VideoModality.looksLikeVideoGenerator(model.id, model.displayName) ||
        model.looksLikeImageGenerator
    if (!generator) return model
    return model.copy(outputModalities = null)
}

/** `gpt-6` / `GPT6` / `gpt-6免费`, but not `gpt-60`. */
fun hasGptFamily(haystack: String, version: Int): Boolean {
    val compact = haystack.lowercase().replace("-", "").replace("_", "").replace(" ", "")
    val needle = "gpt$version"
    val idx = compact.indexOf(needle)
    if (idx < 0) return false
    val after = compact.getOrNull(idx + needle.length)
    return after == null || !after.isDigit()
}
