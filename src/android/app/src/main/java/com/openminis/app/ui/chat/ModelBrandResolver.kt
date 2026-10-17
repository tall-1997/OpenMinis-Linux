package com.openminis.app.ui.chat

import com.openminis.app.R
import com.openminis.app.data.model.ProviderType

/**
 * Resolves the lobe-icons brand slug for a model reference in the top bar.
 *
 * Resolution chain (first hit wins):
 *  1. **ProviderType mapping** — the configured provider type is the
 *     authoritative signal when it is a KNOWN brand type. Relay/compat
 *     types carry no brand information — a relay can serve any vendor's
 *     model — so they fall through to 2.
 *  2. **Model-name keyword scoring** — the fallback for relays and custom
 *     instances. The normalized model id/display name is scanned for brand
 *     keywords; the LONGEST matching keyword wins (a longer hit is a
 *     stronger signal: "deepseek-chat" must outrank any 3-char fragment).
 *     Ties keep table order.
 *  3. null — no icon. A wrong icon is worse than no icon: the logo asserts
 *     "this model comes from vendor X", and a misattributed brand reads as
 *     a routing fact, not decoration.
 *
 * Icons are lobe-icons v1.95.1 (MIT) SVGs packed verbatim under res/raw
 * (color variants) and res/raw-night (mono brands follow the theme via
 * the night qualifier — currentColor semantics, no color conversion).
 */
internal object ModelBrandResolver {

    /**
     * Brand keywords → lobe-icons slug. Order matters for ties; longer
     * keywords outrank shorter ones by length, not position.
     */
    private val KEYWORDS: List<Pair<String, String>> = listOf(
        // Long, unambiguous brand names first so they win length ties.
        "deepseek" to "deepseek",
        "moonshot" to "moonshot",
        "minimax" to "minimax",
        "mistral" to "mistral",
        "codestral" to "mistral",
        "mixtral" to "mistral",
        "anthropic" to "claude",
        "perplexity" to "perplexity",
        "openrouter" to "openrouter",
        "huggingface" to "huggingface",
        "siliconflow" to "siliconcloud",
        "siliconcloud" to "siliconcloud",
        "midjourney" to "midjourney",
        "fireworks" to "fireworks",
        "deepinfra" to "deepinfra",
        "chatgpt" to "openai",
        "chatglm" to "zhipu",
        "cogvideo" to "zhipu",
        "cogview" to "zhipu",
        "zhipu" to "zhipu",
        "baichuan" to "baichuan",
        "sensenova" to "sensenova",
        "tiangong" to "tiangong",
        "tongyi" to "qwen",
        "doubao" to "doubao",
        "skylark" to "doubao",
        "hunyuan" to "hunyuan",
        "wenxin" to "wenxin",
        "ernie" to "wenxin",
        "claude" to "claude",
        "sonnet" to "claude",
        "opus" to "claude",
        "haiku" to "claude",
        "gemini" to "gemini",
        "gemma" to "gemini",
        "grok" to "grok",
        "llama" to "meta",
        "codellama" to "meta",
        "qwen" to "qwen",
        "qwq" to "qwen",
        "qvq" to "qwen",
        "glm" to "zhipu",
        "kimi" to "kimi",
        "copilot" to "copilot",
        "command" to "cohere",
        "together" to "together",
        "ollama" to "ollama",
        "davinci" to "openai",
        "codex" to "openai",
        "dalle" to "openai",
        "sora" to "sora",
        "gpt" to "openai",
        "dbrx" to "dbrx",
        "jina" to "jina",
        "phi-" to "microsoft",
        "yi-" to "yi",
        "o1" to "openai",
        "o3" to "openai",
        "o4" to "openai",
    )

