package com.openminis.app.ui.chat

import com.openminis.app.provider.PromptCacheTtlLedger
import com.openminis.app.provider.PromptCacheWarmRates
import com.openminis.app.provider.PromptCacheWarmer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-prompt-cache-warming] Wiring-layer tests for the warm trigger: the
 * trigger-condition combination matrix (prefs off → never; TTL not due →
 * never; economics short → never; plus the streaming / replay-ban / aged-out
 * / pricing gates), the ledger bookkeeping idempotency the coordinator relies
 * on, and the models.dev rates side-car parsing that feeds the economics.
 */
class PromptCacheWarmPlannerTest {

    // Rates roughly mirroring a frontier model: $3/M input, $15/M output,
    // $0.30/M cache read, $3.75/M cache write — per token.
    private val sampleRates = PromptCacheWarmRates.Rates(
        inputPerTokenUsd = 0.000003,
        outputPerTokenUsd = 0.000015,
        cacheReadPerTokenUsd = 0.0000003,
        cacheWritePerTokenUsd = 0.00000375,
    )

    private val t0 = 10_000_000L

    /** Happy-path args: enabled, idle, replayable, due, aged-in, big prompt, priced. */
    private fun happy(
        enabled: Boolean = true,
        streamingActive: Boolean = false,
        forceThinking: Boolean = false,
        providerFamily: String? = "openai",
        thinkingEnabled: Boolean = false,
        ttlRemainingPct: Double? = 0.05,
        lastRequestAtMs: Long = t0,
        recordedTtlMs: Long? = 300_000L,
        dueAtMs: Long? = t0 + 270_000L,
        nowMs: Long = t0 + 280_000L,
        promptTokens: Int = 1_000_000,
        rates: PromptCacheWarmRates.Rates? = sampleRates,
    ) = PromptCacheWarmPlanner.decide(
        enabled = enabled,
        streamingActive = streamingActive,
        forceThinking = forceThinking,
        providerFamily = providerFamily,
        thinkingEnabled = thinkingEnabled,
        rates = rates,
        promptTokens = promptTokens,
        ttlRemainingPct = ttlRemainingPct,
        lastRequestAtMs = lastRequestAtMs,
        recordedTtlMs = recordedTtlMs,
        dueAtMs = dueAtMs,
        nowMs = nowMs,
    )

    // ── trigger-condition combinations ──────────────────────────────────────

    @Test
    fun `flag off never triggers regardless of everything else`() {
        val decision = happy(enabled = false, ttlRemainingPct = null, promptTokens = 0, rates = null)
        assertTrue(decision is WarmDecision.Stop)
        assertEquals("prefs-disabled", (decision as WarmDecision.Stop).reason)
    }

    @Test
    fun `TTL not yet due does not trigger`() {
        val decision = happy(ttlRemainingPct = 0.95, dueAtMs = t0 + 270_000L, nowMs = t0 + 1_000L)
        assertTrue(decision is WarmDecision.Stop)
        assertEquals("not-due", (decision as WarmDecision.Stop).reason)
    }

    @Test
    fun `economics below the threshold do not trigger`() {
        // A tiny prompt cannot pay for its own refresh (warmCost ~ output of 1
        // token alone exceeds the idle-scaled miss savings).
        val decision = happy(promptTokens = 100)
        assertTrue(decision is WarmDecision.Stop)
        assertEquals("economics-unprofitable", (decision as WarmDecision.Stop).reason)
    }

    @Test
    fun `economics at or above the threshold trigger with the ledger delay`() {
        // 1M-token prompt: miss ≈ (3.75 - 0.30) = $3.45, warm ≈ 0.30 + 0.000015.
        // Idle continuation 0.15 × 3.45 - 0.30 ≈ 0.2175 >= 0.05 → WARM.
        val decision = happy()
        assertTrue("$decision", decision is WarmDecision.Warm)
        assertEquals(0L, (decision as WarmDecision.Warm).delayMs)
    }

    @Test
    fun `future due point becomes the warm delay`() {
        val decision = happy(dueAtMs = t0 + 280_000L, nowMs = t0 + 250_000L, ttlRemainingPct = 0.10)
        assertTrue("$decision", decision is WarmDecision.Warm)
        assertEquals(30_000L, (decision as WarmDecision.Warm).delayMs)
    }

