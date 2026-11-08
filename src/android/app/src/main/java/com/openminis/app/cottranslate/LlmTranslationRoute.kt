package com.openminis.app.cottranslate

import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.LLMProvider

/**
 * [T-cot-translate-llm] The LLM translation route: translate one fragment
 * through an ALREADY-CONFIGURED provider instance instead of the anonymous
 * web endpoints. This is the analog of DSH's paid DeepSeek Flash route, but
 * provider-agnostic — any model the user has configured works.
 *
 * Semantics mirror DSH's `deepseekRecipe`:
 *  - fixed literal-translation system prompt (no persona, no agent context);
 *  - thinking OFF (the request is a utility, not an agent turn);
 *  - small output cap (a fragment is ≤4K chars; translations rarely exceed it);
 *  - zero temperature for deterministic output.
 *
 * NOT routed through [ProviderKeyGate]: this is a user-initiated single
 * request (the "译" toggle), not an agent-loop call — the gate exists to
 * serialize agent burst concurrency, which cannot happen here (the
 * controller's serial latch already guarantees one in-flight request).
 *
 * Failure is reported via [TranslationResult.Failed] like every other route —
 * the UI falls back to the original text with a retry.
 */
object LlmTranslationRoute {

    private const val TAG = "CotTranslateLLM"
    private const val SYSTEM_PROMPT =
        "You are a translation engine. Translate the user's text into the " +
            "requested target language. Output ONLY the translation — no " +
            "explanations, no quotes around the result, preserve the original " +
            "paragraph structure and formatting. If the text is already in the " +
            "target language, return it unchanged."

    private const val MAX_OUTPUT_TOKENS = 4_096

    /**
     * Translate [text] into [targetLanguage] using the given provider.
     * "auto" resolves to English source → the model's strongest language pair
     * is handled by the model itself; the prompt asks to preserve structure.
     */
    suspend fun translate(
        provider: LLMProvider,
        text: String,
        targetLanguage: String,
        modelDisplayName: String,
    ): TranslationResult {
        val lang = if (targetLanguage == "auto") "the user's interface language (Chinese if the text is not already Chinese)" else targetLanguage
        val userMessage = "Translate the following text into $lang. Return only the translation:\n\n$text"
        return try {
            val response = provider.sendMessage(
                messages = listOf(LLMMessage(LLMMessage.Role.USER, userMessage)),
                systemPrompt = SYSTEM_PROMPT,
                maxTokens = MAX_OUTPUT_TOKENS,
                temperature = 0.0,
                thinkingLevel = ThinkingLevel.OFF,
            )
            val out = response.text.trim()
            if (out.isEmpty()) {
                TranslationResult.Failed("$modelDisplayName returned an empty translation")
            } else {
                TranslationResult.Ok(out)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.warning(TAG, "LLM translate failed via $modelDisplayName: ${e.message}")
            TranslationResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }
}
