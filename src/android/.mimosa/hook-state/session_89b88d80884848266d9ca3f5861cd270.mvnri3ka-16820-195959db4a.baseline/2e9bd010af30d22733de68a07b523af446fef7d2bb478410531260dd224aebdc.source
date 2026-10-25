package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import org.json.JSONArray
import org.json.JSONObject

/**
 * Fan-out read-only workers. Maps onto spawn_agent kind=explore.
 *
 * Adapted from XINCODE-Public WolfpackOrchestrator (GPL-3.0-or-later).
 */
object WolfpackTool {
    const val NAME = "wolfpack_run"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Run several short read-only recon tasks in parallel (explore kind, no writes). " +
            "Each task is a string. Prefer this for 'check these N files/questions at once'.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user."),
            "tasks" to AgentToolParam(
                type = "array",
                description = "List of task strings, or objects with a task field.",
                items = AgentToolParam("string", "One recon task"),
            ),
        ),
        required = listOf("tool_title", "tasks"),
        propertyOrdering = listOf("tool_title", "tasks"),
    )

    fun toSpawnArgs(argsJson: String): String {
        val src = JSONObject(argsJson)
        val raw = src.optJSONArray("tasks") ?: JSONArray()
        val tasks = JSONArray()
        for (i in 0 until raw.length()) {
            val item = raw.opt(i)
            val prompt = when (item) {
                is JSONObject -> item.optString("task").ifBlank { item.optString("prompt") }
                else -> item.toString()
            }
            if (prompt.isBlank()) continue
            tasks.put(
                JSONObject()
                    .put("prompt", prompt)
                    .put("kind", SubAgentKind.EXPLORE)
                    .put("role", "探索者"),
            )
        }
        return JSONObject()
            .put("tool_title", src.optString("tool_title", NAME))
            .put("tasks", tasks)
            .toString()
    }
}