    @Test
    fun `unknown TTL family never triggers`() {
        // Null due point AND null recorded TTL: the ledger never learned a
        // lifetime for this pair (unknown family or never recorded).
        val decision = happy(ttlRemainingPct = null, recordedTtlMs = null, dueAtMs = null)
        assertTrue(decision is WarmDecision.Stop)
        assertEquals("ttl-unknown", (decision as WarmDecision.Stop).reason)
    }

    @Test
    fun `a known but non-warmable TTL reports ttl-too-short not ttl-unknown`() {
        // The ledger DID record a TTL, but getCacheWarmingDelayMs refuses it:
        // anything at or below MIN_WARMABLE_TTL_MS (10s) has no warm schedule.
        // That is a value problem, not a lookup gap — it must not be tagged
        // as the unknown-family case (review P3: diagnostic quality).
        val belowFloor = happy(ttlRemainingPct = null, recordedTtlMs = 5_000L, dueAtMs = null)
        assertTrue(belowFloor is WarmDecision.Stop)
        assertEquals("ttl-too-short", (belowFloor as WarmDecision.Stop).reason)

        // Exactly at the floor is also refused (`<=` in getCacheWarmingDelayMs).
        val atFloor = happy(ttlRemainingPct = null, recordedTtlMs = PromptCacheWarmer.MIN_WARMABLE_TTL_MS, dueAtMs = null)
        assertEquals("ttl-too-short", (atFloor as WarmDecision.Stop).reason)
    }

    @Test
    fun `an active stream never triggers`() {
        val decision = happy(streamingActive = true)
        assertTrue(decision is WarmDecision.Stop)
        assertEquals("streaming-active", (decision as WarmDecision.Stop).reason)
    }

    @Test
    fun `budget-style thinking is replay-banned`() {
        val decision = happy(forceThinking = true)
        assertTrue(decision is WarmDecision.Stop)
        assertEquals("budget-thinking-replay-ban", (decision as WarmDecision.Stop).reason)
    }

    @Test
    fun `anthropic thinking sessions are replay-banned`() {
        // thinkingBudget derives budget_tokens from the request maxTokens on
        // every enabled Anthropic level; the maxTokens=1 warm replay changes
        // the request shape and the paid replay would miss.
        val decision = happy(providerFamily = "anthropic", thinkingEnabled = true)
        assertTrue(decision is WarmDecision.Stop)
        assertEquals("thinking-replay-ban", (decision as WarmDecision.Stop).reason)
    }

    @Test
    fun `anthropic with thinking off still triggers`() {
        val decision = happy(providerFamily = "anthropic", thinkingEnabled = false)
        assertTrue("$decision", decision is WarmDecision.Warm)
    }

    @Test
    fun `non-anthropic thinking sessions still trigger`() {
        // OpenAI-family relays do not derive a budget from maxTokens, so the
        // one-token replay keeps the recorded request shape.
        val decision = happy(providerFamily = "openai", thinkingEnabled = true)
        assertTrue("$decision", decision is WarmDecision.Warm)
        val unknownFamily = happy(providerFamily = null, thinkingEnabled = true)
        assertTrue("$unknownFamily", unknownFamily is WarmDecision.Warm)
    }

    @Test
    fun `warming past the idle horizon ages out`() {
        val decision = happy(lastRequestAtMs = t0, nowMs = t0 + PromptCacheWarmer.MAX_IDLE_AGE_MS + 1)
        assertTrue(decision is WarmDecision.Stop)
        assertEquals("aged-out", (decision as WarmDecision.Stop).reason)
    }

    @Test
    fun `a model without catalog pricing never triggers`() {
        val decision = happy(rates = null)
        assertTrue(decision is WarmDecision.Stop)
        assertEquals("pricing-unavailable", (decision as WarmDecision.Stop).reason)
    }

    @Test
    fun `missing usage never triggers`() {
        val decision = happy(promptTokens = 0)
        assertTrue(decision is WarmDecision.Stop)
        assertEquals("prompt-tokens-unknown", (decision as WarmDecision.Stop).reason)
    }

    // ── ledger bookkeeping the coordinator relies on ────────────────────────

