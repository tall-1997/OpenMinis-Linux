package com.openminis.app.provider

import com.openminis.app.provider.PromptCacheWarmer.MINIMUM_EXPECTED_SAVINGS_USD
import com.openminis.app.provider.PromptCacheWarmer.evaluateWarming
import com.openminis.app.provider.PromptCacheWarmer.familyOf
import com.openminis.app.provider.PromptCacheWarmer.getCacheWarmingDelayMs
import com.openminis.app.provider.PromptCacheWarmer.isCacheWarmingReplayable
import com.openminis.app.provider.PromptCacheWarmer.isWarmingTooOld
import com.openminis.app.provider.PromptCacheWarmer.promptCacheTtlMs
import com.openminis.app.provider.PromptCacheWarmer.shouldWarm
import com.openminis.app.provider.PromptCacheWarmer.warmRequestOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port-fidelity tests for the prompt cache warming policy (pi
 * ai/src/cache-warming.ts:31-107). Covers: TTL lookup per provider family, the
 * 90%-of-TTL refresh delay, the warm-or-stop economics with the $0.05 boundary,
 * idle vs streaming continuation, warm request hygiene, the thinking replay
 * ban, and the TTL bookkeeping ledger.
 */
class PromptCacheWarmerTest {

    // Rates roughly mirroring a frontier model: $0.10/M cache read, $1.25/M
    // cache write, $3/M input, $15/M output.
    private val cacheRead = 0.0000001
    private val cacheWrite = 0.00000125
    private val input = 0.000003
    private val output = 0.000015

    // ── TTL lookup ──────────────────────────────────────────────────────────

    @Test
    fun `anthropic has a 5min short and 1h long tier`() {
        assertEquals(300_000L, promptCacheTtlMs("anthropic", "short"))
        assertEquals(3_600_000L, promptCacheTtlMs("anthropic", "long"))
    }

    @Test
    fun `openai-compatible family uses the conservative 10min tier`() {
        assertEquals(600_000L, promptCacheTtlMs("openAI", "short"))
        assertEquals(600_000L, promptCacheTtlMs("openAI", "long"))
        assertEquals(600_000L, promptCacheTtlMs("azure-openai", "short"))
    }

    @Test
    fun `unknown families and disabled retention have no TTL`() {
        assertNull(promptCacheTtlMs("google", "short"))
        assertNull(promptCacheTtlMs("deepSeek", "short"))
        assertNull(promptCacheTtlMs(null, "short"))
        assertNull(promptCacheTtlMs("anthropic", "none"))
    }

    @Test
    fun `familyOf normalizes raw provider type values`() {
        assertEquals("anthropic", familyOf("anthropic"))
        assertEquals("openai", familyOf("openAI"))
        assertEquals("openai", familyOf("MyGptRelay"))
        assertNull(familyOf("google"))
        assertNull(familyOf(null))
    }

    // ── refresh scheduling (pi getCacheWarmingDelayMs) ──────────────────────

    @Test
    fun `refresh delay is 90 percent of the TTL`() {
        assertEquals(270_000L, getCacheWarmingDelayMs(300_000L))
    }

    @Test
    fun `refresh delay keeps the ten second margin`() {
        // min(ttl*0.9, ttl-10s): the margin wins near the floor.
        assertEquals(20_000L, getCacheWarmingDelayMs(30_000L))
        assertEquals(5_000L, getCacheWarmingDelayMs(15_000L))
    }

    @Test
    fun `a TTL at or below ten seconds is never warmable`() {
        assertNull(getCacheWarmingDelayMs(10_000L))
        assertEquals(1L, getCacheWarmingDelayMs(10_001L))
    }

    // ── shouldWarm gate (task boundaries) ───────────────────────────────────

    @Test
    fun `expectedSavings just below the threshold is rejected`() {
        // Due-zone remaining so the rejection is decided by the ECONOMICS
        // (0.049 < 0.05), not by the refresh gate.
        assertFalse(shouldWarm(0.049, idleChatProbability = 1.0, ttlRemainingPct = 0.05))
    }

