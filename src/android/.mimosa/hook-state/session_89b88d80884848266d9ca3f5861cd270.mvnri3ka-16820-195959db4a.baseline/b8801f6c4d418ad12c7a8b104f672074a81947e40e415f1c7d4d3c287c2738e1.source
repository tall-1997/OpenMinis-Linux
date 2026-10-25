package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.goal.GoalManager
import org.json.JSONObject

object GoalTool {
    const val NAME = "update_goal"

    fun definition() = AgentToolDefinition(
        name = NAME,
        description = "Read the current session Goal or report objective progress. You may only mark it complete or report a blocked condition; never create, pause, resume, or change its objective. Call get before acting when a Goal is present.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "Short status text shown to the user."),
            "op" to AgentToolParam("string", "Operation", enumValues = listOf("get", "complete", "blocked")),
            "condition" to AgentToolParam("string", "Specific unmet condition when op=blocked; repeated identical reports are counted."),
        ),
        required = listOf("op"),
        propertyOrdering = listOf("tool_title", "op", "condition"),
    )

    suspend fun execute(argsJson: String, sessionId: String, manager: GoalManager): ToolExecutionResult = try {
        val args = JSONObject(argsJson)
        val op = args.optString("op")
        val goal = when (op) {
            "get" -> manager.get(sessionId)
            "complete" -> manager.updateByModel(sessionId, "complete", null)
            "blocked" -> manager.updateByModel(sessionId, "blocked", args.optString("condition").takeIf(String::isNotBlank))
            else -> return ToolExecutionResult("Invalid Goal operation", false)
        }
        ToolExecutionResult(manager.render(goal), true, toolTitle = args.optString("tool_title", "Goal"))
    } catch (error: Exception) {
        ToolExecutionResult("Goal error: ${error.message ?: "invalid request"}", false)
    }
}
