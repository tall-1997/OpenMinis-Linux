package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import org.json.JSONArray
import org.json.JSONObject

/**
 * On-demand tool discovery. The main conversation starts with a small core
 * schema; long-tail tools are added only after the model asks for them.
 */
object FindTools {
    const val NAME = "find_tools"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Find tools by capability or keyword. Matching tools are enabled for subsequent turns; call this before using a tool that is not currently listed.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "Short status title."),
            "query" to AgentToolParam("string", "Capability or keywords, for example calendar, image, cron, translation, or accessibility."),
            "limit" to AgentToolParam("integer", "Maximum results, from 1 to 12."),
        ),
        required = listOf("query"),
        propertyOrdering = listOf("tool_title", "query", "limit"),
    )

    data class Match(val definition: AgentToolDefinition, val score: Int)

    fun search(
        query: String,
        definitions: List<AgentToolDefinition>,
        limit: Int = 8,
    ): List<Match> {
        val terms = query.lowercase()
            .split(Regex("[^\\p{L}\\p{N}_-]+"))
            .filter { it.length >= 2 }
            .distinct()
        if (terms.isEmpty()) return emptyList()
        return definitions.asSequence()
            .filter { it.name != NAME }
            .map { def ->
                val haystack = "${def.name} ${def.description} ${def.parameters.keys.joinToString(" ")}".lowercase()
                val score = terms.fold(0) { acc, term ->
                    acc + when {
                        def.name.equals(term, true) -> 10
                        def.name.contains(term, true) -> 6
                        haystack.contains(term) -> 2
                        else -> 0
                    }
                }
                Match(def, score)
            }
            .filter { it.score > 0 }
            .sortedWith(compareByDescending<Match> { it.score }.thenBy { it.definition.name })
            .take(limit.coerceIn(1, 12))
            .toList()
    }

    fun format(matches: List<Match>): String {
        if (matches.isEmpty()) return "No matching tools. Try a capability name such as browser, media, schedule, memory, or accessibility."
        val json = JSONArray()
        matches.forEach { match ->
            val def = match.definition
            json.put(JSONObject().apply {
                put("name", def.name)
                put("description", def.description)
                put("parameters", JSONArray(def.parameters.keys.sorted()))
                put("score", match.score)
            })
        }
        return JSONObject().put("tools", json).put("instruction", "Matching tools are now enabled for the next model turn.").toString()
    }
}
