package com.openminis.app.ui.chat

import com.openminis.app.data.model.ThinkingLevel

/**
 * [T-thinking-dual-ledger] (pi port, P2 follow-up) The per-turn thinking
 * ledger — the port of pi's `thinkingLevel` vs `providerThinkingLevel` split
 * (packages/ai/src/types.ts:601-610). Two fields land in every persisted
 * token_usage JSON:
 *
 *  - `thinkingLevelRequested` — ALWAYS written: the tier the session asked
 *    for. Before this existed, "what did the user actually request for this
 *    turn" was nowhere in the usage record, so a wrong-tier request (jumped,
 *    clamped, forced) was undetectable after the fact.
 *  - `thinkingLevelEffective` — written ONLY with response evidence, null
 *    (= unknown) otherwise. Honest by construction: a tier is claimed only
 *    when BOTH (a) the response visibly contained thinking content and
 *    (b) the requested tier is inside the model's DECLARED tier set — the
 *    [com.openminis.app.provider.ThinkingLevelCatalog] selectable set, where
 *    requested == the wire tier by construction (exact declared hit, the
 *    picker offers one rung per distinct declared tier). Outside that set the
 *    wire tier may differ from the ask (relay clamp, omitted field after the
 *    no-upward-jump fix), so the record says unknown instead of guessing.
 *
 * Evidence model (why not token counts): Anthropic does not break thinking
 * tokens out of `usage.output_tokens`, and OpenAI's
 * `completion_tokens_details.reasoning_tokens` is not parsed into [LLMUsage]
 * today — the only evidence available on the persist path is the turn's own
 * reasoning content (ThinkingDelta stream or the opaque `reasoning_content`
 * blob). Models that never stream thinking (o-series by default) therefore
 * record null, which is the honest "unknown", not a false "off".
 */
internal data class TurnThinkingUsage(
    /** Client tier the turn asked for, always present (enum name). */
    val requested: String,
    /** Provider-verified tier for this response, or null = unknown. */
    val effective: String?,
)

/**
 * Resolve the ledger for one turn. [thinkingObserved] = the turn streamed
 * thinking content (ThinkingDelta text or a non-blank reasoning blob — an
 * EMPTY blob is DeepSeek V4's explicit non-thinking signal and is NOT
 * evidence). [declaredSelectableLevels] = the model's declared-tier picker
 * set; pass empty when unknown (undeclared catalogs) — the ledger then stays
 * null, never a guess. Pure — JVM-tested.
 */
internal fun resolveTurnThinkingUsage(
    requestedLevel: ThinkingLevel,
    thinkingObserved: Boolean,
    declaredSelectableLevels: List<ThinkingLevel>,
): TurnThinkingUsage {
    val effective = requestedLevel
        .takeIf {
            thinkingObserved &&
                requestedLevel.isEnabled &&
                requestedLevel in declaredSelectableLevels
        }
        ?.name
    return TurnThinkingUsage(requested = requestedLevel.name, effective = effective)
}
