package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import org.json.JSONObject

/**
 * Main-session grep. Same engine as [GrepSourceTool], public name `grep`.
 *
 * Adapted from XINCODE-Public GrepTool (GPL-3.0-or-later).
 */
object GrepTool {
    const val NAME = "grep"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Search files for a pattern and return matching lines with context in one call. Prefer this over shell grep.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user."),
            "pattern" to AgentToolParam("string", "Text or regex to match."),
            "path" to AgentToolParam("string", "File or directory (default /var/minis/workspace)."),
            "context" to AgentToolParam("integer", "Lines of context (0-6, default 2)."),
            "is_regex" to AgentToolParam("boolean", "Treat pattern as regex (default false)."),
            "max_matches" to AgentToolParam("integer", "Cap on matches (default 50)."),
        ),
        required = listOf("tool_title", "pattern"),
        propertyOrdering = listOf("tool_title", "pattern", "path", "context", "is_regex", "max_matches"),
    )

    fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
        val patched = try {
            val o = JSONObject(argsJson)
            if (o.optString("tool_title").isBlank()) o.put("tool_title", NAME)
            o.toString()
        } catch (_: Exception) {
            argsJson
        }
        return GrepSourceTool.execute(patched, sessionId, context)
    }
}
