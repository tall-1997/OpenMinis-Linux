package com.openminis.app.provider

import java.util.concurrent.ConcurrentHashMap

/**
 * Prompt cache warming policy, a port of pi ai/src/cache-warming.ts:31-107:
 * when a refresh is due, whether a request can be replayed, and whether a
 * refresh pays for itself. pi's hosts own the scheduling; this module owns the
 * numbers — pure functions and constants, no Android, no provider IO, so the
 * caller (the request-finish hook) stays thin and the policy is testable.
 *
 * DEFAULT OFF: every decision a caller makes is additionally gated by
 * [com.openminis.app.data.CacheWarmingPrefs.enabled] (SharedPreferences,
 * default false) before any warm request leaves the app.
 */

/** "streaming" while the agent run that sent the request is still active; "idle" after it settled. */
enum class WarmingPhase { STREAMING, IDLE }

/** Whether a refresh is sent ("warm") or warming ends until the next real request ("stop"). */
enum class WarmingAction { WARM, STOP }

/**
 * Inputs and outcome of one warm-or-stop decision (pi cache-warming.ts:14-28).
 * Costs are USD for the whole request.
 */
data class WarmingDecision(
    val phase: WarmingPhase,
    /** Price of this refresh: a cache read of the prompt plus one output token. */
    val warmCost: Double,
    /** Extra price of the next real request if the cache entry is lost. */
    val missCost: Double,
    /** Estimated chance that a real request arrives before the entry expires. */
    val continuationProbability: Double,
    /** `continuationProbability * missCost - warmCost`. */
    val expectedSavings: Double,
    /** False when the prompt size or the model's prices are unknown. */
    val economicsAvailable: Boolean,
    /** "warm" when `expectedSavings` is at least [MINIMUM_EXPECTED_SAVINGS_USD]. */
    val action: WarmingAction,
)

/**
 * Hygiene of a warm request (pi cache-warming.ts:78-80): cap the output at one
 * token, disable continuation, and never retry — a refresh is best-effort and
 * must not fight the real request for retries or write a second provider
 * thread state.
 */
data class WarmRequestOptions(
    val maxTokens: Int,
    val disableContinuation: Boolean,
    val maxRetries: Int,
)

object PromptCacheWarmer {

    // ── Constants (pi cache-warming.ts:31-49) ───────────────────────────────

    /** Streaming warming never continues past this long after the real request that started it. */
    const val MAX_AGE_MS: Long = 60 * 60_000L

    /** Idle warming uses a shorter horizon; continuation estimates degrade with age. */
    const val MAX_IDLE_AGE_MS: Long = 30 * 60_000L

    /** A refresh is sent only when it is expected to save at least this many dollars. */
    const val MINIMUM_EXPECTED_SAVINGS_USD: Double = 0.05

    /**
     * Chance that a real request arrives before the entry expires while the
     * agent sits idle. Measured from pi's own usage; per-session estimates were
     * not better than this constant (cache-warming.ts:39-43).
     */
    const val IDLE_CONTINUATION_PROBABILITY: Double = 0.15

    /**
     * Refresh when this fraction of the TTL has elapsed, preserving at least
     * ten seconds of margin (pi getCacheWarmingDelayMs, cache-warming.ts:46-49).
     * Expressed as remaining fraction: [TTL_REMAINING_REFRESH_FRACTION]. The
     * two constants are written literally (not `1 - x`) because a binary
     * `1.0 - 0.9` lands at 0.0999… and would flip the `<=` boundary at exactly
     * 10% remaining.
     */
    const val TTL_REFRESH_ELAPSED_FRACTION: Double = 0.9
    const val TTL_REMAINING_REFRESH_FRACTION: Double = 0.1
    const val MIN_TTL_MARGIN_MS: Long = 10_000L

    /** pi cache-warming.ts:46 — a TTL this short is never worth warming. */
    const val MIN_WARMABLE_TTL_MS: Long = 10_000L

    // ── TTL bookkeeping numbers ─────────────────────────────────────────────

