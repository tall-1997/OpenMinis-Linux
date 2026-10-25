package com.openminis.app.tools

import com.openminis.app.accessibility.A11yScriptRecorder
import com.openminis.app.accessibility.A11ySkillStore
import com.openminis.app.accessibility.ServiceA11yActor
import com.openminis.app.accessibility.SkillMatcher
import com.openminis.app.accessibility.SkillReplayEngine
import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import org.json.JSONObject

/**
 * [T-a11y-skill] Record-once / replay-many for repetitive device operations.
 *
 * The user records a flow with [A11yScriptRecorder] (Settings → Accessibility
 * recording dialog); the agent then matches a natural-language instruction
 * against stored skills and replays them via [SkillReplayEngine] with
 * label-first re-resolution (坐标自愈), popup pre-dismissal and loading waits.
 *
 * Distinct from `invoke_skill` / `skill_manage`: those handle SKILL.md text
 * skills; this tool handles recorded ACTION sequences.
 */
object UiSkillTool {
    const val NAME = "ui_skill"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Replay previously recorded device-operation skills. " +
            "REPEATED device flows: try ui_skill match/replay BEFORE manual ui_read+ui_action; " +
            "only fall back to manual ui_action when replay fails. " +
            "action=list (list stored skills), match {instruction} (find best skill for an instruction), " +
            "replay {name?|instruction?} (run a skill: label-first re-resolution, popup auto-dismiss, loading wait), " +
            "save {name, instruction} (persist the latest recording — start recording first), " +
            "delete {name}. Record via Settings → Accessibility → 录制场景. " +
            "Requires Accessibility permission for replay.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user."),
            "action" to AgentToolParam(
                "string",
                "One of: list, match, replay, save, delete.",
                enumValues = listOf("list", "match", "replay", "save", "delete"),
            ),
            "name" to AgentToolParam("string", "Skill name (replay by name, save, delete)."),
            "instruction" to AgentToolParam("string", "User instruction to match (match / replay / save)."),
        ),
        required = listOf("tool_title", "action"),
        propertyOrdering = listOf("tool_title", "action", "name", "instruction"),
    )

    fun execute(argsJson: String, store: A11ySkillStore?): ToolExecutionResult {
        val obj = runCatching { JSONObject(argsJson) }.getOrElse {
            return ToolExecutionResult("Error: invalid args: ${it.message}", false,
                errorCode = ToolErrorCode.INVALID_ARGUMENTS)
        }
        val action = obj.optString("action").lowercase()
        val toolTitle = obj.optString("tool_title", NAME)
        if (store == null) {
            return ToolExecutionResult("Error: a11y skill store unavailable (context not wired)", false,
                errorCode = ToolErrorCode.UNSUPPORTED, toolTitle = toolTitle)
        }
        return when (action) {
            "list" -> list(store, toolTitle)
            "match" -> match(obj, store, toolTitle)
            "replay" -> replay(obj, store, toolTitle)
            "save" -> save(obj, store, toolTitle)
            "delete" -> delete(obj, store, toolTitle)
            else -> ToolExecutionResult(
                "Error: unknown action '$action' (支持: list/match/replay/save/delete)",
                false, errorCode = ToolErrorCode.INVALID_ARGUMENTS,
                recoveryHint = "Choose one of: list, match, replay, save, delete.",
                toolTitle = toolTitle,
            )
        }
    }

    private fun list(store: A11ySkillStore, toolTitle: String): ToolExecutionResult {
        val skills = store.all()
        if (skills.isEmpty()) {
            return ToolExecutionResult("(no a11y skills yet — record one via Settings → Accessibility → 录制场景, then ui_skill save)", true, toolTitle = toolTitle)
        }
        val body = skills.sortedByDescending { it.lastUsedAt }.joinToString("\n") { s ->
            "${s.name}\t${if (s.stale) "STALE" else "ready"}\tsteps=${s.steps.size}\t${s.instruction.take(60)}" +
                if (s.failStreak > 0) "\tfailStreak=${s.failStreak}" else ""
        }
        return ToolExecutionResult(body, true, toolTitle = toolTitle)
    }

    private fun match(obj: JSONObject, store: A11ySkillStore, toolTitle: String): ToolExecutionResult {
        val instruction = obj.optString("instruction").trim()
        if (instruction.isEmpty()) {
            return ToolExecutionResult("Error: instruction required", false,
                errorCode = ToolErrorCode.INVALID_ARGUMENTS, toolTitle = toolTitle)
        }
        val m = SkillMatcher.bestMatch(instruction, store.all())
            ?: return ToolExecutionResult("no skill matches \"$instruction\" (threshold=${SkillMatcher.MATCH_THRESHOLD})", true, toolTitle = toolTitle)
        return ToolExecutionResult(
            "match: ${m.skill.name} score=${"%.2f".format(m.score)}${if (m.skill.stale) " (STALE)" else ""}\n" +
                "instruction: ${m.skill.instruction}\nsteps: ${m.skill.steps.size}",
            true, toolTitle = toolTitle,
        )
    }

    private fun replay(obj: JSONObject, store: A11ySkillStore, toolTitle: String): ToolExecutionResult {
        val name = obj.optString("name").trim()
        val instruction = obj.optString("instruction").trim()
        val skill = when {
            name.isNotEmpty() -> store.byName(name)
                ?: return ToolExecutionResult("Error: no skill named \"$name\"", false,
                    errorCode = ToolErrorCode.NOT_FOUND, toolTitle = toolTitle)
            instruction.isNotEmpty() -> {
                val m = SkillMatcher.bestMatch(instruction, store.all())
                    ?: return ToolExecutionResult(
                        "no skill matches \"$instruction\" — record it first (Settings → Accessibility → 录制场景, then ui_skill save)",
                        false, errorCode = ToolErrorCode.NOT_FOUND, toolTitle = toolTitle)
                m.skill
            }
            else -> return ToolExecutionResult("Error: provide name or instruction", false,
                errorCode = ToolErrorCode.INVALID_ARGUMENTS, toolTitle = toolTitle)
        }
        if (skill.stale) {
            return ToolExecutionResult(
                "skill \"${skill.name}\" is stale (failed ${skill.failStreak}× in a row) — it does not replay; delete and re-record",
                false, errorCode = ToolErrorCode.STALE_OBSERVATION, toolTitle = toolTitle,
            )
        }
        val svc = MinisAccessibilityService.getInstance()
            ?: return ToolExecutionResult(
                "Error: accessibility service not running — enable Minis under Settings → Accessibility",
                false, errorCode = ToolErrorCode.PERMISSION_DENIED, toolTitle = toolTitle,
            )
        val engine = SkillReplayEngine(ServiceA11yActor(svc), store)
        val result = engine.replay(skill)
        return ToolExecutionResult(result.toString(), result.success,
            errorCode = if (result.success) null else ToolErrorCode.EXECUTION_FAILED,
            recoveryHint = if (result.success) null
            else "Replay failed at step ${result.stepIndex}. Read the dump excerpt, fix the flow manually with ui_read/ui_action, or re-record the skill.",
            toolTitle = toolTitle)
    }

    private fun save(obj: JSONObject, store: A11ySkillStore, toolTitle: String): ToolExecutionResult {
        val name = obj.optString("name").trim()
        val instruction = obj.optString("instruction").trim()
        if (name.isEmpty()) {
            return ToolExecutionResult("Error: name required", false,
                errorCode = ToolErrorCode.INVALID_ARGUMENTS, toolTitle = toolTitle)
        }
        val recorded = A11yScriptRecorder.steps.value
        if (recorded.isEmpty()) {
            return ToolExecutionResult(
                "Error: no recording in progress or recording is empty — start one first (Settings → Accessibility → 录制场景), perform the steps, then call ui_skill save",
                false, errorCode = ToolErrorCode.INVALID_ARGUMENTS,
                recoveryHint = "A11yScriptRecorder.start() → perform gestures → A11yScriptRecorder.stop() → ui_skill save.",
                toolTitle = toolTitle,
            )
        }
        val steps = A11ySkillStore.fromRecorderSteps(recorded)
        val pkg = recorded.firstNotNullOfOrNull { it.packageName }
        val saved = store.save(name, instruction.ifBlank { name }, steps, pkg)
            ?: return ToolExecutionResult("Error: save failed (disk write)", false,
                errorCode = ToolErrorCode.EXECUTION_FAILED, toolTitle = toolTitle)
        return ToolExecutionResult("saved \"${saved.name}\" (${steps.size} steps) — replay with ui_skill replay {\"name\":\"${saved.name}\"}", true, toolTitle = toolTitle)
    }

    private fun delete(obj: JSONObject, store: A11ySkillStore, toolTitle: String): ToolExecutionResult {
        val name = obj.optString("name").trim()
        if (name.isEmpty()) {
            return ToolExecutionResult("Error: name required", false,
                errorCode = ToolErrorCode.INVALID_ARGUMENTS, toolTitle = toolTitle)
        }
        val skill = store.byName(name)
            ?: return ToolExecutionResult("Error: no skill named \"$name\"", false,
                errorCode = ToolErrorCode.NOT_FOUND, toolTitle = toolTitle)
        return if (store.delete(skill.id)) ToolExecutionResult("deleted \"${skill.name}\"", true, toolTitle = toolTitle)
        else ToolExecutionResult("Error: delete failed", false, errorCode = ToolErrorCode.EXECUTION_FAILED, toolTitle = toolTitle)
    }
}
