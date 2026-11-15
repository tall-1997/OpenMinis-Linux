package com.openminis.app.provider.compat

import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

/**
 * [T-compat-flags] Declarative per-endpoint compatibility switches for the
 * OpenAI-compatible provider family. Ported from pi
 * `ai/src/providers/compat-schema.ts:196-367` (OpenAICompletionsCompatProperties):
 * pi keeps ~25 per-model flags and every adapter branch reads the table instead
 * of hard-coding `if (host == X)` predicates, so adding a vendor becomes
 * "add flags + one detection line" rather than a new if-else.
 *
 * SCOPE DISCIPLINE (deliberately much smaller than pi's table): only flags that
 * correspond to an EXISTING if-else compat branch in this codebase are declared.
 * Nothing speculative is invented. The audit that produced this set:
 *
 *   - [maxTokensField]        <- OpenAIRequestBodies.buildRequestBody (max_tokens
 *                                vs max_completion_tokens gate, `isOpenRouter || isDashScope`)
 *   - [supportsUsageInStreaming] <- OpenAIRequestBodies.buildRequestBody
 *                                (`stream_options.include_usage` gate, `!isOpenRouter`)
 *   - [cacheControlFormat]    <- OpenAIRequestBodies.buildRequestBody
 *                                (top-level `cache_control` gate,
 *                                `needsOpenRouterAnthropicCacheControl`); declared
 *                                and resolved, branch migration pending — the gate
 *                                additionally keys on the `anthropic/` model-id
 *                                prefix, so it needs the per-model overlay path
 *                                (see [CompatResolver.override]) before the branch
 *                                itself can switch to the flag.
 *
 * Flags that pi declares but this codebase has no matching branch for are NOT
 * declared here:
 *   - thinkingFormat family  -> this codebase already has a data-driven rule
 *     registry in the same role (provider/thinking/ThinkingRuleResolver, the
 *     Phase-1 refactor that replaced the model-id if-chain; golden-pinned by
 *     ThinkingWireGoldenSnapshotTest).
 *   - requiresToolResultName / requiresAssistantAfterToolResult /
 *     requiresNonNullAssistantContent -> ChatToolPairSanitizer + the
 *     always-emit-`content` rule cover these defensively on every request;
 *     there is no per-host branch to express.
 *   - supportsFinishReason -> OpenAIRawStream repairs a missing finish_reason
 *     unconditionally (safe superset of the flag).
 *
 * [T-compat-flags-persist] @Serializable: the persisted per-model overlay
 * (`compat_json` on provider_model_entries, the pi models.json `compat`
 * equivalent) round-trips through the same kotlinx Json instance the provider
 * store uses. Every property carries a default, so an older blob missing a key
 * (or a newer one carrying an unknown key, via ignoreUnknownKeys) decodes to
 * the family-default table instead of failing the whole model load.
 */
@Serializable
data class CompatFlags(
    /**
     * pi `compat-schema.ts:225-229` maxTokensField. Which request-body key
     * carries the output token cap: OpenAI's newer Chat Completions wants
     * `max_completion_tokens`, while several OpenAI-compatible vendors (most
     * visibly Alibaba DashScope / Bailian and OpenRouter) only accept the
     * legacy `max_tokens` and 400 the whole request on the other name.
     */
    val maxTokensField: MaxTokensField = MaxTokensField.MAX_COMPLETION_TOKENS,

    /**
     * pi `compat-schema.ts:211-217` supportsUsageInStreaming. Whether the
     * endpoint accepts `stream_options: {"include_usage": true}` on streaming
     * chat requests. OpenRouter rejects/ignores the field and iOS keyed the
     * same suppression on `useOpenRouterCompat`, so the default-table value
     * for OpenRouter is false; every other family keeps true.
     */
    val supportsUsageInStreaming: Boolean = true,

    /**
     * pi `compat-schema.ts:339-344` cacheControlFormat. Prompt-caching
     * convention: [CacheControlFormat.ANTHROPIC_EPHEMERAL] sends the top-level
     * `cache_control: {"type":"ephemeral"}` breakpoint (OpenRouter's pass-through
     * for Anthropic models), [CacheControlFormat.NONE] omits it. Default-table
     * resolution is NONE everywhere — the live branch also requires the
     * `anthropic/` model-id prefix, which is per-model knowledge; wire the
     * branch through [CompatResolver.override] once per-model flags exist.
     */
    val cacheControlFormat: CacheControlFormat = CacheControlFormat.NONE,
)

/** Which body key carries the output token cap. pi compat-schema.ts:225-229. */
enum class MaxTokensField(val wireName: String) {
    /** OpenAI Chat Completions 2024+ name. */
    MAX_COMPLETION_TOKENS("max_completion_tokens"),

    /** Legacy name still required by DashScope / OpenRouter and several relays. */
    MAX_TOKENS("max_tokens"),
}

