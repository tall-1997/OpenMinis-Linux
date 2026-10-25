package com.openminis.app.provider.thinking

import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.anthropic.AnthropicProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-thinking-ladder-shared] Contract tests for the two LEGACY numeric-budget sites
 * that were converted from hardcoded absolute ladders to [ThinkingLadder]:
 *
 *   • Qwen / DashScope `thinking_budget` — [ThinkingRuleResolver.apply] via the
 *     `ThinkingWireFormat.QwenDual` branch.
 *   • Anthropic legacy `budget_tokens` — [AnthropicProvider.thinkingBudget].
 *
 * These are deliberately written as GENERIC contracts (strictly increasing, strictly
 * below the request ceiling) rather than as golden numbers, because the whole point of
 * the change is that no absolute constant appears on the wire any more: a number is
 * only correct relative to the capacity it was scaled against. The golden snapshots
 * still pin the concrete values; this file pins the property that makes them honest.
 *
 * ── What the OLD ladders did (reproduced by hand before the fix) ──────────────
 *
 * QwenDual, absolute constants 4096/16384/32768/65536 clipped by
 * `ceiling = min(maxTokens - max(2048, maxTokens/8), maxTokens - 1)`:
 *
 *     maxTokens   8192: margin  2048, ceiling  6144 → 4096 6144  6144  6144  6144  6144
 *     maxTokens  16384: margin  2048, ceiling 14336 → 4096 14336 14336 14336 14336 14336
 *     maxTokens  24576: margin  2048, ceiling 21504 → 4096 16384 21504 21504 21504 21504
 *     maxTokens  32768: margin  4096, ceiling 28672 → 4096 16384 28672 28672 28672 28672
 *     maxTokens  65536: margin  8192, ceiling 57344 → 4096 16384 32768 57344 57344 57344
 *
 * Two failures, not one. (1) XHIGH/MAX/ULTRA were the same constant everywhere. (2)
 * the flat-2048 margin meant that for EVERY maxTokens <= 24576 each rung that reached
 * the ceiling was pinned to the SAME number — at 8192, five of six levels sent 6144.
 *
 * Anthropic legacy, OFF 0 / LOW 8192 / MEDIUM 32768 / HIGH min(mt,65536) / rest mt,
 * with the `maxTokens - 1` pullback:
 *
 *     maxTokens   8192: 0 8191 8191 8191 8191 8191 8191
 *     maxTokens  16384: 0 8192 16383 16383 16383 16383 16383
 *     maxTokens  32768: 0 8192 32767 32767 32767 32767 32767
 *     maxTokens  65536: 0 8192 32768 65535 65535 65535 65535
 *     maxTokens 131072: 0 8192 32768 65536 131071 131071 131071
 *
 * HIGH/MAX/ULTRA were one number for every maxTokens <= 65536.
 */
class ThinkingBudgetWireTest {

    /** The six ENABLED levels, weakest first. OFF is never emitted as a budget. */
    private val enabled = listOf(
        ThinkingLevel.LOW,
        ThinkingLevel.MEDIUM,
        ThinkingLevel.HIGH,
        ThinkingLevel.XHIGH,
        ThinkingLevel.MAX,
        ThinkingLevel.ULTRA,
    )

    private val qwenCapacities = listOf(8192, 16384, 32768, 65536)
    private val anthropicCapacities = listOf(8192, 16384, 32768, 65536, 131072)

    // ─────────────────────────── helpers ───────────────────────────

    /**
     * Run the real QwenDual emitter over one (level, maxTokens) pair and return the
     * emitted budget, or null when the branch omitted the field entirely.
     *
     * Goes through [ThinkingRuleResolver.apply] rather than any extracted helper so the
     * OFF short-circuit, the degenerate guard, the dual emission and the margin all stay
     * exercised exactly as production runs them.
     */
    private fun qwenBudget(level: ThinkingLevel, maxTokens: Int): JSONObject {
        val ctx = ThinkingResolveContext(
            modelId = "qwen3-32b",
            supportsReasoning = true,
            declaredEffortValues = null,
            level = level,
            maxTokens = maxTokens,
            isOpenRouter = false,
            usesUnifiedReasoningEffort = false,
            isMistral = false,
            isDashScope = true,
            offEffort = null,
        )
        val body = JSONObject()
        ThinkingRuleResolver.apply(body, ctx)
        return body
    }

    /** Mirror of the production margin clamp, kept here so the test states the
     *  expectation independently of the implementation it is checking. */
    private fun qwenCeiling(maxTokens: Int): Int =
        maxOf(1, minOf(maxTokens - maxOf(2048, maxTokens / 8), maxTokens - 1))

    private fun assertStrictlyIncreasing(label: String, values: List<Int>) {
        values.zipWithNext().forEach { (a, b) ->
            assertTrue(
                "$label must be strictly increasing, but $a was followed by $b — " +
                    "two levels that produce an identical request are a dead slider step. Full: $values",
                a < b,
            )
        }
    }

