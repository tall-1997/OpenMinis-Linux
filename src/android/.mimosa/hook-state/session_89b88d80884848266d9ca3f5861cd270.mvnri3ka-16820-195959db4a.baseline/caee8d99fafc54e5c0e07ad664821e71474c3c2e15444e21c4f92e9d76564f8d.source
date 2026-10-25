package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.data.repository.SkillRepository
import org.json.JSONObject

/**
 * Load a skill body into the tool result so the agent can follow it.
 *
 * Adapted from XINCODE-Public InvokeSkillTool (GPL-3.0-or-later).
 */
object InvokeSkillTool {
    const val NAME = "invoke_skill"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Load an installed skill by name and return its SKILL.md body. Use this before following a skill's steps.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user."),
            "name" to AgentToolParam("string", "Skill name (leading slash optional)."),
        ),
        required = listOf("tool_title", "name"),
        propertyOrdering = listOf("tool_title", "name"),
    )

    fun execute(argsJson: String, repo: SkillRepository?, sessionId: String): ToolExecutionResult {
        val toolTitle = try { JSONObject(argsJson).optString("tool_title", NAME) } catch (_: Exception) { NAME }
        if (repo == null) return ToolExecutionResult("Skills unavailable", false, toolTitle = toolTitle)
        val raw = try {
            JSONObject(argsJson).optString("name").ifBlank { JSONObject(argsJson).optString("skill") }
        } catch (_: Exception) { "" }.trim().trimStart('/')
        if (raw.isBlank()) return ToolExecutionResult("Error: name required", false, toolTitle = toolTitle)
        val skills = repo.skills.value.filter { it.isEnabled || repo.isEnabledForSession(it.id, sessionId) }
        val exact = skills.firstOrNull { it.name.equals(raw, true) || it.id.equals(raw, true) }
        val loose = skills.filter {
            it.name.replace(Regex("[\\s_-]"), "").equals(raw.replace(Regex("[\\s_-]"), ""), true)
        }
        val skill = exact ?: loose.singleOrNull()
            ?: return ToolExecutionResult(
                "Skill not found: $raw. Available: ${skills.joinToString { it.name }}",
                false,
                toolTitle = toolTitle,
            )
        repo.recordSkillUse(skill.id)
        val body = skill.body.ifBlank { "(empty skill body)" }
        return ToolExecutionResult("# ${skill.name}\n${skill.description}\n\n$body", true, toolTitle = toolTitle)
    }
}