/** Prompt-cache control convention. pi compat-schema.ts:339-344 (subset). */
enum class CacheControlFormat {
    /** No cache_control field on the wire (OpenAI/DeepSeek implicit caching). */
    NONE,

    /**
     * Top-level `cache_control: {"type":"ephemeral"}` — the form OpenRouter
     * forwards to Anthropic (OpenMinis#191).
     */
    ANTHROPIC_EPHEMERAL,
}

    /**
     * Per-provider-family default tables + per-model runtime overlay. This is the
     * single lookup the wired branches consult; keeping resolution HERE (and only
     * here) means the family predicates are spelled once and the golden test can
     * pin resolver-equals-legacy-predicate behaviour.
     *
     * The overlay is persisted: `compat_json` on provider_model_entries (pi
     * stores per-model compat in models.json) is deserialized into [CompatFlags]
     * at repository load and fed through [override] — see
     * `CompatOverrideLoader.applyCompatOverrides` in data/repository. There is
     * no UI editor yet (write-side evolution); the read side is live so a
     * future editor / models.json import lands on a working seam.
     */
object CompatResolver {

    /** Endpoint families with a non-default table. Detection = the exact URL
     * predicates the pre-flag branches used, so resolver output is behaviour-
     * identical to the branches it replaces (pinned by CompatFlagsTest). */
    enum class Family(val flags: CompatFlags) {
        /**
         * OpenRouter (`openrouter.ai` in the base URL). Legacy shape:
         * `max_tokens`, no `stream_options`.
         */
        OPENROUTER(
            CompatFlags(
                maxTokensField = MaxTokensField.MAX_TOKENS,
                supportsUsageInStreaming = false,
                cacheControlFormat = CacheControlFormat.NONE,
            ),
        ),

        /**
         * Alibaba DashScope / Bailian compatible mode — [BailianEndpoint.matches]
         * (dashscope substring or a *.maas.aliyuncs.com host, GH#520). Legacy
         * shape: `max_tokens`, WITH stream_options.
         */
        DASHSCOPE(
            CompatFlags(
                maxTokensField = MaxTokensField.MAX_TOKENS,
                supportsUsageInStreaming = true,
                cacheControlFormat = CacheControlFormat.NONE,
            ),
        ),

        /**
         * Everything else (api.openai.com, Mistral, xAI, Azure, Venice, Ark,
         * relays): `max_completion_tokens` + stream_options. Mistral happens to
         * share this table for the declared flags (its compat story lives in
         * the thinking-rule registry), so no MISTRAL constant is spun up until
         * a flag actually diverges.
         */
        GENERIC(
            CompatFlags(
                maxTokensField = MaxTokensField.MAX_COMPLETION_TOKENS,
                supportsUsageInStreaming = true,
                cacheControlFormat = CacheControlFormat.NONE,
            ),
        ),
    }

    /** Per-model runtime overlay: modelId -> flags. pi: models.json `compat`. */
    private val overrides = ConcurrentHashMap<String, CompatFlags>()

    /** Family detection — identical predicates to the branches being replaced. */
    fun familyOf(basePath: String): Family = when {
        basePath.contains("openrouter.ai") -> Family.OPENROUTER
        com.openminis.app.data.model.BailianEndpoint.matches(basePath) -> Family.DASHSCOPE
        else -> Family.GENERIC
    }

    /**
     * Resolve the flags for one request target. [modelId] (the LLMModel.id)
     * participates in the overlay lookup; per-access resolution (rather than
     * caching at provider construction) means [override] applies to the very
     * next request of a live provider instance.
     */
    fun forBasePath(basePath: String, modelId: String? = null): CompatFlags {
        if (modelId != null) overrides[modelId]?.let { return it }
        return familyOf(basePath).flags
    }

    /**
     * Per-model override entry point (pi models.json `compat` equivalent).
     * Unknown model ids that resolve through here bypass family detection
     * entirely — the caller owns the full table, matching pi's "user override
     * wins over URL auto-detection" semantics.
     */
    fun override(modelId: String, flags: CompatFlags) {
        overrides[modelId] = flags
    }

    /**
     * [T-compat-flags-persist] Atomically replace the whole overlay from the
     * freshly loaded provider store (repository load-path sync): model ids
     * absent from [map] drop their override so a deleted entry / cleared blob
     * falls back to the family default instead of surviving as stale state.
     */
    fun replaceAllOverrides(map: Map<String, CompatFlags>) {
        overrides.keys.removeAll { it !in map }
        overrides.putAll(map)
    }

    /** Drop one per-model override (family default applies again). */
    fun clearOverride(modelId: String) {
        overrides.remove(modelId)
    }

    /** Drop every per-model override (test/fixture reset). */
    fun clearAllOverrides() {
        overrides.clear()
    }
}