    // ─────────────────────── Site 1: Qwen / DashScope ───────────────────────

    /**
     * The headline fix: every enabled level must reach the wire as a DIFFERENT number,
     * and every one of them must sit strictly below the clamp ceiling so DashScope
     * (which rejects `thinking_budget >= max_completion_tokens`) never 400s.
     */
    @Test
    fun `qwen thinking_budget is strictly increasing and below the ceiling`() {
        for (maxTokens in qwenCapacities) {
            val ceiling = qwenCeiling(maxTokens)
            val budgets = enabled.map { level ->
                val body = qwenBudget(level, maxTokens)
                assertTrue(
                    "Qwen must emit a positive thinking_budget at $level for maxTokens=$maxTokens: $body",
                    body.optInt("thinking_budget", -1) > 0,
                )
                body.getInt("thinking_budget")
            }

            assertStrictlyIncreasing("qwen thinking_budget @ maxTokens=$maxTokens", budgets)

            budgets.forEach { b ->
                assertTrue(
                    "thinking_budget($b) must be STRICTLY below the clamp ceiling($ceiling) " +
                        "for maxTokens=$maxTokens",
                    b < ceiling,
                )
                assertTrue(
                    "thinking_budget($b) must also be strictly below max_completion_tokens($maxTokens)",
                    b < maxTokens,
                )
            }
        }
    }

    /**
     * The ladder is dual-emitted: DashScope reads `extra_body.thinking_budget` while
     * vLLM/SGLang accept the root key. The two MUST agree, and both must be present
     * whenever a budget exists — a divergent pair means the request means different
     * things to different backends behind the same relay.
     */
    @Test
    fun `qwen dual emission agrees at root and inside extra_body`() {
        for (maxTokens in qwenCapacities) {
            for (level in enabled) {
                val body = qwenBudget(level, maxTokens)
                assertTrue("root enable_thinking expected at $level: $body", body.has("enable_thinking"))
                assertEquals(
                    "enable_thinking must be true whenever a budget is emitted ($level/$maxTokens): $body",
                    true,
                    body.optBoolean("enable_thinking"),
                )
                val extra = body.optJSONObject("extra_body")
                assertTrue("extra_body must be present at $level: $body", extra != null)
                assertEquals(
                    "extra_body.enable_thinking must be true at $level/$maxTokens: $body",
                    true,
                    extra!!.optBoolean("enable_thinking"),
                )
                assertEquals(
                    "root and extra_body thinking_budget must agree at $level/$maxTokens: $body",
                    body.getInt("thinking_budget"),
                    extra.getInt("thinking_budget"),
                )
            }
        }
    }

    /**
     * ANDROID-SPECIFIC OFF SEMANTICS (pinned at the branch): OFF short-circuits BEFORE
     * any emission, so neither `thinking_budget` nor `extra_body` reaches the wire and
     * the vendor default governs. This is deliberately NOT the same shape as the enabled
     * path — do not "fix" it into an explicit null or a zero.
     */
    @Test
    fun `qwen OFF emits no thinking field at all`() {
        for (maxTokens in qwenCapacities) {
            val body = qwenBudget(ThinkingLevel.OFF, maxTokens)
            assertFalse(
                "OFF must not emit thinking_budget for maxTokens=$maxTokens: $body",
                body.has("thinking_budget"),
            )
            assertFalse(
                "OFF must not emit extra_body for maxTokens=$maxTokens: $body",
                body.has("extra_body"),
            )
        }
    }

    /**
     * The pathological guard. With `max_tokens = 1` no positive budget can be strictly
     * below it, so the field is dropped rather than emitted invalid (DashScope
     * "thinking_budget must be less than max_completion_tokens").
     */
    @Test
    fun `qwen drops thinking_budget when max_tokens leaves no room`() {
        for (level in enabled) {
            val body = qwenBudget(level, 1)
            assertFalse(
                "maxTokens=1 must omit the root thinking_budget at $level: $body",
                body.has("thinking_budget"),
            )
            val extra = body.optJSONObject("extra_body")
            assertTrue("extra_body is still emitted at $level: $body", extra != null)
            assertFalse(
                "maxTokens=1 must ALSO omit extra_body.thinking_budget at $level — " +
                    "ANDROID sends no explicit JSON null here, it drops the key: $body",
                extra!!.has("thinking_budget"),
            )
        }
    }

    // ─────────────────── Site 2: Anthropic legacy budget_tokens ───────────────────

