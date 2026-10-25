package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.data.repository.SkillRepository
import org.json.JSONObject

/**
 * Agent-facing skill library writes. Bundled skills are read-only.
 *
 * Adapted from XINCODE-Public SkillManageTool (GPL-3.0-or-later).
 */
object SkillManageTool {
    const val NAME = "skill_manage"
    private const val READ_TTL_MS = 5 * 60 * 1000L
    private val readAt = HashMap<String, Long>()

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Manage the skill library. action=view|create|patch|edit|remove|list. " +
            "Must view before patch/edit. Bundled skills are read-only.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user."),
            "action" to AgentToolParam(
                "string",
                "view, create, patch, edit, remove, or list.",
                enumValues = listOf("view", "create", "patch", "edit", "remove", "list"),
            ),
            "name" to AgentToolParam("string", "Skill name."),
            "description" to AgentToolParam("string", "create/edit description."),
            "content" to AgentToolParam("string", "create/edit markdown body."),
            "old_string" to AgentToolParam("string", "patch: unique fragment to replace."),
            "new_string" to AgentToolParam("string", "patch: replacement."),
        ),
        required = listOf("tool_title", "action"),
        propertyOrdering = listOf("tool_title", "action", "name", "description", "content", "old_string", "new_string"),
    )

    fun execute(argsJson: String, repo: SkillRepository?): ToolExecutionResult {
        val toolTitle = try { JSONObject(argsJson).optString("tool_title", NAME) } catch (_: Exception) { NAME }
        if (repo == null) return ToolExecutionResult("Skills unavailable", false, toolTitle = toolTitle)
        val args = JSONObject(argsJson)
        val action = args.optString("action")
        val name = args.optString("name").trim()
        if (action == "list") {
            val body = repo.skills.value.joinToString("\n") { "${it.name}\t${it.description.take(80)}" }
            return ToolExecutionResult(body.ifBlank { "(no skills)" }, true, toolTitle = toolTitle)
        }
        if (name.isBlank()) return ToolExecutionResult("Error: name required", false, toolTitle = toolTitle)
        val skill = repo.skills.value.firstOrNull { it.name.equals(name, true) || it.id.equals(name, true) }
        return when (action) {
            "view" -> {
                if (skill == null) return ToolExecutionResult("not found: $name", false, toolTitle = toolTitle)
                readAt[skill.name] = System.currentTimeMillis()
                ToolExecutionResult("# ${skill.name}\n${skill.description}\n\n${skill.body}", true, toolTitle = toolTitle)
            }
            "create" -> {
                if (skill != null) return ToolExecutionResult("exists: $name", false, toolTitle = toolTitle)
                val created = repo.add(
                    name = name,
                    description = args.optString("description"),
                    body = args.optString("content"),
                    source = SkillRepository.ImportSource.SESSION,
                )
                ToolExecutionResult(if (created != null) "created ${created.name}" else "create failed", created != null, toolTitle = toolTitle)
            }
            "patch", "edit" -> {
                if (skill == null) return ToolExecutionResult("not found: $name", false, toolTitle = toolTitle)
                if (skill.importSource == SkillRepository.ImportSource.BUNDLED) {
                    return ToolExecutionResult("bundled skill is read-only", false, toolTitle = toolTitle)
                }
                val last = readAt[skill.name] ?: 0L
                if (System.currentTimeMillis() - last > READ_TTL_MS) {
                    return ToolExecutionResult("view this skill first (TTL 5m)", false, toolTitle = toolTitle)
                }
                if (action == "patch") {
                    val old = args.optString("old_string")
                    val new = args.optString("new_string")
                    if (old.isBlank()) return ToolExecutionResult("old_string required", false, toolTitle = toolTitle)
                    val count = skill.body.split(old).size - 1
                    if (count != 1) return ToolExecutionResult("old_string matches $count times", false, toolTitle = toolTitle)
                    val ok = repo.update(skill.id, body = skill.body.replaceFirst(old, new))
                    ToolExecutionResult(if (ok) "patched ${skill.name}" else "patch failed", ok, toolTitle = toolTitle)
                } else {
                    val ok = repo.update(
                        skill.id,
                        description = args.optString("description").ifBlank { skill.description },
                        body = args.optString("content").ifBlank { skill.body },
                    )
                    ToolExecutionResult(if (ok) "edited ${skill.name}" else "edit failed", ok, toolTitle = toolTitle)
                }
            }
            "remove" -> {
                if (skill == null) return ToolExecutionResult("not found: $name", false, toolTitle = toolTitle)
                if (skill.importSource == SkillRepository.ImportSource.BUNDLED) {
                    return ToolExecutionResult("bundled skill is read-only", false, toolTitle = toolTitle)
                }
                repo.delete(skill.id)
                ToolExecutionResult("removed ${skill.name}", true, toolTitle = toolTitle)
            }
            else -> ToolExecutionResult("unknown action $action", false, toolTitle = toolTitle)
        }
    }
}
