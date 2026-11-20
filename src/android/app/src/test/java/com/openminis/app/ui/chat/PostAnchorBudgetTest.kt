package com.openminis.app.ui.chat

import com.openminis.app.agent.HarnessConfig
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-compact-gate-unblinded] The production tail budget, single-sourced so the
 * gate and the assembly can never disagree about what "un-budgeted" means.
 */
class PostAnchorBudgetTest {

    @Test
    fun `budget scales with the window up to the ceiling`() {
        assertEquals(PostAnchorTail.BUDGET_TOKENS, PostAnchorTail.budgetTokensForWindow(null))
        assertEquals(60_000, PostAnchorTail.budgetTokensForWindow(1_000_000))
        assertEquals(44_800, PostAnchorTail.budgetTokensForWindow(128_000))
        // Small windows take the 35% share, never the 60k ceiling.
        assertEquals(11_200, PostAnchorTail.budgetTokensForWindow(32_000))
    }

    @Test
    fun `un-budgeted mode is unbounded`() {
        assertEquals(Int.MAX_VALUE, PostAnchorTail.budgetTokensForWindow(128_000, applyTailBudget = false))
        assertEquals(Int.MAX_VALUE, PostAnchorTail.budgetTokensForWindow(null, applyTailBudget = false))
    }

    @Test
    fun `an unbounded budget drops nothing`() {
        val history = (0 until 30).map { i ->
            val id = "c$i"
            LLMMessage(
                role = LLMMessage.Role.ASSISTANT,
                content = "",
                contentParts = listOf(
                    AgentContentPart.ToolUse(id, "shell_execute", org.json.JSONObject("""{"command":"ls"}""")),
                    AgentContentPart.ToolResult(id = id, name = "shell_execute", content = "x".repeat(4_000)),
                ),
            )
        }
        val result = PostAnchorTail.apply(
            history,
            PostAnchorTail.Budget(
                budgetTokens = PostAnchorTail.budgetTokensForWindow(128_000, applyTailBudget = false),
                estimate = WalkBack::estimateMessageTokens,
            ),
        )
        assertEquals(0, result.droppedRounds)
        assertEquals(history, result.kept)
    }

    @Test
    fun `an unbounded budget does not scan any round`() {
        val history = listOf(user("old"), user("new"))
        val result = PostAnchorTail.apply(
            history,
            PostAnchorTail.Budget(Int.MAX_VALUE, estimate = { error("must not estimate an uncapped tail") }),
        )
        assertTrue(result.kept === history)
    }

    @Test
    fun `large round totals cannot wrap around the budget`() {
        val history = listOf(user("oldest"), user("old"), user("new"))
        val result = PostAnchorTail.apply(
            history,
            PostAnchorTail.Budget(1_000, estimate = { Int.MAX_VALUE / 2 + 10 }),
        )
        assertEquals(2, result.droppedRounds)
        assertEquals(Int.MAX_VALUE, result.droppedTokens)
        assertEquals(history.last(), result.kept.last())
    }

    // ── estimator completeness ────────────────────────────────────────────

    @Test
    fun `notice and digest fit inside the declared budget`() {
        // [T-postanchor-notice-reserve] A 3k-char digest is ~750 tokens and
        // must not be added on top of the tail budget.
        val rounds = (0 until 6).map { i ->
            listOf(
                user("round $i"),
                LLMMessage(
                    role = LLMMessage.Role.ASSISTANT,
                    content = "",
                    contentParts = listOf(
                        AgentContentPart.ToolUse(
                            "c$i",
                            "shell_execute",
                            org.json.JSONObject("""{"command":"ls $i"}"""),
                        ),
                    ),
                ),
                LLMMessage(
                    role = LLMMessage.Role.USER,
                    content = "",
                    contentParts = listOf(
                        AgentContentPart.ToolResult("c$i", "shell_execute", "x".repeat(6_000)),
                    ),
                ),
            )
        }.flatten()
        val result = PostAnchorTail.apply(
            rounds,
            PostAnchorTail.Budget(
                budgetTokens = 8_000,
                estimate = WalkBack::estimateMessageTokens,
                digest = { "d".repeat(3_000) },
            ),
        )
        val total = result.kept.sumOf { WalkBack.estimateMessageTokens(it) }
        assertTrue("kept=$total must fit 8000", total <= 8_000)
    }

    @Test
    fun `tool call arguments count toward the budget`() {
        val heavy = LLMMessage(
            role = LLMMessage.Role.ASSISTANT,
            content = "",
            contentParts = listOf(
                AgentContentPart.ToolUse(
                    "c1",
                    "file_write",
                    org.json.JSONObject("""{"path":"/a.kt","content":"${"x".repeat(4_000)}"}"""),
                ),
            ),
        )
        assertTrue(
            "4k of tool arguments must weigh ~1000 tokens, not ~1",
            WalkBack.estimateMessageTokens(heavy) >= 1_000,
        )
    }

