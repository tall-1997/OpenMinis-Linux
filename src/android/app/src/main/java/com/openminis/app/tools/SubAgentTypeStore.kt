package com.openminis.app.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Configurable sub-agent types (name, prompt, skill/tool whitelist, kind).
 *
 * Adapted from XINCODE-Public SubAgentEntity + DefaultsSeeder (GPL-3.0-or-later).
 * Stored in SharedPreferences so we do not bump Room.
 */
data class SubAgentType(
    val name: String,
    val description: String,
    val systemPrompt: String,
    val skillNames: List<String>,
    val toolNames: List<String>,
    val kind: String,
    val builtin: Boolean,
)

object SubAgentTypeStore {
    private const val PREFS = "sub_agent_types"
    private const val KEY = "types_json"

    fun builtins(): List<SubAgentType> = listOf(
        SubAgentType(
            name = "探索者",
            description = "只读侦察：读文件、列目录、grep/glob",
            systemPrompt = "你是探索者。只读。先定位再报告。不要改文件，不要跑会改状态的命令。",
            skillNames = listOf("explore"),
            toolNames = listOf("file_read", "list_dir", "grep", "grep_source", "glob", "web_search", "code_graph"),
            kind = SubAgentKind.EXPLORE,
            builtin = true,
        ),
        SubAgentType(
            name = "审查员",
            description = "代码审查：只读 + 只读 shell",
            systemPrompt = "你是审查员。找缺陷、风险、测试缺口。给可执行的修改建议，但不要自己改。",
            skillNames = listOf("code-review", "systematic-debugging"),
            toolNames = listOf(
                "file_read", "list_dir", "grep", "grep_source", "glob",
                "shell_execute", "code_graph", "web_search",
            ),
            kind = SubAgentKind.PLAN,
            builtin = true,
        ),
        SubAgentType(
            name = "编码员",
            description = "实现改动：读写文件 + shell",
            systemPrompt = "你是编码员。按任务改代码、跑测试。只动任务范围内的文件。写完用 grep/测试自检。",
            skillNames = listOf("test-loop"),
            toolNames = listOf(
                "file_read", "file_write", "file_edit", "multi_edit",
                "list_dir", "grep", "grep_source", "glob", "shell_execute", "code_graph",
            ),
            kind = SubAgentKind.WORKER,
            builtin = true,
        ),
        SubAgentType(
            name = "研究员",
            description = "联网查资料，必须带出处",
            systemPrompt = "你是研究员。用 web_search / web_fetch。每条结论带 URL。不要编造来源。",
            skillNames = emptyList(),
            toolNames = listOf("web_search", "web_fetch", "file_read", "memory_get"),
            kind = SubAgentKind.EXPLORE,
            builtin = true,
        ),
    )

    fun load(context: Context): List<SubAgentType> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
        if (raw.isBlank()) return builtins()
        return try {
            val arr = JSONArray(raw)
            val custom = mutableListOf<SubAgentType>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                custom += SubAgentType(
                    name = o.getString("name"),
                    description = o.optString("description"),
                    systemPrompt = o.optString("systemPrompt"),
                    skillNames = o.optJSONArray("skillNames")?.toStringList() ?: emptyList(),
                    toolNames = o.optJSONArray("toolNames")?.toStringList() ?: emptyList(),
                    kind = o.optString("kind", SubAgentKind.WORKER),
                    builtin = o.optBoolean("builtin", false),
                )
            }
            val names = custom.map { it.name }.toSet()
            builtins().filter { it.name !in names } + custom
        } catch (_: Exception) {
            builtins()
        }
    }

    fun save(context: Context, types: List<SubAgentType>) {
        val arr = JSONArray()
        for (t in types) {
            arr.put(
                JSONObject()
                    .put("name", t.name)
                    .put("description", t.description)
                    .put("systemPrompt", t.systemPrompt)
                    .put("skillNames", JSONArray(t.skillNames))
                    .put("toolNames", JSONArray(t.toolNames))
                    .put("kind", t.kind)
                    .put("builtin", t.builtin),
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    fun find(context: Context, name: String): SubAgentType? {
        val n = name.trim()
        return load(context).firstOrNull { it.name.equals(n, ignoreCase = true) }
    }

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).mapNotNull { i -> optString(i).takeIf { it.isNotBlank() } }
}