    @Test
    fun `re-recording the same pair is idempotent and resets the clock`() {
        val ledger = PromptCacheTtlLedger()
        // Two turns 60s apart against the same (instance, model): the second
        // record REPLACES the first — one entry, refreshed due point.
        ledger.recordRequest("inst-1", "claude-x", 300_000L, t0)
        ledger.recordRequest("inst-1", "claude-x", 300_000L, t0 + 60_000L)
        assertEquals(t0 + 60_000L + 270_000L, ledger.nextWarmDueAtMs("inst-1", "claude-x"))
        // Remaining fraction decays from the LATEST request, not the first.
        assertEquals(1.0, ledger.ttlRemainingPct("inst-1", "claude-x", t0 + 60_000L)!!, 1e-9)
    }

    @Test
    fun `recordedTtlMs separates unknown-family pairs from too-short pairs`() {
        val ledger = PromptCacheTtlLedger()
        // Unknown family (ttl null) and never-recorded pairs both read null —
        // the planner's "ttl-unknown" evidence.
        ledger.recordRequest("inst-1", "relay-model", null, t0)
        assertNull(ledger.recordedTtlMs("inst-1", "relay-model"))
        assertNull(ledger.recordedTtlMs("inst-1", "never-seen"))
        // A tiny KNOWN ttl reads back non-null while its due point is null —
        // exactly the planner's "ttl-too-short" evidence.
        ledger.recordRequest("inst-1", "tiny", 5_000L, t0)
        assertEquals(5_000L, ledger.recordedTtlMs("inst-1", "tiny"))
        assertNull(ledger.nextWarmDueAtMs("inst-1", "tiny"))
    }

    @Test
    fun `a warm replay re-records the ledger so the chain does not hot-loop`() {
        val ledger = PromptCacheTtlLedger()
        ledger.recordRequest("inst-1", "gpt-y", 600_000L, t0)
        val firstDue = ledger.nextWarmDueAtMs("inst-1", "gpt-y")!!
        // The successful warm resets the clock at its own finish time.
        val warmFinishedAt = firstDue + 2_000L
        ledger.recordRequest("inst-1", "gpt-y", 600_000L, warmFinishedAt)
        assertEquals(warmFinishedAt + 540_000L, ledger.nextWarmDueAtMs("inst-1", "gpt-y"))
        // Right after the warm the entry is fresh: not due any more.
        val fresh = ledger.ttlRemainingPct("inst-1", "gpt-y", warmFinishedAt + 1_000L)!!
        assertTrue(fresh > PromptCacheWarmer.TTL_REMAINING_REFRESH_FRACTION)
    }

    // ── models.dev rates side-car ───────────────────────────────────────────

    private val registry = """
        {
          "anthropic": {"models": {"claude-test": {"cost": {"input": 3, "output": 15, "cache_read": 0.3, "cache_write": 3.75}}}},
          "openai": {"models": {"gpt-test": {"cost": {"input": 2.5, "output": 10, "cache_read": 1.25}}}}
        }
    """.trimIndent()

    @Test
    fun `rates parse from the registry cost object per token`() {
        val r = PromptCacheWarmRates.parseRates(registry, "claude-test")!!
        assertEquals(3.0 / 1_000_000, r.inputPerTokenUsd, 1e-15)
        assertEquals(15.0 / 1_000_000, r.outputPerTokenUsd, 1e-15)
        assertEquals(0.3 / 1_000_000, r.cacheReadPerTokenUsd, 1e-15)
        assertEquals(3.75 / 1_000_000, r.cacheWritePerTokenUsd, 1e-15)
    }

    @Test
    fun `missing cache_write degrades to zero not failure`() {
        val r = PromptCacheWarmRates.parseRates(registry, "gpt-test")!!
        assertEquals(1.25 / 1_000_000, r.cacheReadPerTokenUsd, 1e-15)
        assertEquals(0.0, r.cacheWritePerTokenUsd, 0.0)
    }

    @Test
    fun `prefixed relay ids match by their final path segment`() {
        val r = PromptCacheWarmRates.parseRates(registry, "anthropic/claude-test")
        assertEquals(PromptCacheWarmRates.parseRates(registry, "claude-test"), r)
    }

    @Test
    fun `unknown model ids and corrupt documents yield null`() {
        assertNull(PromptCacheWarmRates.parseRates(registry, "private-relay-model"))
        assertNull(PromptCacheWarmRates.parseRates("{not-json", "claude-test"))
        assertNull(PromptCacheWarmRates.parseCostObject(null))
        assertNull(PromptCacheWarmRates.parseCostObject(JSONObject("""{"input": "NaN-ish"}""")))
    }
}
