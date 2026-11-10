package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.goal.GoalManager
import org.json.JSONObject

object GoalTool {
    const val NAME = "update_goal"

    fun definition() = AgentToolDefinition(
        name = NAME,
        // [T-goal-created-with-plan] The model can now CREATE the Goal, which
        // the harness it mirrors also allows (create_goal): a long-running
        // objective must be recorded, not remembered. Pause/resume/clear stay
        // user-only — they control whether the session keeps working, and that
        // is the human's decision.
        // [T-goal-model-origin] A different objective supersedes the model's
        // OWN previous Goal (a finished task must not keep steering new ones);
        // an objective the human wrote is never replaced.
        // [T-goal-tool-clear] op=clear is the delete the tool lacked: a
        // finished or replaced task must be removable by the agent, otherwise
        // the row survives and keeps mounting into later requests.
        description = "Read, create, update or clear the current session Goal. op=get reads it; " +
            "op=create records THIS task's Goal. Repeating the same objective is idempotent; a different " +
            "objective replaces a Goal the model itself wrote earlier (unless the human paused it), so a " +
            "finished task never keeps steering the next one. A Goal written by the human is never " +
            "overwritten by op=create. " +
            "op=clear DELETES the Goal (any status except paused) — use it when the task it described is " +
            "over or has been replaced and you are about to record a different objective; a Goal the human " +
            "paused is refused on purpose, report the conflict instead of forcing it. " +
            "op=complete and op=blocked report outcome, which the harness treats as the model's call. " +
            "Creating the task board with agent_plan already records the Goal from its objective — call " +
            "op=create explicitly only for a long-running objective that has no board. " +
            "You may never pause or resume the Goal: those control whether the session keeps working, " +
            "and that is the user's decision.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "Short status text shown to the user."),
            "op" to AgentToolParam(
                "string",
                "Operation",
                enumValues = listOf("get", "create", "complete", "blocked", "clear"),
            ),
            "objective" to AgentToolParam(
                "string",
                "Required for op=create: the concrete completion objective, in one sentence.",
            ),
            "token_budget" to AgentToolParam("integer", "Optional positive token budget for op=create."),
            "condition" to AgentToolParam("string", "Specific unmet condition when op=blocked; repeated identical reports are counted."),
        ),
        required = listOf("op"),
        propertyOrdering = listOf("tool_title", "op", "objective", "token_budget", "condition"),
    )

    suspend fun execute(argsJson: String, sessionId: String, manager: GoalManager): ToolExecutionResult {
        return try {
            val args = JSONObject(argsJson)
            val op = args.optString("op")
            if (op == "clear") {
                // [T-goal-tool-clear] Deletion has no state to render afterwards,
                // so it returns its own result instead of the shared render path.
                val cleared = manager.clearByModel(sessionId)
                val title = args.optString("tool_title", "Goal")
                return if (cleared) {
                    ToolExecutionResult("Goal cleared.", true, toolTitle = title)
                } else {
                    ToolExecutionResult(
                        "Goal not cleared: none exists, or the human paused it — a paused Goal is theirs to " +
                            "resume or clear; report the conflict instead of forcing it.",
                        false,
                        toolTitle = title,
                    )
                }
            }
            val goal = when (op) {
                "get" -> manager.get(sessionId)
                "create" -> {
                    val objective = args.optString("objective").trim()
                    if (objective.isEmpty()) {
                        return ToolExecutionResult(
                            "Goal error: op=create needs objective — one sentence stating what the task must achieve",
                            false,
                            toolTitle = args.optString("tool_title", "Goal"),
                        )
                    }
                    val budget = if (args.has("token_budget")) args.optLong("token_budget").takeIf { it > 0 } else null
                    manager.createByModel(sessionId, objective, budget)
                }
                "complete" -> manager.updateByModel(sessionId, "complete", null)
                "blocked" -> manager.updateByModel(sessionId, "blocked", args.optString("condition").takeIf(String::isNotBlank))
                else -> return ToolExecutionResult("Invalid Goal operation", false)
            }
            ToolExecutionResult(manager.render(goal), true, toolTitle = args.optString("tool_title", "Goal"))
        } catch (error: Exception) {
            ToolExecutionResult("Goal error: ${error.message ?: "invalid request"}", false)
        }
    }
}