    @Test
    fun `expectedSavings just above the threshold is accepted`() {
        // ttlRemainingPct must sit in the DUE zone (<= 0.1 remaining, the 90%
        // point pinned by `refresh is gated at the 90 percent point`) so this
        // test exercises the ECONOMICS boundary, not the refresh gate.
        assertTrue(shouldWarm(0.051, idleChatProbability = 1.0, ttlRemainingPct = 0.05))
    }

    @Test
    fun `default threshold is pi's five cents`() {
        assertEquals(0.05, MINIMUM_EXPECTED_SAVINGS_USD, 0.0)
    }

    @Test
    fun `idle probability scales savings down before the threshold`() {
        // 0.40 raw * 0.15 idle = 0.06 >= 0.05 -> warm; 0.30 raw * 0.15 = 0.045 -> stop.
        // Due-zone remaining (0.05) so the idle-economics boundary is what decides.
        assertTrue(shouldWarm(0.40, idleChatProbability = PromptCacheWarmer.IDLE_CONTINUATION_PROBABILITY, ttlRemainingPct = 0.05))
        assertFalse(shouldWarm(0.30, idleChatProbability = PromptCacheWarmer.IDLE_CONTINUATION_PROBABILITY, ttlRemainingPct = 0.05))
    }

    @Test
    fun `refresh is gated at the 90 percent point of the TTL`() {
        // 95% of the TTL still remaining -> not due, however good the economics.
        assertFalse(shouldWarm(5.0, idleChatProbability = 1.0, ttlRemainingPct = 0.95))
        assertTrue(shouldWarm(5.0, idleChatProbability = 1.0, ttlRemainingPct = 0.10))
        assertTrue(shouldWarm(5.0, idleChatProbability = 1.0, ttlRemainingPct = 0.05))
        // Unknown remaining counts as due; the TTL lookup gates warmability.
        assertTrue(shouldWarm(5.0, idleChatProbability = 1.0, ttlRemainingPct = null))
    }

    // ── evaluateWarming (pi evaluateCacheWarming) ───────────────────────────

    @Test
    fun `streaming phase uses continuation probability one`() {
        val decision = evaluateWarming(1_000_000, cacheRead, cacheWrite, input, output, WarmingPhase.STREAMING)
        assertEquals(1.0, decision.continuationProbability, 0.0)
        assertEquals(0.1 + output, decision.warmCost, 1e-12)
        assertEquals(1.25 - 0.1, decision.missCost, 1e-12)
        assertEquals(1.15 - (0.1 + output), decision.expectedSavings, 1e-12)
        assertEquals(WarmingAction.WARM, decision.action)
        assertTrue(decision.economicsAvailable)
    }

    @Test
    fun `idle phase applies the 0_15 continuation probability`() {
        val decision = evaluateWarming(1_000_000, cacheRead, cacheWrite, input, output, WarmingPhase.IDLE)
        assertEquals(0.15, decision.continuationProbability, 0.0)
        assertEquals(0.15 * 1.15 - (0.1 + output), decision.expectedSavings, 1e-12)
        assertEquals(WarmingAction.WARM, decision.action)
    }

    @Test
    fun `small prompts stop when idle`() {
        val decision = evaluateWarming(100_000, cacheRead, cacheWrite, input, output, WarmingPhase.IDLE)
        assertTrue(decision.expectedSavings < MINIMUM_EXPECTED_SAVINGS_USD)
        assertEquals(WarmingAction.STOP, decision.action)
    }

    @Test
    fun `missing cache write pricing falls back to input pricing`() {
        val decision = evaluateWarming(1_000_000, cacheRead, 0.0, input, output, WarmingPhase.STREAMING)
        // pi cache-warming.ts:91-96: cacheWrite <= 0 prices the miss at INPUT,
        // so missCost = (input - cacheRead) * promptTokens — WHOLE-request
        // dollars, not a per-token rate. The delta only absorbs double
        // rounding (the formula itself is pi-exact; no accumulation cascade).
        assertEquals((input - cacheRead) * 1_000_000, decision.missCost, 1e-9)
        assertEquals(WarmingAction.WARM, decision.action)
    }

