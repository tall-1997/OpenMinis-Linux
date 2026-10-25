package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.evolution.EvolutionHooks
import com.openminis.app.evolution.EvolutionProposal
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Agent-facing surface for the self-evolution engine.
 *
 * WHY THIS EXISTS. Evolution shipped with a full engine (harvest, beliefs,
 * proposals, rollback), hooks wired into the chat loop, and a Settings screen
 * a human could use — but no tool. So the feature was reachable only by
 * tapping through Settings: the agent that actually produced the evidence
 * could neither see what had been proposed about its own behaviour nor accept,
 * reject, or inspect it. Every other self-modifying subsystem here has an
 * agent tool (memory_write/memory_get, skill_manage); this closes that gap.
 *
 * Scope is deliberately read-mostly: the engine owns the write paths, and
 * accept/rollback are exactly the operations the Settings screen performs, so
 * the agent can do nothing a user could not already do by hand.
 */
object EvolutionTool {
    const val NAME = "evolution"

    private val ACTIONS = listOf(
        "status", "list", "show", "accept", "reject", "defer", "rollback", "harvest",
    )

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Inspect and act on the self-evolution engine (learned rules, " +
            "beliefs, skill patches). action=status|list|show|accept|reject|defer|rollback|harvest. " +
            "status summarizes what is pending; list shows proposals; show prints one in full; " +
            "accept/reject/defer/rollback decide one by id; harvest triggers an idle pass. " +
            "This is the memory-side self-update tool: call it when the user asks you to remember a behavior change, or after memory_get scope=evolution. Disabled until Settings → Memory → Evolution is on.",
        parameters = mapOf(
            "tool_title" to AgentToolParam(
                "string",
                "A concise 5-10 word summary shown to the user. Use the same language as the user.",
            ),
            "action" to AgentToolParam(
                "string",
                "status, list, show, accept, reject, defer, rollback, or harvest.",
                enumValues = ACTIONS,
            ),
            "id" to AgentToolParam(
                "string",
                "Proposal id, required for show/accept/reject/defer/rollback.",
            ),
        ),
        required = listOf("tool_title", "action"),
        propertyOrdering = listOf("tool_title", "action", "id"),
    )