    /**
     * Prompt cache TTL (seconds) per provider family and retention tier.
     *
     * Source of the shape: pi `ModelPromptCacheSchema` (ai/src/providers/
     * model-schema.ts — "Best-effort prompt cache lifetime in seconds for the
     * short and long retention tiers. Pi warms only tiers with a known
     * lifetime"). Neither pi's bundled catalogs nor our model table
     * (ProviderModelEntryEntity/Model) populate a per-model TTL, so per the
     * port task this table maps provider FAMILIES to documented vendor values:
     *  - anthropic: 5 min default, 1 h with the extended-TTL cache_control tier
     *    (Anthropic "Prompt caching" docs).
     *  - openai (incl. OpenAI-compatible relays): prompt cache entries are
     *    evicted after 5-10 min of inactivity (OpenAI "Prompt caching" guide);
     *    600 s is the conservative end, no long tier is honored.
     * Unknown families return null — pi warms only tiers with a known lifetime.
     */
    private val TTL_SECONDS_BY_FAMILY: Map<String, Pair<Long, Long>> = mapOf(
        FAMILY_ANTHROPIC to (300L to 3_600L),
        FAMILY_OPENAI to (600L to 600L),
    )

    // ── TTL lookup ──────────────────────────────────────────────────────────

    /**
     * Map a stored `ProviderType` rawValue (e.g. `openAI`, `anthropic`) to a
     * warming family key, or null when the family has no known prompt cache.
     * Raw values are user-entered relays' business, so matching is by lowercase
     * substring, not equality.
     */
    fun familyOf(providerTypeRaw: String?): String? {
        val raw = providerTypeRaw?.lowercase() ?: return null
        return when {
            raw.contains("anthropic") || raw.contains("claude") -> FAMILY_ANTHROPIC
            raw.contains("openai") || raw.contains("azure") || raw.contains("gpt") -> FAMILY_OPENAI
            else -> null
        }
    }

    /**
     * Lifetime in ms of the prompt cache entry a request writes, for the
     * retention the request used (pi getPromptCacheTtlMs, cache-warming.ts:55-62).
     * Null when caching is off for the family ("none") or no lifetime is known.
     */
    fun promptCacheTtlMs(providerTypeRaw: String?, retention: String = "short"): Long? {
        if (retention == "none") return null
        val family = familyOf(providerTypeRaw) ?: return null
        val seconds = TTL_SECONDS_BY_FAMILY[family]?.let { (short, long) ->
            if (retention == "long") long else short
        } ?: return null
        return seconds * 1_000L
    }

    /**
     * Delay until a refresh should be sent (pi getCacheWarmingDelayMs,
     * cache-warming.ts:46-49): at 90% of the TTL, preserving >= 10 s of margin;
     * null when the TTL is too short to be worth warming at all.
     */
    fun getCacheWarmingDelayMs(ttlMs: Long): Long? {
        if (ttlMs <= MIN_WARMABLE_TTL_MS) return null
        return maxOf(1L, minOf((ttlMs * TTL_REFRESH_ELAPSED_FRACTION).toLong(), ttlMs - MIN_TTL_MARGIN_MS))
    }

    /** True when warming for [phase] has aged past its horizon (pi MAX_AGE/MAX_IDLE_AGE). */
    fun isWarmingTooOld(lastRequestAtMs: Long, nowMs: Long, phase: WarmingPhase): Boolean {
        val age = nowMs - lastRequestAtMs
        val maxAge = if (phase == WarmingPhase.IDLE) MAX_IDLE_AGE_MS else MAX_AGE_MS
        return age > maxAge
    }

    // ── Warm request hygiene (pi cache-warming.ts:78-80) ────────────────────

    /** The refresh options: one output token, no continuation, no retries. */
    fun warmRequestOptions(): WarmRequestOptions = WarmRequestOptions(
        maxTokens = 1,
        disableContinuation = true,
        maxRetries = 0,
    )

    // ── Replay ban for request-shape-derived thinking (pi cache-warming.ts:64-72) ────

    /** Warming family key [familyOf] assigns to Anthropic-shaped providers. */
    const val FAMILY_ANTHROPIC = "anthropic"

    /** Warming family key [familyOf] assigns to OpenAI-shaped providers (incl. relays). */
    const val FAMILY_OPENAI = "openai"

