package com.openminis.app.text

/**
 * [T-universal-think-tag] Single source of truth for the reasoning-tag
 * spellings every provider has been observed leaking into `content`.
 *
 * Two consumers must never drift apart again (they did once: the history
 * stripper knew 6 spellings while the stream parser knew 9, so paired
 * `<antThinking>…</antThinking>` blocks survived the history path and were
 * replayed verbatim to the model):
 *
 *  - [com.openminis.app.provider.openai.ThinkPrefixStreamParser] — live
 *    streaming split (start-anchored state machine).
 *  - [com.openminis.app.agent.MessageTransformerChain] — persisted-history
 *    stripping (regex, code-fence protected).
 *
 * Order does not matter here; each consumer establishes its own
 * longest-first matching order.
 */
object ReasoningTagVariants {
    /** Bare tag names between `<` `>` (and the Chinese localized forms). */
    val NAMES: List<String> = listOf(
        "thinking",       // Kimi K2/K3/K4 (Moonshot), Qwen-QwQ, DeepSeek-R1 distills
        "think",          // MiniMax M3
        "reasoning",      // some OpenRouter / OpenAI-compatible routers
        "analysis",       // Gemini / Ollama variant deployments
        "antThinking",    // Claude→OpenAI bridge deployments
        "inner_thought",  // GLM-5 family preview builds
        "scratchpad",     // older self-hosted CoT wrappers
        "分析",            // Chinese-localized wrappers
        "思考",            // Chinese-localized wrappers
    )

    /** Names that also appear in the `<|name|>` special-token form (GLM/ChatGLM). */
    val SPECIAL_NAMES: List<String> = listOf("thinking", "think", "reasoning")

    /** `<name>` open tags, longest first (so `<thinking>` wins over `<think>`). */
    private val PAIRS: List<Pair<String, String>> =
        (NAMES.map { "<$it>" to "</$it>" } + SPECIAL_NAMES.map { "<|$it|>" to "<|/$it|>" })
            .sortedByDescending { it.first.length }

    /** `<name>` open tags, longest first (so `<thinking>` wins over `<think>`). */
    val OPEN_TAGS: List<String> = PAIRS.map { it.first }

    /** `</name>` close tags aligned index-for-index with [OPEN_TAGS]. */
    val CLOSE_TAGS: List<String> = PAIRS.map { it.second }

    /** Alternation body for regex construction, e.g. `thinking|think|…`. */
    val ALTERNATION: String = NAMES.joinToString("|")
}
