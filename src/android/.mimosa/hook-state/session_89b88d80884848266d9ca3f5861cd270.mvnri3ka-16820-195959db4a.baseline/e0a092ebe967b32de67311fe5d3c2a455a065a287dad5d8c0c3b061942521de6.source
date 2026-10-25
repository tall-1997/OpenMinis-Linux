package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import org.json.JSONObject

/**
 * Hand a hard sub-question to a stronger reasoning pass.
 *
 * Adapted from XINCODE-Public AskReasoningTool (GPL-3.0-or-later).
 * The actual LLM call is injected by ChatViewModel.
 */
object AskReasoningTool {
    const val NAME = "ask_reasoning"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Hand a hard math/logic/planning sub-question to a dedicated reasoning pass and return its conclusion.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user."),
            "question" to AgentToolParam("string", "The sub-question plus necessary context."),
        ),
        required = listOf("tool_title", "question"),
        propertyOrdering = listOf("tool_title", "question"),
    )

    suspend fun execute(
        argsJson: String,
        ask: suspend (system: String, user: String) -> String,
    ): ToolExecutionResult {
        val toolTitle = try { JSONObject(argsJson).optString("tool_title", NAME) } catch (_: Exception) { NAME }
        val q = try { JSONObject(argsJson).optString("question").trim() } catch (_: Exception) { "" }
        if (q.isBlank()) return ToolExecutionResult("Error: question required", false, toolTitle = toolTitle)
        return try {
            val out = ask(
                "你是严谨的推理助手。一步步想清楚,给出最终结论,并简述关键推理。",
                q,
            )
            ToolExecutionResult(out, true, toolTitle = toolTitle)
        } catch (e: Exception) {
            ToolExecutionResult("ask_reasoning failed: ${e.message}", false, toolTitle = toolTitle)
        }
    }
}
