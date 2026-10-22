package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import org.json.JSONArray
import org.json.JSONObject

/**
 * Plan-mode exit gate: the agent submits its plan (markdown + task items) and
 * the tool blocks until the user approves, requests changes, or rejects —
 * the same deferred-pause shape as [AskUserQuestion].
 *
 * This object is the pure half (definition, parsing, result formatting) so it
 * unit-tests without a ViewModel; the suspend/deferred half lives in
 * ChatViewModelPlanExt because it needs the review card state.
 */
object ExitPlanModeTool {
    const val NAME = "exit_plan_mode"
    const val MAX_ITEMS = 20

    data class PlanItem(
        val title: String,
        val description: String = "",
    )

    data class Submission(
        val markdown: String,
        val items: List<PlanItem>,
    )

    /** User decision returned to the model as the tool result. */
    sealed class Decision {
        data class Approved(val items: List<PlanItem>) : Decision()
        data class Revise(val feedback: String) : Decision()
        data object Rejected : Decision()
    }

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = """Submit the plan for user approval and exit plan mode. Use ONLY while plan mode is active (the system prompt says so). Pass:
  plan_markdown (string, required): the plan itself, concise markdown the user will review.
  items (JSON array, required): 1-20 objects {title, description} — one per actionable task. On approval these become the plan board.

The tool blocks until the user decides:
- approved: the items are on the board, plan mode is OFF — execute now, advancing items with agent_plan.
- revise(feedback): update the plan and call exit_plan_mode again.
- rejected: plan mode is OFF; wrap up without executing.""",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user."),
            "plan_markdown" to AgentToolParam(
                "string",
                "The plan in concise markdown for the user to review.",
            ),
            "items" to AgentToolParam(
                "string",
                "JSON array of 1-20 objects {title, description}. Also accepted as a JSON array value.",
            ),
        ),
        required = listOf("plan_markdown", "items"),
        propertyOrdering = listOf("tool_title", "plan_markdown", "items"),
    )

    /**
     * Parses and validates a submission. Returns null (with the reason in
     * `parseError`) when the payload is unusable, so the caller can fail the
     * tool call without pausing the loop on garbage.
     */
    fun parse(argsJson: String): Pair<Submission?, String?> {
        val args = runCatching { JSONObject(argsJson) }.getOrElse { return null to "invalid JSON arguments" }
        val markdown = args.optString("plan_markdown").trim()
        if (markdown.isEmpty()) return null to "plan_markdown is required"
        val rawItems = args.opt("items")
        val array = when (rawItems) {
            is JSONArray -> rawItems
            is String -> if (rawItems.isBlank()) JSONArray() else runCatching { JSONArray(rawItems) }.getOrElse { return null to "items is not a valid JSON array" }
            else -> JSONArray()
        }
        val items = mutableListOf<PlanItem>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val title = obj.optString("title").trim()
            if (title.isEmpty()) continue
            items.add(PlanItem(title, obj.optString("description").trim()))
            if (items.size >= MAX_ITEMS) break
        }
        if (items.isEmpty()) return null to "items must contain at least one {title} entry"
        return Submission(markdown, items) to null
    }

    fun formatDecision(decision: Decision): String = when (decision) {
        is Decision.Approved -> buildString {
            append("Plan approved. The board now holds your items:\n")
            decision.items.forEachIndexed { i, item ->
                append("${i + 1}. [pending] ${item.title}\n")
            }
            append("Plan mode is OFF. Execute the plan now; advance board items with agent_plan (pending -> active -> done) as you work.")
        }
        is Decision.Revise ->
            "Plan needs changes (plan mode stays ON): ${decision.feedback.trim().ifEmpty { "(no specific feedback given)" }}. Revise the plan and call exit_plan_mode again."
        is Decision.Rejected ->
            "Plan rejected by the user. Plan mode is OFF. Do NOT execute the plan; summarize what you found and stop."
    }

    /** Guard message returned by mutating tools while plan mode is active. */
    fun refusalMessage(): String =
        "Plan mode is active: mutating tools are refused until the plan is approved. " +
            "Investigate with read-only tools, then call exit_plan_mode with the plan."
}