    /**
     * Whether replaying the request with a one-token output cap leaves its
     * cache entry untouched.
     *
     * Anthropic: NO for every thinking-enabled request. The real rule is pi's
     * three-condition ban (`model.api == "anthropic-messages" &&
     * options.reasoning && !forceAdaptiveThinking`) widened to ignore pi's
     * adaptive-thinking escape hatch, because in THIS codebase
     * [com.openminis.app.provider.anthropic.AnthropicProvider.thinkingBudget]
     * derives `budget_tokens` from the REQUEST's `max_tokens` on ALL enabled
     * thinking levels — and a warm replay runs with `maxTokens = 1`
     * ([warmRequestOptions]), below the budget floor, so the replayed request
     * carries a different (empty) thinking shape. Anthropic keys the message
     * cache on the request shape, so the paid replay would MISS: warm at your
     * own cost and still read nothing back. `forceThinking` does NOT restore
     * replayability — it only marks that the provider forces thinking past its
     * catalog ceiling, which changes nothing about the derivation above.
     *
     * Non-Anthropic families: replayable unless `forceThinking` — the ported
     * budget-style marker ([com.openminis.app.provider.LLMProvider
     * .forcesThinkingWhenEnabled]) — says the provider pins thinking onto
     * enabled requests, meaning the model could still think for thousands of
     * tokens past the one-token cap.
     *
     * @param providerFamily warming family key from [familyOf] (null = unknown
     *   family: only the forcing rule applies)
     * @param thinkingEnabled whether the recorded request ran with an enabled
     *   [com.openminis.app.data.model.ThinkingLevel]
     * @param forceThinking the provider's `forcesThinkingWhenEnabled` flag at
     *   the time the recorded request was sent
     */
    fun isCacheWarmingReplayable(
        providerFamily: String?,
        thinkingEnabled: Boolean,
        forceThinking: Boolean,
    ): Boolean {
        if (providerFamily == FAMILY_ANTHROPIC && thinkingEnabled) return false
        return !forceThinking
    }

    // ── Economics (pi evaluateCacheWarming, cache-warming.ts:83-107) ────────

    /**
     * Decide whether refreshing the cache entry of a `promptTokens`-token
     * request pays for itself in [phase]. Per-token USD rates in; the pi cost
     * model is `calculateCost(model, usage)` — callers supply the resolved
     * rates instead of the model object.
     */
    fun evaluateWarming(
        promptTokens: Int,
        cacheReadPerTokenUsd: Double,
        cacheWritePerTokenUsd: Double,
        inputPerTokenUsd: Double,
        outputPerTokenUsd: Double,
        phase: WarmingPhase,
    ): WarmingDecision {
        val cacheHitCost = cacheReadPerTokenUsd * promptTokens
        val cacheMissCost = if (cacheWritePerTokenUsd > 0) {
            cacheWritePerTokenUsd * promptTokens
        } else {
            inputPerTokenUsd * promptTokens
        }
        val warmCost = cacheReadPerTokenUsd * promptTokens + outputPerTokenUsd
        val missCost = maxOf(0.0, cacheMissCost - cacheHitCost)
        val continuationProbability =
            if (phase == WarmingPhase.IDLE) IDLE_CONTINUATION_PROBABILITY else 1.0
        val economicsAvailable = promptTokens > 0 && (cacheHitCost > 0 || cacheMissCost > 0)
        val expectedSavings = continuationProbability * missCost - warmCost
        val action = if (expectedSavings >= MINIMUM_EXPECTED_SAVINGS_USD) WarmingAction.WARM else WarmingAction.STOP
        return WarmingDecision(
            phase = phase,
            warmCost = warmCost,
            missCost = missCost,
            continuationProbability = continuationProbability,
            expectedSavings = expectedSavings,
            economicsAvailable = economicsAvailable,
            action = action,
        )
    }

