package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-postanchor-tail-budget] Unit tests for the request-side hard
 * tail window over the v2 marker's postAnchor slice. All cases run the pure
 * [PostAnchorTail.apply] with [WalkBack.estimateMessageTokens] — the exact
 * estimator the production wiring passes.
 *
 * Round shape used throughout: [user, assistant(tool_use), user(tool_result)]
 * — the pairing invariant lives inside one round, so whole-round drops must
 * never split it.
 */
class PostAnchorTailTest {

    private fun userText(text: String) = LLMMessage(role = LLMMessage.Role.USER, content = text)

    private fun toolRound(
        index: Int,
        resultChars: Int = 400,
    ): List<LLMMessage> {
        val toolId = "call_$index"
        val assistant = LLMMessage(
            role = LLMMessage.Role.ASSISTANT,
            content = "",
            contentParts = listOf(
                AgentContentPart.ToolUse(toolId, "execute_shell", JSONObject("""{"cmd":"ls $index"}""")),
            ),
        )
        val result = LLMMessage(
            role = LLMMessage.Role.USER,
            content = "",
            contentParts = listOf(
                AgentContentPart.ToolResult(
                    id = toolId,
                    name = "execute_shell",
                    content = "x".repeat(resultChars),
                ),
            ),
        )
        return listOf(assistant, result)
    }

    private fun roundBody(index: Int, resultChars: Int = 400): LLMMessage = userText("round $index")

    /** chronological postAnchor: [round0(user, asst, res), round1(...), ...] */
    private fun postAnchor(rounds: Int, resultChars: (Int) -> Int = { 400 }): List<LLMMessage> {
        val out = ArrayList<LLMMessage>()
        for (i in 0 until rounds) {
            out.add(roundBody(i))
            out.addAll(toolRound(i, resultChars(i)))
        }
        return out
    }

    private val budget = PostAnchorTail.Budget(
        budgetTokens = 60_000,
        estimate = WalkBack::estimateMessageTokens,
    )

    // ── 1. within budget: byte-identical passthrough ────────────────────────

    @Test
    fun withinBudgetKeepsEverything() {
        val input = postAnchor(5)
        val result = PostAnchorTail.apply(input, budget)
        assertEquals(input, result.kept)
        assertEquals(0, result.droppedRounds)
        assertFalse(result.hasNotice)
    }

    @Test
    fun emptyAndSingleRoundPassthrough() {
        val single = postAnchor(1)
        val result = PostAnchorTail.apply(single, budget)
        assertEquals(single, result.kept)
        assertFalse(result.hasNotice)

        val empty = PostAnchorTail.apply(emptyList(), budget)
        assertEquals(0, empty.kept.size)
        assertFalse(empty.hasNotice)
    }

    // ── 2. over budget: oldest whole rounds dropped ─────────────────────────

    @Test
    fun overBudgetDropsTheOldestRounds() {
        // Each round ≈ (7 + 11 + 400/4 + 400/4 + tool json) tokens; keep it
        // simple: a 6000-char result ≈ 1500 tokens per round. Budget 4000
        // tokens admits 2 rounds, drops the older ones.
        val input = postAnchor(5, resultChars = { 6000 })
        val tight = PostAnchorTail.Budget(
            budgetTokens = 4_000,
            estimate = WalkBack::estimateMessageTokens,
        )
        val result = PostAnchorTail.apply(input, tight)
        assertTrue("expected drops", result.droppedRounds > 0)
        // Newest rounds kept, oldest gone: the last round body must survive,
        // the first round body must not.
        assertTrue(result.kept.any { it.content == "round 4" })
        assertFalse(result.kept.any { it.content == "round 0" })
        assertEquals(result.droppedRounds, result.droppedMessages / 3)
        assertTrue(result.droppedTokens > 0)
    }

    @Test
    fun newestRoundIsKeptEvenWhenItAloneExceedsTheBudget() {
        val input = postAnchor(3, resultChars = { 20_000 })
        val tiny = PostAnchorTail.Budget(budgetTokens = 10, estimate = WalkBack::estimateMessageTokens)
        val result = PostAnchorTail.apply(input, tiny)
        assertEquals("the newest round is never dropped", 2, result.droppedRounds)
        assertTrue(result.kept.any { it.content == "round 2" })
    }

    // ── 3. pairing: no orphan tool_use / tool_result after the cut ──────────

