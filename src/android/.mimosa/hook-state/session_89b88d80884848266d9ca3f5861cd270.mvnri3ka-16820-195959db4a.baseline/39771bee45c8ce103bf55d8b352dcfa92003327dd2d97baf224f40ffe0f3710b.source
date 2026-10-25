package com.openminis.app.provider.thinking

import com.openminis.app.data.model.ThinkingLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-thinking-ladder-shared] Contract tests for the ONE place a thinking level
 * becomes a numeric token budget.
 *
 * This suite deliberately tests the CONTRACT, not one model. Four providers
 * drive thinking with a number (Gemini `thinkingBudget`, DashScope
 * `thinking_budget`, Anthropic legacy `budget_tokens`, Zen free
 * `max_completion_tokens`), and each collapsed the same way before the ladder
 * was shared: the top rungs were pinned to the model's own ceiling, so on any
 * model whose capacity sits near that ceiling XHIGH/MAX/ULTRA went out as
 * byte-identical numbers and the slider did nothing.
 *
 * So the properties asserted here are the ones every call site relies on:
 *
 *  A. NO COLLISION AT THE CEILING, EVER. For every capacity and multiplier,
 *     the ladder is non-decreasing and strictly below capacity. This is the
 *     invariant the old absolute tables violated, and it holds even for
 *     capacities too small to express seven distinct rungs.
 *  B. STRICTLY INCREASING wherever the ladder can honestly be expressed —
 *     i.e. exactly when [ThinkingLadder.supportsDistinctLadder] is true.
 *  C. NEVER ZERO. Every rung clears `min(FLOOR, capacity - 1)`; several
 *     upstreams (Gemini Pro, DashScope) reject a budget of 0 outright.
 *  D. OFF < LOW whenever the ladder is distinct — the specific collapse this
 *     whole change exists to remove.
 *
 * A per-model expected-value table belongs in the per-provider suites; a
 * hardcoded list here would restate the implementation and go stale
 * silently. Asserting the INVARIANTS protects every call site for free,
 * including capacities nobody thought to write down.
 */
class ThinkingLadderTest {

    /**
     * [T-thinking-ladder-shared] The sweep straddles every threshold in the
     * primitive: below 2×FLOOR (1024 — fully degenerate), below the distinct
     * point (4096, 8192 — floor-collapsed), at it (16384 — the smallest
     * honest capacity), the REAL Zen free capacities that motivated the
     * change (32000, 32768), and headroom above (131072, 524288).
     */
    private val capacitySweep =
        listOf(1024, 1500, 4096, 8192, 16384, 32000, 32768, 65536, 131072, 524288)

    private val multipliers = listOf(1, 2)

    private fun ladderTable(capacity: Int, multiplier: Int) =
        ThinkingLadder.fullLadder(capacity, multiplier)
            .zip(ThinkingLevel.entries)
            .joinToString(" / ") { (v, l) -> "$l=$v" }

    private fun floorFor(capacity: Int) = minOf(ThinkingLadder.FLOOR, capacity - 1).coerceAtLeast(1)

    // -- A. the invariant every call site depends on

    /**
     * Non-decreasing for EVERY capacity, and strictly below capacity for
     * every capacity. The old absolute table failed the second half: on a
     * 32000-capacity Zen model the top three rungs all clipped to the same
     * number, which is only visible here as "the top rung equals capacity".
     */
    @Test
    fun `ladder never decreases and never reaches capacity`() {
        for (capacity in capacitySweep) {
            for (multiplier in multipliers) {
                val table = ladderTable(capacity, multiplier)
                val budgets = ThinkingLadder.fullLadder(capacity, multiplier)
                assertEquals(
                    "[cap=$capacity mult=$multiplier] ladder must cover every level: $table",
                    ThinkingLevel.entries.size,
                    budgets.size,
                )
                budgets.zipWithNext().forEach { (lower, higher) ->
                    assertTrue(
                        "[cap=$capacity mult=$multiplier] ladder must never go BACKWARDS: $table",
                        higher >= lower,
                    )
                }
                budgets.forEach { budget ->
                    assertTrue(
                        "[cap=$capacity mult=$multiplier] $budget must be < capacity: $table",
                        budget < capacity,
                    )
                }
            }
        }
    }

    // -- B. strict monotonicity wherever the ladder can be honest

    /**
     * [T-thinking-ladder-shared] Below the distinct point the ladder
     * deliberately degrades to repeated FLOOR values instead of faking a
     * gradient (4096 and 8192 both collapse OFF onto the floor), so strict
     * increase is asserted exactly where [ThinkingLadder.supportsDistinctLadder]
     * promises it — coupling the two so they cannot drift apart silently.
     */
    @Test
    fun `budgets strictly increase wherever the ladder is distinct`() {
        for (capacity in capacitySweep) {
            for (multiplier in multipliers) {
                if (!ThinkingLadder.supportsDistinctLadder(capacity, multiplier)) continue
                val budgets = ThinkingLadder.fullLadder(capacity, multiplier)
                val table = ladderTable(capacity, multiplier)
                budgets.zipWithNext().forEach { (lower, higher) ->
                    assertTrue(
                        "[cap=$capacity mult=$multiplier] ladder must strictly increase: $table",
                        higher > lower,
                    )
                }
            }
        }
    }

    // -- C. the floor, relative to capacity

