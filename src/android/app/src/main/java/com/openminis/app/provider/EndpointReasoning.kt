package com.openminis.app.provider

import org.json.JSONObject

/** The three reasoning fields a listing entry can settle, or null for "the endpoint said nothing". */
internal data class EndpointReasoningFacts(
    val supportsReasoning: Boolean?,
    val reasoningEffortValues: List<String>?,
    val declaresNoEffortTiers: Boolean?,
) {
    companion object {
        val UNKNOWN = EndpointReasoningFacts(null, null, null)
    }
}

/**
 * Reads reasoning capability from what the endpoint actually declares.
 *
 * The parse used to guess from id prefixes — `gpt-5`, `o1`, `o3`, `o4`,
 * `codex` — which is a hardcoded list in disguise and drifts the same way the
 * retired Zen seed did. It is wrong in the expensive direction for a gateway
 * catalogue: on 2026-10-24 all 17 of Kilo's free models declared reasoning in
 * `supported_parameters` and **none** matched a single prefix, so every one of
 * them landed with the Thinking pill disabled while the endpoint was stating
 * outright that it works.
 *
 * Order of authority:
 * 1. `supported_parameters` naming `reasoning` / `reasoning_effort` /
 *    `include_reasoning` — the endpoint's own declaration, so it wins.
 * 2. `opencode.variants[*].reasoning` — the concrete effort tiers. Present tiers
 *    become [EndpointReasoningFacts.reasoningEffortValues]; a model that declares
 *    reasoning but exposes no tier gets `declaresNoEffortTiers = true`, which is
 *    a different statement from "no tiers were reported" and is why the field is
 *    nullable rather than defaulting to an empty list.
 * 3. The legacy id prefixes, kept as a fallback only. They still fire for
 *    official OpenAI endpoints, whose listings carry no `supported_parameters`
 *    at all, so no existing model regresses.
 *
 * Anything the endpoint does not declare is left null on purpose: models.dev
 * enrichment and the catalogue fill those in later, and a wrong `true` here
 * would win over both.
 */
internal object EndpointReasoning {

    private val DECLARED_PARAMS = setOf("reasoning", "reasoning_effort", "include_reasoning")
    private val LEGACY_PREFIXES = listOf("gpt-5", "o1", "o3", "o4")

    /** An effort value that means "no reasoning", so it is not a tier. */
    private const val EFFORT_NONE = "none"

    fun read(obj: JSONObject, modelId: String): EndpointReasoningFacts {
        val declares = obj.optJSONArray("supported_parameters")
            ?.let { params -> (0 until params.length()).any { params.optString(it) in DECLARED_PARAMS } }
            ?: false
        if (declares) {
            val efforts = effortTiers(obj)
            return EndpointReasoningFacts(
                supportsReasoning = true,
                reasoningEffortValues = efforts.takeIf { it.isNotEmpty() },
                declaresNoEffortTiers = if (efforts.isEmpty()) true else null,
            )
        }
        return legacyGuess(modelId)
    }

    /**
     * Effort tiers from `opencode.variants`, in listing order and deduplicated.
     * A variant whose reasoning is off, or whose effort is `none`, is not a tier
     * — `instant` next to `thinking(high)` is exactly that shape.
     */
    private fun effortTiers(obj: JSONObject): List<String> {
        val variants = obj.optJSONObject("opencode")?.optJSONObject("variants") ?: return emptyList()
        val out = mutableListOf<String>()
        variants.keys().forEach { name ->
            val reasoning = variants.optJSONObject(name)?.optJSONObject("reasoning") ?: return@forEach
            if (!reasoning.optBoolean("enabled", false)) return@forEach
            val effort = reasoning.optString("effort")
            if (effort.isEmpty() || effort == EFFORT_NONE || effort in out) return@forEach
            out.add(effort)
        }
        return out
    }

    /** The pre-declaration behaviour, now a fallback rather than the whole story. */
    private fun legacyGuess(modelId: String): EndpointReasoningFacts {
        val lower = modelId.lowercase()
        val known = LEGACY_PREFIXES.any { lower.startsWith(it) } || lower.contains("codex")
        return if (known) EndpointReasoningFacts(true, null, null) else EndpointReasoningFacts.UNKNOWN
    }
}