    /**
     * ProviderType → lobe-icons slug. Only types that assert a brand.
     * Compat/relay/unsupported types return null on purpose — see KDoc.
     */
    private fun slugForType(type: ProviderType): String? = when (type) {
        ProviderType.anthropic -> "claude"
        ProviderType.gemini -> "gemini"
        ProviderType.openAI, ProviderType.openAIResponses -> "openai"
        ProviderType.xAI -> "xai"
        ProviderType.kimiCode -> "kimi"
        else -> null
    }

    /**
     * @param providerType the instance's configured type, when known.
     * @param modelId raw model id (e.g. "glm-5.3", "claude-sonnet-4-5").
     * @param displayName display name as a second signal — relay catalogs
     *   often use friendlier ids than the wire id.
     * @return lobe-icons slug, or null when nothing matches well enough.
     */
    fun resolve(providerType: ProviderType?, modelId: String?, displayName: String?): String? {
        providerType?.let { type -> slugForType(type)?.let { return it } }

        val haystack = "${modelId ?: ""} ${displayName ?: ""}".lowercase()
        if (haystack.isBlank()) return null

        var best: String? = null
        var bestScore = 0
        for ((keyword, slug) in KEYWORDS) {
            if (keyword.length <= bestScore) continue
            if (haystack.contains(keyword)) {
                best = slug
                bestScore = keyword.length
            }
        }
        return best
    }

    /** Compiled resource id for a slug, or null when not packed. */
    fun iconResId(slug: String): Int? = when (slug) {
        "openai" -> R.raw.ic_brand_openai
        "claude" -> R.raw.ic_brand_claude
        "gemini" -> R.raw.ic_brand_gemini
        "deepseek" -> R.raw.ic_brand_deepseek
        "qwen" -> R.raw.ic_brand_qwen
        "zhipu" -> R.raw.ic_brand_zhipu
        "kimi" -> R.raw.ic_brand_kimi
        "moonshot" -> R.raw.ic_brand_moonshot
        "grok" -> R.raw.ic_brand_grok
        "mistral" -> R.raw.ic_brand_mistral
        "minimax" -> R.raw.ic_brand_minimax
        "doubao" -> R.raw.ic_brand_doubao
        "hunyuan" -> R.raw.ic_brand_hunyuan
        "wenxin" -> R.raw.ic_brand_wenxin
        "baichuan" -> R.raw.ic_brand_baichuan
        "yi" -> R.raw.ic_brand_yi
        "meta" -> R.raw.ic_brand_meta
        "xai" -> R.raw.ic_brand_xai
        "openrouter" -> R.raw.ic_brand_openrouter
        "perplexity" -> R.raw.ic_brand_perplexity
        "cohere" -> R.raw.ic_brand_cohere
        "dbrx" -> R.raw.ic_brand_dbrx
        "microsoft" -> R.raw.ic_brand_microsoft
        "sora" -> R.raw.ic_brand_sora
        "deepinfra" -> R.raw.ic_brand_deepinfra
        "siliconcloud" -> R.raw.ic_brand_siliconcloud
        "groq" -> R.raw.ic_brand_groq
        "together" -> R.raw.ic_brand_together
        "fireworks" -> R.raw.ic_brand_fireworks
        "huggingface" -> R.raw.ic_brand_huggingface
        "ollama" -> R.raw.ic_brand_ollama
        "copilot" -> R.raw.ic_brand_copilot
        "midjourney" -> R.raw.ic_brand_midjourney
        "jina" -> R.raw.ic_brand_jina
        "zai" -> R.raw.ic_brand_zai
        "chatglm" -> R.raw.ic_brand_chatglm
        "alibaba" -> R.raw.ic_brand_alibaba
        "volcengine" -> R.raw.ic_brand_volcengine
        "baidu" -> R.raw.ic_brand_baidu
        "tencent" -> R.raw.ic_brand_tencent
        "bytedance" -> R.raw.ic_brand_bytedance
        "gemma" -> R.raw.ic_brand_gemma
        "tiangong" -> R.raw.ic_brand_tiangong
        "sensenova" -> R.raw.ic_brand_sensenova
        else -> null
    }
}
