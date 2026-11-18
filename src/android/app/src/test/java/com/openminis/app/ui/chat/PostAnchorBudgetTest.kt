package com.openminis.app.ui.chat

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

    // ── estimator completeness ────────────────────────────────────────────

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
    fun `summary falls back to the first user when only notes exist`() {
        val note = user("<system-reminder>\n## Recalled relevant memories\nx\n</system-reminder>")
        assertEquals(0, firstInjectableUserIndex(listOf(note)))
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
}