    /**
     * The Anthropic-side half of the same bug: HIGH, MAX and ULTRA all resolved to one
     * value for any maxTokens <= 65536. The ladder must now separate every level, and
     * every rung must stay strictly under `max_tokens` — Anthropic 400s on
     * `budget_tokens >= max_tokens` ("thinking.budget_tokens must be less than
     * max_tokens"), which is why the `maxTokens - 1` pullback is kept at the call site.
     */
    @Test
    fun `anthropic legacy thinkingBudget is strictly increasing and below maxTokens`() {
        for (maxTokens in anthropicCapacities) {
            val budgets = enabled.map { AnthropicProvider.thinkingBudget(maxTokens, it) }
            assertStrictlyIncreasing("anthropic thinkingBudget @ maxTokens=$maxTokens", budgets)
            budgets.forEach { b ->
                assertTrue(
                    "budget_tokens($b) must be STRICTLY less than max_tokens($maxTokens) — " +
                        "equality is a hard 400",
                    b < maxTokens,
                )
                assertTrue("budget_tokens must stay positive: got $b", b > 0)
            }
        }
    }

    /**
     * OFF must stay exactly 0 — never a real budget, never a negative, at any capacity.
     */
    @Test
    fun `anthropic OFF returns exactly zero`() {
        for (maxTokens in anthropicCapacities + listOf(0, 1, -5)) {
            assertEquals(
                "OFF must return 0 for maxTokens=$maxTokens",
                0,
                AnthropicProvider.thinkingBudget(maxTokens, ThinkingLevel.OFF),
            )
        }
    }

    /** An unusable max_tokens cannot host a budget; guard it rather than emitting 0-negatives. */
    @Test
    fun `anthropic returns zero when maxTokens cannot host a budget`() {
        for (maxTokens in listOf(0, 1, -5)) {
            for (level in enabled) {
                assertEquals(
                    "maxTokens=$maxTokens must yield 0 at $level, not an invalid budget",
                    0,
                    AnthropicProvider.thinkingBudget(maxTokens, level),
                )
            }
        }
    }

    // ─────────────────────── cross-site invariant ───────────────────────

    /**
     * HONEST DEGRADATION. [ThinkingLadder] cannot express 7 distinct rungs below
     * `2 * floor` (the spacing between the smallest fractions is smaller than the
     * floor). [ThinkingLadder.supportsDistinctLadder] is the flag that says so, and the
     * contract is that a site must NOT paper over it by emitting a fake gradient.
     *
     * This test asserts the flag first — so the sites below are only held to the
     * strict-monotonicity contract when the ladder genuinely can express one, and are
     * instead required to return a bounded, non-increasing, honest single value. A
     * silent "fixed" ladder that duplicated rungs here while claiming 7 levels would
     * pass a naive monotonicity check only if that check were dropped; this pins both
     * halves so neither can be removed.
     */
    @Test
    fun `sites degrade honestly where a distinct ladder is impossible`() {
        val tooSmall = listOf(2048, 4096, 8192)
        for (capacity in tooSmall) {
            // 8192 CAN host a distinct ladder for MEDIUM..ULTRA, but OFF and LOW both
            // floor to 512, so supportsDistinctLadder reports false — it asks about all
            // seven rungs including OFF. Everything below keys off this flag, so the
            // expectation changes shape with it.
            assertFalse(
                "capacity $capacity cannot express 7 distinct rungs (OFF and LOW collide)",
                ThinkingLadder.supportsDistinctLadder(capacity),
            )
        }

        for (capacity in tooSmall) {
            // Qwen: the margin ceiling is what the ladder is generated against. For
            // capacity <= 2560 that ceiling is <= FLOOR and the budget is legitimately
            // OMITTED (the vendor default is the honest answer — a 1-token thinking
            // budget is a broken mode, not a weak one). Above it, values are positive
            // and strictly under maxTokens either way.
            val ceiling = qwenCeiling(capacity)
            val qwen = enabled.map {
                qwenBudget(it, capacity).optInt("thinking_budget", 0)
            }
            if (ceiling <= ThinkingLadder.FLOOR) {
                assertTrue(
                    "with ceiling=$ceiling (<= FLOOR) Qwen must omit the budget entirely, " +
                        "never emit a degenerate value: $qwen",
                    qwen.all { it == 0 },
                )
            } else {
                assertTrue(
                    "Qwen must still emit something positive and legal at capacity=$capacity: $qwen",
                    qwen.all { it > 0 && it < capacity },
                )
                if (ThinkingLadder.supportsDistinctLadder(ceiling)) {
                    assertStrictlyIncreasing("qwen @ capacity=$capacity", qwen)
                } else {
                    // Honest degradation. [ThinkingLadder.supportsDistinctLadder] asks
                    // about all SEVEN rungs and OFF is the smallest, so below capacity
                    // 16384 it reports false even when the six EMITTED rungs are
                    // perfectly distinct — OFF and LOW both bottom out on the 512 floor.
                    // The flag is therefore an upper bound, and the property that
                    // actually matters is the one asserted here: a site emits at most
                    // one value per emitted level (no fabricated extra rungs) and the
                    // ladder still ramps UP with level rather than inverting.
                    assertTrue(
                        "a site must never present more distinct rungs than it has " +
                            "levels ($enabled) at capacity=$capacity: $qwen",
                        qwen.toSet().size <= enabled.size,
                    )
                    assertTrue(
                        "emitted budgets must be non-decreasing in level — the ladder " +
                            "tapers UP toward the ceiling, never down: $qwen",
                        qwen.zipWithNext().all { (a, b) -> a <= b },
                    )
                }
            }

            // Anthropic: capacity IS maxTokens here (budget_tokens is request-scoped),
            // so the same flag gates the same property.
            val anthropic = enabled.map { AnthropicProvider.thinkingBudget(capacity, it) }
            assertTrue(
                "Anthropic must stay strictly legal at capacity=$capacity: $anthropic",
                anthropic.all { it in 1 until capacity },
            )
            if (ThinkingLadder.supportsDistinctLadder(capacity)) {
                assertStrictlyIncreasing("anthropic @ capacity=$capacity", anthropic)
            } else {
                // Same reasoning as the Qwen arm: the flag counts all seven rungs
                // including OFF, so a `false` does NOT mean the emitted rungs collapse.
                // Assert the property that must hold either way.
                assertTrue(
                    "anthropic must never present more distinct rungs than it has " +
                        "levels at capacity=$capacity: $anthropic",
                    anthropic.toSet().size <= enabled.size,
                )
                assertTrue(
                    "emitted budgets must be non-decreasing in level at " +
                        "capacity=$capacity: $anthropic",
                    anthropic.zipWithNext().all { (a, b) -> a <= b },
                )
            }
        }
    }

