package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import org.json.JSONArray
import org.json.JSONObject

/**
 * Look up a named sub-agent type, inject prompt + tool/skill whitelist, then
 * spawn via the existing SubAgentRunner (independent loop, not nested AgentCore).
 *
 * Adapted from XINCODE-Public SubAgentTool (GPL-3.0-or-later).
 */
object DispatchAgentsTool {
    const val NAME = "dispatch_agents"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Dispatch one or more named sub-agent presets. Built-in presets: " +
            "代码审计 (read-only audit), 方案规划 (read-only design), 调研 (web research " +
            "with citations), 复现 (read-only reproduction), 提炼 (distill conclusions), " +
            "plus 探索者/审查员/编码员/研究员 and user-defined types. " +
            "Each type carries its own kind, skills, tool whitelist and tool scope " +
            "(read-only by default — a preset without an explicit whitelist can only read). " +
            "Nested dispatch is forbidden. Prefer this when you know which specialist to " +
            "send; use spawn_agent for ad-hoc kinds.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user."),
            "assignments" to AgentToolParam(
                type = "array",
                description = "List of {agent, task} or {name, prompt}.",
                items = AgentToolParam(
                    type = "object",
                    description = "One assignment",
                    properties = mapOf(
                        "agent" to AgentToolParam("string", "Type name, e.g. 探索者"),
                        "name" to AgentToolParam("string", "Alias of agent"),
                        "task" to AgentToolParam("string", "What this agent should do"),
                        "prompt" to AgentToolParam("string", "Alias of task"),
                    ),
                ),
            ),
        ),
        required = listOf("tool_title", "assignments"),
        propertyOrdering = listOf("tool_title", "assignments"),
    )

    /** Rewrite into spawn_agent tasks JSON so ChatViewModel.executeRunSubAgent can run it. */
    fun toSpawnArgs(argsJson: String, context: Context): String {
        val src = JSONObject(argsJson)
        val assignments = src.optJSONArray("assignments") ?: JSONArray()
        val tasks = JSONArray()
        for (i in 0 until assignments.length()) {
            val a = assignments.optJSONObject(i) ?: continue
            val agentName = a.optString("agent").ifBlank { a.optString("name") }
            val task = a.optString("task").ifBlank { a.optString("prompt") }
            // A lane with no brief burns a full sub-agent turn budget and then
            // reports nothing. Drop it instead of spawning an empty agent.
            if (task.isBlank()) continue
            val type = SubAgentTypeStore.find(context, agentName)
            val kind = type?.kind ?: SubAgentKind.WORKER
            val prompt = buildString {
                if (type != null) {
                    append(type.systemPrompt.trim())
                    append("\n\n")
                }
                append(task)
            }
            val obj = JSONObject()
                .put("prompt", prompt)
                .put("kind", kind)
                .put("role", agentName)
            if (type != null && type.skillNames.isNotEmpty()) {
                // [T-dispatch-skills-contract] `skills` is DECLARED as a string
                // ("Comma-separated skill ids", AgentTools spawn_agent schema) and
                // the consumer reads it with `args.optString("skills")`. Emitting a
                // JSONArray made the value arrive as the literal text
                // `["a","b"]` inside the worker's system prompt — brackets and
                // quotes and all. Join it into the contracted form.
                obj.put("skills", type.skillNames.joinToString(","))
            }
            tasks.put(obj)
        }
        return JSONObject()
            .put("tool_title", src.optString("tool_title", NAME))
            .put("tasks", tasks)
            .toString()
    }

    fun filterToolsForType(type: SubAgentType?, tools: List<AgentToolDefinition>): List<AgentToolDefinition> {
        if (type == null) return SubAgentKind.dedupeByName(tools)
        val scopeReadOnly = type.toolScope != SCOPE_WRITE
        // [GH#530] 未指定工具白名单 = 只读预设的默认范围，而不是“全部工具”。
        // 这堵住了“新建一个没写 tools 的预设 → 继承主会话全部能力”的默认放大。
        val allow = if (type.toolNames.isEmpty()) {
            if (scopeReadOnly) READ_ONLY_DEFAULT_TOOLS else return SubAgentKind.dedupeByName(tools)
        } else {
            type.toolNames.toSet()
        }
        var filtered = tools.filter { it.name in allow }
        if (scopeReadOnly) {
            // 白名单写漏一个写工具也不能真的放行：scope 是硬约束。
            filtered = filtered.filter { it.name !in WRITE_TOOLS }
        }
        return SubAgentKind.dedupeByName(filtered)
    }

    /**
     * [GH#530] 预设的只读 scope 覆盖请求里的 kind：调用方写 kind=worker 但挑了一个
     * 只读预设时，按 [SubAgentKind.PLAN] 执行（工具白名单 + shell 只读检查一起
     * 收紧），而不是让白名单外的东西先跑起来再被 executeTool 拒绝。
     */
    fun effectiveKind(type: SubAgentType?, requestedKind: String): String {
        val requested = SubAgentKind.normalize(requestedKind)
        if (type == null) return requested
        if (type.toolScope == SCOPE_WRITE) return requested
        return if (SubAgentKind.isReadOnly(requested)) requested else SubAgentKind.PLAN
    }

    /**
     * [GH#530] 只读预设没有显式白名单时的默认工具集：读 + 检索 + 只读 shell。
     * 与 [SubAgentKind] 的 READ_ONLY_ALLOW 保持一致，但这里只列无副作用的工具。
     */
    private val READ_ONLY_DEFAULT_TOOLS = setOf(
        "file_read", "list_dir", "grep", "grep_source", "glob",
        "web_search", "web_fetch", "search_sessions", "read_session",
        "memory_get", "read_image", "browser_use", "code_graph",
        "shell_execute",
    )

    /** [GH#530] 写类工具（只读 scope 下强制剔除）。 */
    private val WRITE_TOOLS = setOf(
        "file_write", "file_edit", "multi_edit", "file_checkpoint",
        "memory_write", "image_generate", "video_generate",
    )

    /** [GH#530] 执行期 scope 校验用的写工具集合（只读预设不得调用）。 */
    fun writeToolNames(): Set<String> = WRITE_TOOLS
}