    @Test
    fun `every budget clears the floor`() {
        for (capacity in capacitySweep) {
            for (multiplier in multipliers) {
                val floor = floorFor(capacity)
                ThinkingLadder.fullLadder(capacity, multiplier).forEach { budget ->
                    assertTrue(
                        "[cap=$capacity mult=$multiplier] $budget must be >= floor $floor " +
                            "(${ladderTable(capacity, multiplier)})",
                        budget >= floor,
                    )
                }
            }
        }
    }

    // -- D. OFF must be below LOW wherever the ladder can say so

    @Test
    fun `off is strictly below low on every capacity that can express it`() {
        // The original defect in its smallest possible form: OFF and LOW were
        // the same number on the wire, so turning thinking OFF changed nothing.
        for (capacity in capacitySweep) {
            for (multiplier in multipliers) {
                val ladder = ThinkingLadder.fullLadder(capacity, multiplier)
                if (!ThinkingLadder.supportsDistinctLadder(capacity, multiplier)) continue
                assertTrue(
                    "[cap=$capacity mult=$multiplier] OFF (${ladder.first()}) must be < " +
                        "LOW (${ladder[1]}); ${ladderTable(capacity, multiplier)}",
                    ladder.first() < ladder[1],
                )
            }
        }
    }

    // -- supportsDistinctLadder must agree with what fullLadder actually does

    /**
     * [T-thinking-ladder-shared] The predicate exists so the picker can
     * truncate a model that cannot honestly offer seven rungs. If it drifted
     * from `fullLadder`, the UI would offer rungs the wire cannot tell apart —
     * so the test asks the LADDER what it can express and compares, rather
     * than restating the implementation's own threshold constant.
     */
    @Test
    fun `supportsDistinctLadder agrees with the ladder it actually produces`() {
        for (capacity in capacitySweep) {
            for (multiplier in multipliers) {
                val ladder = ThinkingLadder.fullLadder(capacity, multiplier)
                val actuallyDistinct = ladder.toSet().size == ThinkingLevel.entries.size
                assertEquals(
                    "[cap=$capacity mult=$multiplier] predicate disagrees with the ladder " +
                        "it gates: ${ladderTable(capacity, multiplier)}",
                    actuallyDistinct,
                    ThinkingLadder.supportsDistinctLadder(capacity, multiplier),
                )
            }
        }
    }

    /** The documented threshold: 16384 is the smallest capacity that fits all
     *  seven rungs; below it the ladder cannot be honest and must collapse
     *  loudly rather than pretend. */
    @Test
    fun `distinct ladder threshold sits between 8192 and 16384`() {
        assertTrue(
            "16384 must offer every rung distinctly (${ladderTable(16384, 1)})",
            ThinkingLadder.supportsDistinctLadder(16384),
        )
        assertFalse(
            "8192 cannot: ${ladderTable(8192, 1)}",
            ThinkingLadder.supportsDistinctLadder(8192),
        )
        assertFalse(
            "4096 cannot: ${ladderTable(4096, 1)}",
            ThinkingLadder.supportsDistinctLadder(4096),
        )
    }

    // -- the multiplier rescales the FRACTIONS, it does not scale the result

    /**
     * [T-thinking-ladder-shared] The must-think transform (MiMo/Agnes) must
     * NOT be `budget * 2`: that pushes the top rungs over the ceiling, where
     * they clamp back together and the original collapse returns — on exactly
     * the models the transform exists for. Dividing the fractions instead
     * shifts the whole ladder down, so ULTRA stays below the ceiling on every
     * capacity, doubled or not.
     */
    @Test
    fun `doubling the multiplier shifts the ladder down, never past the ceiling`() {
        for (capacity in listOf(32000, 32768, 131072)) {
            val single = ThinkingLadder.fullLadder(capacity, 1)
            val doubled = ThinkingLadder.fullLadder(capacity, 2)
            assertTrue(
                "[cap=$capacity] multiplier=2 must sit at or BELOW multiplier=1",
                doubled.zip(single).all { (d, s) -> d <= s },
            )
            assertTrue(
                "[cap=$capacity] multiplier=2 must actually change the ladder: " +
                    "1=${single.joinToString("/")} 2=${doubled.joinToString("/")}",
                doubled != single,
            )
            assertEquals(
                "[cap=$capacity] doubling must not collapse the ladder",
                ThinkingLevel.entries.size,
                doubled.toSet().size,
            )
            assertTrue(
                "[cap=$capacity] the doubled top rung must stay under capacity",
                doubled.last() < capacity,
            )
        }
    }

    // -- rank lookup must agree with enum order, not with declaration accident

    @Test
    fun `ladder is ordered by rank, not by insertion`() {
        val byRank = ThinkingLevel.entries.sortedBy { it.rank }.map {
            ThinkingLadder.budgetFor(it, 131072)
        }
        assertEquals(ThinkingLadder.fullLadder(131072), byRank)
    }

    // -- real Zen free catalogue capacities: the models that motivated the fix

    /**
     * [T-zen-free-thinking-budget-ladder] big-pickle / mimo-v2.5 / mimo-v2.6
     * (32000) and ling-3.1 (32768) are the four models whose top rungs used
     * to clip to one number under the old absolute table. Pinned here as the
     * regression that justified making the ladder proportional.
     */
    @Test
    fun `zen free capacities keep all seven rungs distinct`() {
        for (capacity in listOf(32000, 32768)) {
            assertTrue(
                "[cap=$capacity] Zen free model lost a rung: ${ladderTable(capacity, 1)}",
                ThinkingLadder.supportsDistinctLadder(capacity, 1),
            )
        }
    }
}