    @Test
    fun `economics are unavailable without tokens or prices`() {
        assertFalse(evaluateWarming(0, cacheRead, cacheWrite, input, output, WarmingPhase.IDLE).economicsAvailable)
        assertFalse(evaluateWarming(1_000, 0.0, 0.0, 0.0, 0.0, WarmingPhase.IDLE).economicsAvailable)
        assertEquals(WarmingAction.STOP, evaluateWarming(0, cacheRead, cacheWrite, input, output, WarmingPhase.IDLE).action)
    }

    @Test
    fun `a cheaper cache write than read never yields negative miss cost`() {
        val decision = evaluateWarming(1_000_000, 0.01, 0.001, 0.0, 0.0, WarmingPhase.STREAMING)
        assertEquals(0.0, decision.missCost, 0.0)
    }

    // ── warm request hygiene (pi getCacheWarmingRequestOptions) ─────────────

    @Test
    fun `warm request carries the hygiene triple`() {
        val options = warmRequestOptions()
        assertEquals(1, options.maxTokens)
        assertTrue(options.disableContinuation)
        assertEquals(0, options.maxRetries)
    }

    // ── thinking replay ban (pi isCacheWarmingReplayable) ────────────────────

    @Test
    fun `anthropic thinking sessions are not replayable regardless of forcing`() {
        // AnthropicProvider.thinkingBudget derives budget_tokens from the
        // REQUEST's maxTokens on every enabled level; the maxTokens=1 warm
        // replay runs below the budget floor, so the request shape — which the
        // message cache keys on — changes and the paid replay misses. pi's
        // forceAdaptiveThinking escape hatch does NOT apply: forcing thinking
        // past the catalog ceiling changes nothing about the derivation.
        assertFalse(isCacheWarmingReplayable("anthropic", thinkingEnabled = true, forceThinking = false))
        assertFalse(isCacheWarmingReplayable("anthropic", thinkingEnabled = true, forceThinking = true))
    }

    @Test
    fun `anthropic with thinking off stays replayable`() {
        // No enabled level -> no budget derivation -> the replay shape matches
        // the recorded request.
        assertTrue(isCacheWarmingReplayable("anthropic", thinkingEnabled = false, forceThinking = false))
    }

    @Test
    fun `non-anthropic families keep the budget-forcing rule only`() {
        // OpenAI-family relays do not derive a budget from maxTokens, so a
        // thinking-enabled recorded request still replays to the same shape.
        assertTrue(isCacheWarmingReplayable("openai", thinkingEnabled = true, forceThinking = false))
        assertTrue(isCacheWarmingReplayable(null, thinkingEnabled = true, forceThinking = false))
        // The forcing marker alone still bans: the model may think for
        // thousands of tokens past the one-token cap whatever the family.
        assertFalse(isCacheWarmingReplayable("openai", thinkingEnabled = true, forceThinking = true))
        assertFalse(isCacheWarmingReplayable(null, thinkingEnabled = false, forceThinking = true))
        assertFalse(isCacheWarmingReplayable("anthropic", thinkingEnabled = false, forceThinking = true))
    }

    // ── age horizons ────────────────────────────────────────────────────────

    @Test
    fun `warming ages out per phase`() {
        val startedAt = 1_000_000L
        // Streaming tolerates 60 min, idle only 30 min.
        assertFalse(isWarmingTooOld(startedAt, startedAt + PromptCacheWarmer.MAX_AGE_MS, WarmingPhase.STREAMING))
        assertTrue(isWarmingTooOld(startedAt, startedAt + PromptCacheWarmer.MAX_AGE_MS + 1, WarmingPhase.STREAMING))
        assertTrue(isWarmingTooOld(startedAt, startedAt + PromptCacheWarmer.MAX_IDLE_AGE_MS + 1, WarmingPhase.IDLE))
        assertFalse(isWarmingTooOld(startedAt, startedAt + PromptCacheWarmer.MAX_IDLE_AGE_MS, WarmingPhase.IDLE))
    }

    // ── TTL bookkeeping ledger ──────────────────────────────────────────────