    @Test
    fun `heavy tool rounds are dropped by the tail budget`() {
        val rounds = (0 until 6).map { i ->
            val id = "c$i"
            listOf(
                LLMMessage(
                    role = LLMMessage.Role.USER,
                    content = "round $i",
                ),
                LLMMessage(
                    role = LLMMessage.Role.ASSISTANT,
                    content = "",
                    contentParts = listOf(
                        AgentContentPart.ToolUse(
                            id,
                            "file_write",
                            org.json.JSONObject("""{"content":"${"x".repeat(4_000)}"}"""),
                        ),
                    ),
                ),
                LLMMessage(
                    role = LLMMessage.Role.USER,
                    content = "",
                    contentParts = listOf(AgentContentPart.ToolResult(id, "file_write", "ok")),
                ),
            )
        }.flatten()
        val result = PostAnchorTail.apply(
            rounds,
            PostAnchorTail.Budget(
                budgetTokens = 3_000,
                estimate = WalkBack::estimateMessageTokens,
            ),
        )
        assertTrue("the budget must see the argument weight", result.droppedRounds > 0)
    }

    // ── summary injection target ──────────────────────────────────────────

    private fun user(text: String) = LLMMessage(role = LLMMessage.Role.USER, content = text)

    @Test
    fun `summary skips the dropped-rounds notice and lands on the real user turn`() {
        val notice = user(
            "[context window: 9 older rounds dropped (≈1000 tokens) — " +
                "the earlier summary does NOT cover them; see the digest]",
        )
        val real = user("implement the retry")
        val messages = listOf(notice, real)
        assertEquals(1, firstInjectableUserIndex(messages))
    }

    @Test
    fun `summary does not target a tool-result carrier`() {
        val notice = user("[context window: 1 older rounds dropped (≈1 tokens) — x]")
        val carrier = LLMMessage(
            role = LLMMessage.Role.USER,
            content = "",
            contentParts = listOf(AgentContentPart.ToolResult("c1", "shell_execute", "ok")),
        )
        assertEquals(-1, firstInjectableUserIndex(listOf(notice, carrier)))
    }

    @Test
    fun `summary falls back to no target when only notes exist`() {
        val note = user("<system-reminder>\n## Recalled relevant memories\nx\n</system-reminder>")
        assertEquals(-1, firstInjectableUserIndex(listOf(note)))
    }

    @Test
    fun `gate measurement follows the window tier`() {
        // [T-exhausted-tier-measurement] <32K cannot compact, so it must weigh
        // the real (trimmed/budgeted) request, not the un-budgeted worst case.
        assertEquals(PolicyMeasurement.REQUEST, policyMeasurementMode(30_000, 75))
        assertEquals(PolicyMeasurement.UNBUDGETED, policyMeasurementMode(32_000, 75))
        assertEquals(PolicyMeasurement.UNBUDGETED, policyMeasurementMode(128_000, 75))
        assertEquals(PolicyMeasurement.UNBUDGETED, policyMeasurementMode(null, 75))
    }

    // ── state note projection ─────────────────────────────────────────────

    private fun stateNote() = user(
        "<system-reminder>\n${SessionStateSnapshot.MARKER}\n\nPlan (1/2 done):\n1. [done] a\n</system-reminder>",
    )

    @Test
    fun `an evicted state note is reported for request-only re-injection`() {
        val before = user("task")
        val note = stateNote()
        val snapshot = listOf(before, note)
        assertEquals(note, evictedStateNote(snapshot, anchorIdx = 0, kept = listOf(before)))
    }

    @Test
    fun `a quoted state marker is not a state snapshot`() {
        val note = stateNote()
        val quote = user("what does ${SessionStateSnapshot.MARKER} mean?")
        assertEquals(note, evictedStateNote(listOf(note, quote), anchorIdx = -1, kept = listOf(quote)))
    }

    @Test
    fun `a visible or summarized state note is not re-injected`() {
        val before = user("task")
        val note = stateNote()
        // Visible in the kept projection.
        assertEquals(null, evictedStateNote(listOf(before, note), anchorIdx = 0, kept = listOf(before, note)))
        // Before the anchor: covered by the summary.
        assertEquals(null, evictedStateNote(listOf(note, before), anchorIdx = 1, kept = listOf(before)))
    }

    @Test
    fun `no user at all yields minus one`() {
        assertEquals(-1, firstInjectableUserIndex(emptyList()))
    }

    @Test
    fun `harness notes are not injection targets`() {
        val state = user("<system-reminder>\nSession state restored by the host\n\nplan\n</system-reminder>")
        val real = user("keep going")
        assertTrue(firstInjectableUserIndex(listOf(state, real)) == 1)
    }

    @Test
    fun aPerModelOverrideReplacesTheWindowScalingButStaysInsideTheCeiling() {
        // The model override changes the ceiling, without increasing the fraction of a small window.
        val custom = HarnessConfig.DEFAULT.copy(postAnchorBudgetTokens = 20_000)
        assertEquals(11_200, PostAnchorTail.budgetTokensForWindow(32_000, true, HarnessConfig.DEFAULT))
        assertEquals(11_200, PostAnchorTail.budgetTokensForWindow(32_000, true, custom))
        assertEquals(20_000, PostAnchorTail.budgetTokensForWindow(128_000, true, custom))
    }

    @Test
    fun theBudgetCarriesTheConfigItWasBuiltWith() {
        val custom = HarnessConfig.DEFAULT.copy(noticeReserveTokens = 900)
        val budget = PostAnchorTail.Budget(
            budgetTokens = 10_000,
            estimate = WalkBack::estimateMessageTokens,
            config = custom,
        )
        // effectiveTailBudget is what the kept slice actually gets: reserve-aware.
        assertEquals(10_000 - 900, budget.config.effectiveTailBudget(budget.budgetTokens))
    }
}