    @Test
    fun keptSliceNeverOrphansToolUseOrResult() {
        val input = postAnchor(8, resultChars = { if (it % 2 == 0) 8000 else 400 })
        val tight = PostAnchorTail.Budget(budgetTokens = 3_000, estimate = WalkBack::estimateMessageTokens)
        val result = PostAnchorTail.apply(input, tight)
        val keptUseIds = HashSet<String>()
        val keptResultIds = HashSet<String>()
        for (msg in result.kept) {
            for (part in msg.contentParts) {
                when (part) {
                    is AgentContentPart.ToolUse -> keptUseIds.add(part.id)
                    is AgentContentPart.ToolResult -> keptResultIds.add(part.id)
                    else -> Unit
                }
            }
        }
        assertEquals("every kept tool_use has its result", keptUseIds, keptUseIds intersect keptResultIds)
        assertEquals("every kept tool_result has its use", keptResultIds, keptUseIds intersect keptResultIds)
    }

    @Test
    fun droppedSideIsAlsoPairComplete() {
        val input = postAnchor(6, resultChars = { 5000 })
        val tight = PostAnchorTail.Budget(budgetTokens = 3_000, estimate = WalkBack::estimateMessageTokens)
        val result = PostAnchorTail.apply(input, tight)
        val dropped = input.toSet() - result.kept.drop(1).toSet() // minus the notice
        val droppedUseIds = HashSet<String>()
        val droppedResultIds = HashSet<String>()
        for (msg in dropped) {
            for (part in msg.contentParts) {
                when (part) {
                    is AgentContentPart.ToolUse -> droppedUseIds.add(part.id)
                    is AgentContentPart.ToolResult -> droppedResultIds.add(part.id)
                    else -> Unit
                }
            }
        }
        assertEquals(droppedUseIds, droppedResultIds)
    }

    // ── 4. head notice ───────────────────────────────────────────────────────

    @Test
    fun noticeLineInjectedAtHeadWithRealCounts() {
        val input = postAnchor(5, resultChars = { 6000 })
        val tight = PostAnchorTail.Budget(budgetTokens = 4_000, estimate = WalkBack::estimateMessageTokens)
        val result = PostAnchorTail.apply(input, tight)
        assertTrue(result.hasNotice)
        val notice = result.notice!!
        assertEquals(LLMMessage.Role.USER, notice.role)
        assertTrue(
            "notice text must match the spec",
            notice.content.startsWith("[context window: ") &&
                notice.content.contains(" older tool rounds dropped (≈") &&
                notice.content.contains(" tokens) — the summary above covers them; " +
                    "re-run a specific tool if you need fresh output]"),
        )
        // N must be the real dropped-round count.
        assertTrue(notice.content.contains(" ${result.droppedRounds} older tool rounds dropped"))
        // K must be the real dropped-token estimate.
        assertTrue(notice.content.contains("(≈${result.droppedTokens} tokens)"))
        // The notice is the FIRST element of the kept slice.
        assertEquals(notice, result.kept.first())
    }

    @Test
    fun noNoticeWhenNothingDropped() {
        val input = postAnchor(3)
        val result = PostAnchorTail.apply(input, budget)
        assertFalse(result.hasNotice)
    }

    // ── 5. round splitting (lead-in group before the first user round) ──────

    @Test
    fun leadingAssistantMessagesFormTheirOwnDroppableGroup() {
        // postAnchor that starts mid-round: assistant tool_use + result, then
        // proper rounds. The lead-in pair is group 0 and must be dropped
        // whole — never leaving the tool_result without its tool_use.
        val lead = toolRound(100, resultChars = 4000)
        val input = lead + postAnchor(3, resultChars = { 4000 })
        val tight = PostAnchorTail.Budget(budgetTokens = 3_500, estimate = WalkBack::estimateMessageTokens)
        val result = PostAnchorTail.apply(input, tight)
        assertTrue(result.droppedRounds >= 1)
        val keptUseIds = HashSet<String>()
        val keptResultIds = HashSet<String>()
        for (msg in result.kept) {
            for (part in msg.contentParts) {
                when (part) {
                    is AgentContentPart.ToolUse -> keptUseIds.add(part.id)
                    is AgentContentPart.ToolResult -> keptResultIds.add(part.id)
                    else -> Unit
                }
            }
        }
        assertEquals(keptUseIds, keptResultIds)
    }

    @Test
    fun splitRoundsGroupsByRoundStartUser() {
        val a = LLMMessage(
            role = LLMMessage.Role.USER,
            content = "",
            contentParts = listOf(AgentContentPart.ToolResult("r1", "t", "out")),
        )
        val groups = PostAnchorTail.splitRounds(listOf(userText("u1"), a, userText("u2")))
        assertEquals(2, groups.size)
        assertEquals(2, groups[0].size)
        assertEquals(1, groups[1].size)
    }
}