    @Test
    fun `ledger tracks remaining TTL per instance and model`() {
        val ledger = PromptCacheTtlLedger()
        val t0 = 10_000_000L
        ledger.recordRequest("inst-1", "claude-x", promptCacheTtlMs("anthropic", "short"), t0)
        assertEquals(1.0, ledger.ttlRemainingPct("inst-1", "claude-x", t0)!!, 1e-9)
        assertEquals(0.5, ledger.ttlRemainingPct("inst-1", "claude-x", t0 + 150_000)!!, 1e-9)
        assertEquals(0.0, ledger.ttlRemainingPct("inst-1", "claude-x", t0 + 300_000)!!, 1e-9)
        // Expired past the horizon clamps at zero instead of going negative.
        assertEquals(0.0, ledger.ttlRemainingPct("inst-1", "claude-x", t0 + 900_000)!!, 1e-9)
    }

    @Test
    fun `same model on a different provider instance books separately`() {
        val ledger = PromptCacheTtlLedger()
        val t0 = 10_000_000L
        ledger.recordRequest("inst-1", "gpt-y", 600_000L, t0)
        assertNull(ledger.ttlRemainingPct("inst-2", "gpt-y", t0 + 1_000))
        ledger.recordRequest("inst-2", "gpt-y", 600_000L, t0)
        // The remaining fraction decays with elapsed time (ledger semantics):
        // 1s into a 600s TTL it is genuinely 599/600 — a math value, not a
        // float artifact — and it is exactly 1.0 at the request instant.
        assertEquals(599.0 / 600.0, ledger.ttlRemainingPct("inst-2", "gpt-y", t0 + 1_000)!!, 1e-9)
        assertEquals(1.0, ledger.ttlRemainingPct("inst-2", "gpt-y", t0)!!, 1e-9)
    }

    @Test
    fun `ledger reports null for unknown pairs and unknown TTL`() {
        val ledger = PromptCacheTtlLedger()
        val t0 = 10_000_000L
        assertNull(ledger.ttlRemainingPct("inst-1", "never-seen", t0))
        ledger.recordRequest("inst-1", "relay-model", null, t0)
        assertNull(ledger.ttlRemainingPct("inst-1", "relay-model", t0))
    }

    @Test
    fun `next warm due point applies the 90 percent delay`() {
        val ledger = PromptCacheTtlLedger()
        val t0 = 10_000_000L
        ledger.recordRequest("inst-1", "claude-x", 300_000L, t0)
        assertEquals(t0 + 270_000L, ledger.nextWarmDueAtMs("inst-1", "claude-x"))
        // TTL unknown or too short -> never due.
        ledger.recordRequest("inst-1", "relay-model", null, t0)
        assertNull(ledger.nextWarmDueAtMs("inst-1", "relay-model"))
        ledger.recordRequest("inst-1", "tiny", 10_000L, t0)
        assertNull(ledger.nextWarmDueAtMs("inst-1", "tiny"))
    }

    @Test
    fun `ledger clear drops one pair only`() {
        val ledger = PromptCacheTtlLedger()
        val t0 = 10_000_000L
        ledger.recordRequest("inst-1", "claude-x", 300_000L, t0)
        ledger.recordRequest("inst-1", "gpt-y", 600_000L, t0)
        ledger.clear("inst-1", "claude-x")
        assertNull(ledger.ttlRemainingPct("inst-1", "claude-x", t0))
        assertEquals(1.0, ledger.ttlRemainingPct("inst-1", "gpt-y", t0)!!, 1e-9)
    }

    @Test
    fun `ledger and shouldWarm compose end to end`() {
        val ledger = PromptCacheTtlLedger()
        val t0 = 10_000_000L
        ledger.recordRequest("inst-1", "claude-x", 300_000L, t0)
        val remaining = ledger.ttlRemainingPct("inst-1", "claude-x", t0 + 280_000)!!
        // 90% elapsed -> due; raw savings 1.15 - warm at idle continuation.
        val economics = evaluateWarming(1_000_000, cacheRead, cacheWrite, input, output, WarmingPhase.IDLE)
        val rawSavings = economics.missCost - economics.warmCost
        assertTrue(shouldWarm(rawSavings, idleChatProbability = PromptCacheWarmer.IDLE_CONTINUATION_PROBABILITY, ttlRemainingPct = remaining))
    }
}