    /**
     * The standalone warm-or-stop gate over raw numbers (pi cache-warming.ts:97-105):
     * savings scale by the continuation probability (`idleChatProbability`,
     * pass 1.0 for the streaming phase), and a refresh is only considered once
     * [TTL_REFRESH_ELAPSED_FRACTION] of the entry's TTL has elapsed (the 90%
     * refresh point — same rule as [getCacheWarmingDelayMs], viewed from the
     * remaining-fraction side the ledger reports). [expectedSavingsUsd] is RAW
     * (missCost - warmCost, unscaled — [evaluateWarming] folds the probability
     * into its own output). Unknown TTL remaining counts as due: the caller's
     * TTL lookup already answers "can we warm at all".
     */
    fun shouldWarm(
        expectedSavingsUsd: Double,
        thresholdUsd: Double = MINIMUM_EXPECTED_SAVINGS_USD,
        idleChatProbability: Double = IDLE_CONTINUATION_PROBABILITY,
        ttlRemainingPct: Double?,
    ): Boolean {
        val refreshDue = ttlRemainingPct == null || ttlRemainingPct <= TTL_REMAINING_REFRESH_FRACTION
        val adjustedSavings = idleChatProbability * expectedSavingsUsd
        return refreshDue && adjustedSavings >= thresholdUsd
    }
}

/**
 * TTL bookkeeping for warm decisions, per (provider instance, model id) — the
 * task's item 1: remember when the last real request ran against that pair and
 * with which prompt-cache TTL, so the warm hook can ask "how much of the
 * entry's lifetime is left" without touching storage. Bounded naturally by the
 * number of (instance, model) pairs in active use (dozens at most).
 *
 * Thread-safe; pure JVM, injectable `nowMs` for tests.
 */
class PromptCacheTtlLedger {

    private data class Entry(val lastRequestAtMs: Long, val ttlMs: Long?)

    private val entries = ConcurrentHashMap<String, Entry>()

    private fun key(providerInstanceId: String?, modelId: String): String =
        "${providerInstanceId ?: "-"}|$modelId"

    /** Record one real request against (instance, model); [ttlMs] null = TTL unknown for that family. */
    fun recordRequest(providerInstanceId: String?, modelId: String, ttlMs: Long?, atMs: Long) {
        entries[key(providerInstanceId, modelId)] = Entry(lastRequestAtMs = atMs, ttlMs = ttlMs)
    }

    /**
     * Fraction (0..1) of the recorded TTL still remaining at [nowMs]; null when
     * the pair was never recorded or its TTL is unknown (caller treats null as
     * "economics unknown", same convention as [PromptCacheWarmer.shouldWarm]).
     */
    fun ttlRemainingPct(providerInstanceId: String?, modelId: String, nowMs: Long): Double? {
        val entry = entries[key(providerInstanceId, modelId)] ?: return null
        val ttl = entry.ttlMs ?: return null
        if (ttl <= 0) return null
        val elapsed = nowMs - entry.lastRequestAtMs
        val remaining = ttl - elapsed
        if (remaining <= 0) return 0.0
        return remaining.toDouble() / ttl.toDouble()
    }

    /** When the next refresh becomes due (pi getCacheWarmingDelayMs applied to the recorded TTL), or null. */
    fun nextWarmDueAtMs(providerInstanceId: String?, modelId: String): Long? {
        val entry = entries[key(providerInstanceId, modelId)] ?: return null
        val ttl = entry.ttlMs ?: return null
        val delay = PromptCacheWarmer.getCacheWarmingDelayMs(ttl) ?: return null
        return entry.lastRequestAtMs + delay
    }

    /**
     * The TTL the pair's last request was recorded with, or null when the pair
     * was never recorded or its family has no known lifetime. Read together
     * with [nextWarmDueAtMs]: a non-null TTL with a null due point means the
     * TTL is KNOWN but at or below [PromptCacheWarmer.MIN_WARMABLE_TTL_MS] —
     * the "ttl-too-short" case the planner reports separately from
     * "ttl-unknown".
     */
    fun recordedTtlMs(providerInstanceId: String?, modelId: String): Long? =
        entries[key(providerInstanceId, modelId)]?.ttlMs

    /** Drop one pair (provider instance deleted / model switched away). */
    fun clear(providerInstanceId: String?, modelId: String) {
        entries.remove(key(providerInstanceId, modelId))
    }
}
