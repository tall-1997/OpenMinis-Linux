package com.openminis.app.agent

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.ToolIdNormalization
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-toolid-alias-pairing] A Responses combined id (`call|fc`) and its bare
 * call_id are one logical call. After request-time normalization the pair must
 * still share one id, so the recent-round protection in the pruning tiers
 * (which pair by id) can keep the round closed instead of folding one side.
 */
class ToolResultAgingSplitCarrierTest {

    @Test
    fun `mixed-form recent round stays paired and verbatim while the old round folds`() {
        val history = listOf(
            LLMMessage(LLMMessage.Role.ASSISTANT, "", contentParts = listOf(
                AgentContentPart.ToolUse("toolu_old", "shell", JSONObject()),
            )),
            LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(
                AgentContentPart.ToolResult("toolu_old", "shell", "old output — long enough to fold. ${"x".repeat(4000)}"),
            )),
            // Model switch / DB reload: the call stored combined, the result bare.
            LLMMessage(LLMMessage.Role.ASSISTANT, "", contentParts = listOf(
                AgentContentPart.ToolUse("call_new|fc_x", "shell", JSONObject()),
            )),
            LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(
                AgentContentPart.ToolResult("call_new", "shell", "recent output"),
            )),
        )
        val normalized = ToolIdNormalization.normalizeMessages(history)
        val callId = (normalized[2].contentParts.single() as AgentContentPart.ToolUse).id
        val resultId = (normalized[3].contentParts.single() as AgentContentPart.ToolResult).id
        assertEquals(callId, resultId)

        val aged = ToolResultAging.age(
            messages = normalized,
            keepRecent = 1,
            minChars = 0,
            verifiedSpillPaths = emptySet(),
        )
        val recent = aged.messages.last().contentParts.single() as AgentContentPart.ToolResult
        assertEquals("recent output", recent.content)
        val old = aged.messages[1].contentParts.single() as AgentContentPart.ToolResult
        assertEquals(true, ToolResultProjection.isProjected(old.content))
    }
}