    suspend fun execute(argsJson: String, engine: com.openminis.app.evolution.EvolutionEngine?): ToolExecutionResult {
        val args = try {
            JSONObject(argsJson)
        } catch (e: Exception) {
            return ToolExecutionResult("Error: invalid arguments: ${e.message}", false)
        }
        val toolTitle = args.optString("tool_title", NAME)
        val action = args.optString("action", "").trim().lowercase()

        if (engine == null) {
            return ToolExecutionResult(
                "Evolution engine is not available in this process.",
                false,
                toolTitle = toolTitle,
            )
        }
        val prefs = engine.prefs
        val store = engine.store

        // `status` is the one action that must work while evolution is off —
        // otherwise the agent cannot tell "nothing proposed" from "switched off".
        if (action == "status") {
            val proposals = store.proposals.value
            val byStatus = EvolutionProposal.Status.entries.joinToString(", ") { s ->
                "${s.raw}=${proposals.count { it.status == s }}"
            }
            val body = buildString {
                append("enabled: ${prefs.isEnabled}\n")
                append("daily LLM cap: ${prefs.dailyCap()}\n")
                append("llm fused (3 consecutive failures): ${prefs.llmFused()}\n")
                append("harvest cooldown elapsed: ${prefs.harvestCooldownElapsed()}\n")
                append("weekly reflection cooldown elapsed: ${prefs.reflectCooldownElapsed()}\n")
                append("proposals (${proposals.size}): $byStatus\n")
                append("beliefs: ${store.listBeliefs().size}")
            }
            return ToolExecutionResult(body, true, toolTitle = toolTitle)
        }

        if (!prefs.isEnabled) {
            return ToolExecutionResult(
                "Evolution is off. Ask the user to enable it under Settings → Memory → Evolution.",
                false,
                toolTitle = toolTitle,
            )
        }

        when (action) {
            "list" -> {
                val open = store.proposals.value.filter {
                    it.status == EvolutionProposal.Status.PENDING ||
                        it.status == EvolutionProposal.Status.DEFERRED
                }
                if (open.isEmpty()) {
                    return ToolExecutionResult("No pending evolution proposals.", true, toolTitle = toolTitle)
                }
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                val body = open.joinToString("\n") { p ->
                    "${p.id}\t${p.type.raw}\t${p.status.raw}\t${p.scene}\t" +
                        "${stamp.format(Date(p.createdAt))}\t${p.title.take(120)}"
                }
                return ToolExecutionResult(body, true, toolTitle = toolTitle)
            }

            "show" -> {
                val id = args.optString("id").trim()
                if (id.isEmpty()) return ToolExecutionResult("Error: id required for show", false, toolTitle = toolTitle)
                val p = store.getProposal(id)
                    ?: return ToolExecutionResult("No proposal with id '$id'.", false, toolTitle = toolTitle)
                val body = buildString {
                    append("# ${p.title}\n")
                    append("id: ${p.id}\n")
                    append("type: ${p.type.raw}    status: ${p.status.raw}    scene: ${p.scene}\n")
                    append("created: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(p.createdAt))}\n")
                    if (p.skillId != null) append("skill: ${p.skillId}\n")
                    append("\n## Draft\n").append(p.draftText.ifBlank { "(empty)" }).append('\n')
                    if (p.evidence.isNotBlank()) append("\n## Evidence\n").append(p.evidence).append('\n')
                    if (!p.rollbackText.isNullOrBlank()) append("\n## Rollback\n").append(p.rollbackText).append('\n')
                }
                return ToolExecutionResult(body, true, toolTitle = toolTitle)
            }

            "accept", "reject", "defer", "rollback" -> {
                val id = args.optString("id").trim()
                if (id.isEmpty()) {
                    return ToolExecutionResult("Error: id required for $action", false, toolTitle = toolTitle)
                }
                if (store.getProposal(id) == null) {
                    return ToolExecutionResult("No proposal with id '$id'.", false, toolTitle = toolTitle)
                }
                return when (action) {
                    // accept() returns false when the proposal is not in a state
                    // that can be accepted (already decided), which is worth
                    // reporting as a failure rather than a silent no-op.
                    "accept" -> {
                        val ok = engine.accept(id)
                        ToolExecutionResult(
                            if (ok) "Accepted $id." else "Could not accept $id (already decided, or rollback failed).",
                            ok,
                            toolTitle = toolTitle,
                        )
                    }
                    "reject" -> {
                        engine.reject(id)
                        ToolExecutionResult("Rejected $id.", true, toolTitle = toolTitle)
                    }
                    "defer" -> {
                        engine.defer(id)
                        ToolExecutionResult("Deferred $id.", true, toolTitle = toolTitle)
                    }
                    else -> {
                        val ok = engine.rollback(id)
                        ToolExecutionResult(
                            if (ok) "Rolled back $id." else "Could not roll back $id (not accepted, or nothing to undo).",
                            ok,
                            toolTitle = toolTitle,
                        )
                    }
                }
            }

            "harvest" -> {
                // Fire-and-forget by design: the idle pass takes the engine
                // mutex and may call the model, so awaiting it here would stall
                // the agent's turn. Report the gates up front so the reply is
                // honest about whether anything can happen yet.
                val gated = buildList {
                    if (!prefs.harvestCooldownElapsed()) add("harvest cooldown has not elapsed")
                    if (!prefs.reflectCooldownElapsed()) add("weekly reflection is not due")
                }
                EvolutionHooks.maybeHarvestIdle()
                val body = if (gated.isEmpty()) {
                    "Idle harvest triggered; it runs in the background and only when the device is charging."
                } else {
                    "Idle harvest asked for, but it will skip work right now: ${gated.joinToString("; ")}. " +
                        "It also requires the device to be charging."
                }
                return ToolExecutionResult(body, true, toolTitle = toolTitle)
            }

            else -> return ToolExecutionResult(
                "Error: unknown action '$action'. Expected one of: ${ACTIONS.joinToString(", ")}.",
                false,
                toolTitle = toolTitle,
            )
        }
    }
}
