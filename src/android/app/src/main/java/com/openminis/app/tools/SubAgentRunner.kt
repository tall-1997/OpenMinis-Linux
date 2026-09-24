package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.LLMProvider
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/**
 * Nested agent loop used by [spawn_agent]. Sub-agents do not receive the
 * parent conversation and must not spawn further sub-agents.
 */
object SubAgentRunner {

    /** Absolute safety ceiling so a runaway loop cannot burn tokens forever. */
    const val ABSOLUTE_MAX_TURNS = 200

    /** Fraction of budget consumed at which a <budget_warning> is injected. */
    private const val WARN_FRACTION = 0.80

    /** Fraction at which the agent is ordered to stop calling tools and write up. */
    private const val FORCE_FRACTION = 0.95

    /** Cap on a single tool-result / report chunk kept in history (tail kept). */
    private const val MAX_REPORT_CHARS = 12_000

    suspend fun run(
        provider: LLMProvider,
        modelDisplayName: String,
        userPrompt: String,
        role: String?,
        skillsHint: String?,
        tools: List<AgentToolDefinition>,
        maxTokens: Int,
        executeTool: suspend (name: String, argsJson: String) -> ToolExecutionResult,
        onStep: suspend (turn: Int, toolName: String) -> Unit = { _, _ -> },
        kind: String = SubAgentKind.WORKER,
        writePaths: List<String> = emptyList(),
        maxTurns: Int = ABSOLUTE_MAX_TURNS,
        roleContext: Context? = null,
    ): ToolExecutionResult {
        val briefed = SubAgentBrief.wrap(userPrompt, kind = kind, role = role, writePaths = writePaths)
        val history = mutableListOf(
            LLMMessage(role = LLMMessage.Role.USER, content = briefed),
        )
        // Turn budget is whatever the coordinator assigned (auto-sized or
        // explicit), bounded only by ABSOLUTE_MAX_TURNS as a runaway guard —
        // there is no longer a low global settings clamp here.
        val turns = maxTurns.coerceIn(1, ABSOLUTE_MAX_TURNS)
        val system = workerSystemPrompt(modelDisplayName, role, skillsHint, kind, writePaths, turns, roleContext)
        val report = StringBuilder()
        val timeline = StringBuilder()
        var warned = false
        var forced = false

        try {
            var turn = 0
            while (turn < turns) {
                turn++
                runCatching { onStep(turn, "") }
                // Compact accumulated history before it can blow the context
                // window; the freshest tool results stay verbatim.
                val sendHistory = SubAgentHistoryCompactor.compact(history)
                val textSb = StringBuilder()
                val toolCalls = mutableListOf<Triple<String, String, JSONObject>>()
                provider.streamMessage(
                    messages = sendHistory,
                    systemPrompt = system,
                    maxTokens = maxTokens.coerceIn(256, 8192),
                    tools = tools,
                    thinkingLevel = ThinkingLevel.OFF,
                ).collect { chunk ->
                    when (chunk) {
                        is LLMStreamChunk.Text -> textSb.append(chunk.text)
                        is LLMStreamChunk.ToolCallComplete ->
                            toolCalls.add(Triple(chunk.id, chunk.name, chunk.args))
                        else -> Unit
                    }
                }

                val text = textSb.toString().trim()
                if (text.isNotEmpty()) {
                    if (report.isNotEmpty()) report.append("\n\n")
                    report.append(text)
                }

                if (toolCalls.isEmpty()) {
                    runCatching { onStep(turn, "done") }
                    return ToolExecutionResult(composeOutput(report.toString(), timeline.toString()), true)
                }

                val assistantParts = mutableListOf<AgentContentPart>()
                if (text.isNotEmpty()) assistantParts.add(AgentContentPart.Text(text))
                for ((id, name, args) in toolCalls) {
                    assistantParts.add(AgentContentPart.ToolUse(id, name, input = args))
                }
                history.add(
                    LLMMessage(
                        role = LLMMessage.Role.ASSISTANT,
                        content = text,
                        contentParts = assistantParts,
                    ),
                )

                val resultParts = mutableListOf<AgentContentPart>()
                for ((id, name, args) in toolCalls) {
                    if (SubAgentKind.isSpawnTool(name) || SubAgentKind.blocks(kind, name)) {
                        runCatching { onStep(turn, "$name · blocked") }
                        timeline.append("- turn $turn: $name (blocked)\n")
                        resultParts.add(
                            AgentContentPart.ToolResult(
                                id = id,
                                name = name,
                                content = "Error: sub-agents cannot spawn further sub-agents or use blocked tools. Complete the assigned work yourself.",
                                isError = true,
                            ),
                        )
                        continue
                    }
                    val argsJson = args.toString()
                    if (SubAgentKind.isReadOnly(kind) && name in setOf("shell_execute", "shell_exec", "env_exec")) {
                        val denied = SubAgentKind.readOnlyShellDenial(
                            runCatching { org.json.JSONObject(argsJson).optString("command") }.getOrDefault(""),
                        )
                        if (denied != null) {
                            timeline.append("- turn $turn: $name (read-only denied)\n")
                            resultParts.add(
                                AgentContentPart.ToolResult(id = id, name = name, content = denied, isError = true),
                            )
                            continue
                        }
                    }
                    val preview = previewToolArgs(argsJson)
                    runCatching {
                        onStep(turn, buildString {
                            append(name)
                            if (preview.isNotBlank()) append(" · ").append(preview)
                        })
                    }
                    val result = try {
                        executeTool(name, argsJson)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ToolExecutionResult("Error: ${e.message ?: e.javaClass.simpleName}", false)
                    }
                    runCatching {
                        onStep(turn, buildString {
                            append(if (result.success) "ok" else "fail")
                            append(' ').append(name)
                            val snippet = result.output.replace('\n', ' ').trim().take(80)
                            if (snippet.isNotEmpty()) append(" · ").append(snippet)
                        })
                    }
                    appendTimeline(timeline, turn, name, preview, result)
                    resultParts.add(
                        AgentContentPart.ToolResult(
                            id = id,
                            name = name,
                            content = result.output,
                            isError = !result.success,
                            imageData = result.imageData,
                            imageMimeType = result.imageMimeType,
                            imageLinuxPath = result.imageLinuxPath,
                        ),
                    )
                }
                history.add(
                    LLMMessage(
                        role = LLMMessage.Role.USER,
                        content = "",
                        contentParts = resultParts,
                    ),
                )

                // Turn-budget advisory so the agent wraps up instead of silently
                // hitting the cap. 80%: warning; 95%: order to stop tool calls
                // and hand in the partial result.
                val frac = turn.toDouble() / turns
                when {
                    frac >= FORCE_FRACTION && !forced -> {
                        forced = true
                        history.add(
                            LLMMessage(
                                role = LLMMessage.Role.USER,
                                content = "<budget_warning used=\"$turn/$turns\" force=\"true\">\n" +
                                    "You are at the final stretch of your turn budget. STOP calling tools NOW and return your findings so far as your final report in THIS turn — partial results are far more valuable than a perfect result you never submit. Lead with what you already confirmed, then list what is still unverified.\n" +
                                    "</budget_warning>",
                            ),
                        )
                    }
                    frac >= WARN_FRACTION && !warned -> {
                        warned = true
                        history.add(
                            LLMMessage(
                                role = LLMMessage.Role.USER,
                                content = "<budget_warning used=\"$turn/$turns\">\n" +
                                    "You have used $turn of your $turns turns (~${(frac * 100).toInt()}%). Prioritize finishing: avoid further broad searches, consolidate what you have, and prepare to submit your report.\n" +
                                    "</budget_warning>",
                            ),
                        )
                    }
                }
            }
            // Loop ended at the budget (95% force message, or a stubborn agent
            // kept calling tools to the last turn). Hand back the accumulated
            // partial report instead of a bare "hit the cap" string.
            val partial = report.toString().trim()
            val footer = if (partial.isNotEmpty()) {
                "(reached the $turns-turn budget — partial report above)"
            } else {
                "(sub-agent reached the $turns-turn budget with no findings to report)"
            }
            return ToolExecutionResult(composeOutput(partial, timeline.toString(), footer), true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ToolExecutionResult(
                composeOutput(
                    report.toString(),
                    timeline.toString(),
                    "Sub-agent failed: ${e.message ?: e.javaClass.simpleName}",
                ),
                false,
            )
        }
    }

    fun composeOutput(report: String, timeline: String, footer: String = ""): String {
        val body = report.trim()
        val trace = timeline.trim()
        val note = footer.trim()
        val composed = buildString {
            if (trace.isNotEmpty()) {
                append("## Trace\n")
                append(trace)
            }
            if (body.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("## Report\n")
                append(body)
            }
            if (note.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append(note)
            }
            if (isEmpty()) append("(sub-agent finished with empty output)")
        }
        return truncate(composed)
    }

    /**
     * Card and detail show the current result, not the step history.
     * The tool result returned to the parent still uses [composeOutput].
     */
    fun cardStep(output: String): String {
        val text = output.trim()
        val marker = "## Report"
        val at = text.indexOf(marker)
        if (at >= 0) {
            return text.substring(at + marker.length).trim().ifEmpty { text }
        }
        if (text.startsWith("## Trace")) return "已结束"
        return text
    }

    private fun appendTimeline(
        timeline: StringBuilder,
        turn: Int,
        name: String,
        preview: String,
        result: ToolExecutionResult,
    ) {
        timeline.append("- turn ").append(turn).append(": ").append(name)
        if (preview.isNotBlank()) {
            timeline.append(" `").append(preview.replace('`', '\'')).append("`")
        }
        timeline.append('\n')
        val snippet = result.output.replace('\n', ' ').trim().take(160)
        if (snippet.isNotEmpty()) {
            timeline.append("  ")
            if (!result.success) timeline.append("fail: ") else timeline.append("→ ")
            timeline.append(snippet)
            timeline.append('\n')
        }
    }

    fun previewToolArgs(argsJson: String): String {
        return try {
            val o = JSONObject(argsJson)
            val raw = when {
                o.has("command") -> o.optString("command")
                o.has("path") -> o.optString("path")
                o.has("query") -> o.optString("query")
                o.has("url") -> o.optString("url")
                o.has("pattern") -> o.optString("pattern")
                else -> argsJson
            }
            raw.replace('\n', ' ').trim().take(80)
        } catch (_: Exception) {
            argsJson.replace('\n', ' ').trim().take(80)
        }
    }

    private fun truncate(text: String): String {
        if (text.length <= MAX_REPORT_CHARS) return text
        return "…(truncated)\n" + text.takeLast(MAX_REPORT_CHARS)
    }

    private fun workerSystemPrompt(
        modelDisplayName: String,
        role: String?,
        skillsHint: String?,
        kind: String,
        writePaths: List<String>,
        turns: Int,
        roleContext: Context? = null,
    ): String {
        val catalog = roleContext?.let { CollabRoles.byName(it, role) } ?: CollabRoles.byName(role)
        val roleLine = when {
            catalog != null -> "Assigned role: ${catalog.name}.\n\n${catalog.prompt}\n\n"
            !role.isNullOrBlank() -> "Assigned role: ${role.trim()}.\n"
            else -> ""
        }
        val skillsLine = skillsHint?.trim()?.takeIf { it.isNotEmpty() }?.let {
            "Read these skills first (file_read `/var/minis/skills/<id>/SKILL.md`): $it\n"
        } ?: ""
        val kindLine = "Kind: $kind.\n"
        val writeLine = if (writePaths.isNotEmpty()) {
            "You may only file_write/file_edit under: ${writePaths.joinToString()}. " +
                "Keep shell writes in those prefixes; MINIS_WRITE_PATHS is exported.\n"
        } else {
            ""
        }
        val toolLine = if (SubAgentKind.isReadOnly(kind)) {
            "- Read-only: file_read, grep_source, and shell_execute for inspection only (date, uname, cat, ls). Do not write files or redirect output into a file."
        } else {
            "- Use tools immediately. Prefer file_read / grep_source / file_edit / file_write / shell_execute."
        }
        return """You are a sub-agent ($kind), not the session coordinator. Model: $modelDisplayName.
${roleLine}${skillsLine}${kindLine}${writeLine}You cannot see the parent conversation. The user prompt is a self-contained brief with ## Task / ## Expected result / ## Constraints / ## Workflow / ## Collaboration.

Turn budget: you have $turns turns. A <budget_warning> will be injected as you approach the limit — treat it as a hard signal to wrap up. Handing in a partial report is far better than running dry mid-task; if forced to stop, lead with confirmed findings, then list unverified leftovers.

Rules:
- Complete ONLY the assigned slice. Do not rewrite unrelated files.
- Do not spawn further sub-agents. spawn_agent / run_subagent are not available and will error if you try.
- Searching/reading source: prefer grep_source (one call = matching lines ± context) over paging file_read through a big file. Old tool outputs are auto-trimmed from your context, so re-read a file if you need it back.
$toolLine
- Follow the brief's Workflow, then return a concise report: what changed, files touched, leftover risks, and whether Expected result passed.
- If you cannot meet the acceptance criteria, say so explicitly and list what failed.
"""
    }
}
