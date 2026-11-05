package com.openminis.app.agent

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-age-only-recoverable] A fold trades tokens for a recovery step, so it may
 * only happen where recovery is cheap: a spill path, a result big enough to have
 * been spilled, or one big enough that the saving dwarfs a re-run. Errors are the
 * diagnostic and are never folded.
 */
class ToolResultAgingRecoverabilityTest {

    private fun result(
        id: String,
        chars: Int,
        isError: Boolean = false,
        spillPath: String? = null,
    ) = AgentContentPart.ToolResult(
        id = id,
        name = "shell_execute",
        content = buildString {
            if (spillPath != null) append("full output: ").append(spillPath).append('\n')
            append("x".repeat(chars))
        },
        isError = isError,
    )

    private fun history(vararg parts: AgentContentPart.ToolResult): List<LLMMessage> =
        parts.map { LLMMessage(role = LLMMessage.Role.ASSISTANT, content = "", contentParts = listOf(it)) }

    private fun aged(messages: List<LLMMessage>): Int = messages.sumOf { m ->
        m.contentParts.count { it is AgentContentPart.ToolResult && ToolResultAging.isAged(it.content) }
    }

    @Test
    fun `errors are never folded even when huge`() {
        val plan = ToolResultAging.age(
            history(*(1..4).map { result("e$it", 20_000, isError = true) }.toTypedArray()),
            keepRecent = 1,
            minChars = 1_200,
        )
        assertEquals(0, plan.agedCount)
    }

    @Test
    fun `a medium unsplilled result stays because recovery would mean a re-run`() {
        val plan = ToolResultAging.age(
            history(*(1..4).map { result("m$it", 3_000) }.toTypedArray()),
            keepRecent = 1,
            minChars = 1_200,
        )
        assertEquals(0, plan.agedCount)
    }

    @Test
    fun `a result with a spill path folds even when small`() {
        val plan = ToolResultAging.age(
            history(
                *(1..4).map {
                    result("s$it", 3_000, spillPath = "/var/minis/workspace/tool-spill/tool_$it.txt")
                }.toTypedArray(),
            ),
            keepRecent = 1,
            minChars = 1_200,
        )
        assertEquals(3, plan.agedCount)
        val folded = plan.messages[0].contentParts[0] as AgentContentPart.ToolResult
        assertTrue(folded.content.contains("/var/minis/workspace/tool-spill/"))
    }

    @Test
    fun `a large unsplilled result still folds because it was spilled by size`() {
        val plan = ToolResultAging.age(
            history(*(1..4).map { result("l$it", ToolResultAging.SPILL_LIMIT_CHARS + 1_000) }.toTypedArray()),
            keepRecent = 1,
            minChars = 1_200,
        )
        assertEquals(3, plan.agedCount)
    }
}