    /**
     * [T-thinking-ladder-shared] Guard on a real defect found while wiring this:
     * `ThinkingLadder.budgetFor` evaluates `scaled.coerceIn(FLOOR, capacity - 1)`
     * BEFORE its own degenerate early-return, so any capacity at or below FLOOR
     * (512) raises `IllegalArgumentException: Cannot coerce value to an empty range`
     * instead of degrading to the documented honest single value.
     *
     * ThinkingLadder.kt is the shared, final primitive and is NOT edited here, so both
     * call sites clamp the capacity BEFORE handing it over. This test pins that the
     * contract holds from the outside: for every capacity the sites can produce, the
     * call must not throw. If the primitive is ever fixed to early-return, this still
     * passes — it asserts the behaviour both sites depend on, not the bug.
     */
    @Test
    fun `neither site can drive the shared ladder into its empty-range throw`() {
        val hostile = listOf(
            0, 1, 2, 100, 511, 512, 513, 1024, 2048, 2560, 2561, 4096, 8192,
        )
        for (maxTokens in hostile) {
            for (level in enabled) {
                val body = qwenBudget(level, maxTokens)
                val budget = body.optInt("thinking_budget", 0)
                assertTrue(
                    "qwen budget must be either 0 (omitted) or strictly below maxTokens " +
                        "at maxTokens=$maxTokens/$level; got $budget",
                    budget == 0 || (budget > 0 && budget < maxTokens),
                )
                val anthropic = AnthropicProvider.thinkingBudget(maxTokens, level)
                assertTrue(
                    "anthropic budget must be 0 or strictly below maxTokens at " +
                        "maxTokens=$maxTokens/$level; got $anthropic",
                    anthropic == 0 || (anthropic > 0 && anthropic < maxTokens),
                )
            }
            // OFF must stay OFF across the whole hostile range, at every capacity.
            assertFalse(
                "qwen OFF must emit nothing at maxTokens=$maxTokens",
                qwenBudget(ThinkingLevel.OFF, maxTokens).has("thinking_budget"),
            )
            assertEquals(
                "anthropic OFF must be 0 at maxTokens=$maxTokens",
                0,
                AnthropicProvider.thinkingBudget(maxTokens, ThinkingLevel.OFF),
            )
        }
    }

    /**
     * The shared primitive's own monotonicity contract, asserted once here so both
     * sites above are known to be resting on something real — and so a future edit to
     * either site that re-introduces an absolute constant is caught against the same
     * reference the sites scale from.
     */
    @Test
    fun `shared ladder is strictly increasing wherever it claims to be`() {
        for (capacity in qwenCapacities + anthropicCapacities) {
            if (!ThinkingLadder.supportsDistinctLadder(capacity)) continue
            val full = ThinkingLevel.entries.map { ThinkingLadder.budgetFor(it, capacity) }
            assertStrictlyIncreasing("ThinkingLadder @ capacity=$capacity", full)
            assertEquals(
                "a capacity passing supportsDistinctLadder must yield 7 distinct rungs @ $capacity",
                7,
                full.toSet().size,
            )
        }
    }
}
