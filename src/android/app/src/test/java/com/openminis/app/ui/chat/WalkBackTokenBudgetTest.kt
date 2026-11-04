package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-compact-keep-budget] The token axis opencode's `keep` models.
 *
 * The existing keep rule counts user turns, which says nothing about size: three
 * turns carrying 100 KB tool results cost more than thirty short ones. The
 * budget is opt-in (`maxEstimatedTokens = null` by default) so the shipped
 * turn-counted behaviour stays byte-identical until a caller opts in — the
 * first test is the pin for that.
 */
class WalkBackTokenBudgetTest {

    private fun userText(text: String) = LLMMessage(role = LLMMessage.Role.USER, content = text)

    private fun assistantText(text: String) = LLMMessage(role = LLMMessage.Role.ASSISTANT, content = text)

    /** One-char bodies estimate to 1 token each, so budgets below are message counts. */
    private fun unitHistory() = listOf(
        userText("a"),      // 0
        assistantText("b"), // 1
        userText("c"),      // 2
        assistantText("d"), // 3
        userText("e"),      // 4
        assistantText("f"), // 5
    )

    @Test
    fun noBudgetKeepsTheTurnCountedResult() {
        val result = WalkBack.bounded(unitHistory(), anchorIdx = 5, maxUserTextTurns = 3, maxMessages = 100)
        assertEquals(WalkBack.STOP_USER_TEXT_TARGET_MET, result.stopReason)
        assertEquals(3, result.userTextTurnsFound)
        assertEquals(0, result.priorIdx)
        assertEquals(6, result.messageCount)
    }

    @Test
    fun budgetStopsBeforeExceedingTokens() {
        val result = WalkBack.bounded(
            unitHistory(),
            anchorIdx = 5,
            maxUserTextTurns = 3,
            maxMessages = 100,
            maxEstimatedTokens = 3,
        )
        assertEquals(WalkBack.STOP_TOKEN_BUDGET_WOULD_EXCEED, result.stopReason)
        // Only the [4..5] round fit in three one-token messages.
        assertEquals(4, result.priorIdx)
        assertEquals(1, result.userTextTurnsFound)
        assertEquals(2, result.messageCount)
    }

    @Test
    fun aSingleHugeToolResultBlowsTheBudgetDespiteFewTurns() {
        // 4000 chars / 4 = 1000 estimated tokens on its own.
        val huge = "x".repeat(4000)
        val history = listOf(
            userText("a"),
            assistantText("b"),
            userText(huge),
            assistantText("c"),
        )
        val result = WalkBack.bounded(
            history,
            anchorIdx = 3,
            maxUserTextTurns = 3,
            maxMessages = 100,
            maxEstimatedTokens = 100,
        )
        assertEquals(WalkBack.STOP_TOKEN_BUDGET_WOULD_EXCEED, result.stopReason)
        assertNull("nothing may be accepted when the first round already blows the budget", result.priorIdx)
    }

    @Test
    fun messageCapStillWinsWhenItFiresFirst() {
        val result = WalkBack.bounded(
            unitHistory(),
            anchorIdx = 5,
            maxUserTextTurns = 3,
            maxMessages = 2,
            maxEstimatedTokens = 1_000,
        )
        assertEquals(WalkBack.STOP_MESSAGE_CAP_WOULD_EXCEED, result.stopReason)
    }

    @Test
    fun toolResultsCountTowardTheEstimate() {
        val message = LLMMessage(
            role = LLMMessage.Role.USER,
            content = "",
            contentParts = listOf(AgentContentPart.ToolResult("id_1", "shell_execute", "y".repeat(400))),
        )
        assertEquals(100, WalkBack.estimateMessageTokens(message))
    }

    @Test
    fun invalidAnchorIsStillReportedBeforeAnyBudgetWork() {
        val result = WalkBack.bounded(unitHistory(), anchorIdx = 99, maxUserTextTurns = 3, maxMessages = 100)
        assertEquals(WalkBack.STOP_INVALID_ANCHOR, result.stopReason)
        assertNull(result.priorIdx)
    }

    @Test
    fun roundIsNeverSplitAtAToolResultCarryingUserMessage() {
        // The tool result is a USER message but not a round boundary; the
        // budget check must not accept it as one.
        val history = listOf(
            userText("do the thing"),
            LLMMessage(
                role = LLMMessage.Role.ASSISTANT,
                content = "",
                contentParts = listOf(AgentContentPart.ToolUse("call_A", "shell_execute", org.json.JSONObject())),
            ),
            LLMMessage(
                role = LLMMessage.Role.USER,
                content = "",
                contentParts = listOf(AgentContentPart.ToolResult("call_A", "shell_execute", "ok")),
            ),
        )
        val result = WalkBack.bounded(history, anchorIdx = 2, maxUserTextTurns = 1, maxMessages = 100)
        assertEquals(0, result.priorIdx)
        assertEquals(1, result.userTextTurnsFound)
    }
}
