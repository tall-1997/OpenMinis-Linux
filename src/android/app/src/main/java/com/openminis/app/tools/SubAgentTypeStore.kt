package com.openminis.app.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 子代理“预设定义”：名称、提示词、技能/工具白名单、kind 与工具范围。
 *
 * [GH#530] 在原有 name/prompt/whitelist 之上补了两件事：
 *
 *  1. [toolScope]（[SCOPE_READ_ONLY] / [SCOPE_WRITE]）——工具白名单之外的第二
 *     道约束。只读预设即使白名单写漏了 `file_write`，派发时也会被强制降为只读
 *     kind，并由 [DispatchAgentsTool.filterToolsForType] 再剔除写工具。
 *  2. 五个内置预设（代码审计 / 方案规划 / 调研 / 复现 / 提炼），对齐
 *     [GH#530] 的“内置 5 个预设 + 工具范围”。
 *
 * 兼容：老的自定义 JSON 没有 `toolScope` 字段，读取时按 [kind] 推导——老数据
 * 里 `编码员`（worker）仍然是可写，不会因为升级突然失去写权限。新建类型若既
 * 没写 scope 也没写 kind，则落到只读（“默认只读若未指定”）。
 *
 * 存储仍是 SharedPreferences（不碰 Room schema）；本批不做子代理类型编辑 UI，
 * 预设通过 `dispatch_agents` / `spawn_agent(role=…)` 使用。
 */
data class SubAgentType(
    val name: String,
    val description: String,
    val systemPrompt: String,
    val skillNames: List<String>,
    val toolNames: List<String>,
    val kind: String,
    /** [GH#530] [SCOPE_READ_ONLY] 或 [SCOPE_WRITE]。 */
    val toolScope: String = SCOPE_READ_ONLY,
    val builtin: Boolean,
)

/** [GH#530] 工具范围常量：不是枚举，避免 JSON 往返时的 enum-value 兼容问题。 */
const val SCOPE_READ_ONLY = "read_only"
const val SCOPE_WRITE = "write"

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
            toolScope = SCOPE_READ_ONLY,
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
            toolScope = SCOPE_READ_ONLY,
            builtin = true,
        ),
        SubAgentType(
            name = "编码员",
            description = "实现改动：读写文件 + shell",
            systemPrompt = "你是编码员。按任务改代码、跑测试。只动任务范围内的文件。写完用 grep/测试自检。",
            skillNames = listOf("test-loop"),
            toolNames = listOf(
                "file_read", "file_write", "file_edit", "multi_edit", "file_checkpoint",
                "list_dir", "grep", "grep_source", "glob", "shell_execute", "code_graph",
            ),
            kind = SubAgentKind.WORKER,
            toolScope = SCOPE_WRITE,
            builtin = true,
        ),
        SubAgentType(
            name = "研究员",
            description = "联网查资料，必须带出处",
            systemPrompt = "你是研究员。用 web_search / web_fetch。每条结论带 URL。不要编造来源。",
            skillNames = emptyList(),
            toolNames = listOf("web_search", "web_fetch", "file_read", "memory_get"),
            kind = SubAgentKind.EXPLORE,
            toolScope = SCOPE_READ_ONLY,
            builtin = true,
        ),
        // ── [GH#530] 五个高价值预设 ─────────────────────────────────────
        SubAgentType(
            name = "代码审计",
            description = "只读审计：缺陷 / 安全 / 测试缺口，产出可执行清单",
            systemPrompt = """
你是代码审计员。只读：不改文件、不跑会改状态的命令（可用只读 shell 查看 git log / grep）。

审计维度，按严重度排序：
1. 正确性：空值、边界、并发、错误处理是否吞错。
2. 安全：注入、路径穿越、凭据泄漏、权限门是否可绕过。
3. 资源：泄漏、无界缓存、OOM / 超时风险。
4. 测试：关键路径是否缺测试，现有测试是否真的能失败。

输出：每条发现给「文件:行 — 问题 — 触发条件 — 建议修法」，并标注 P0/P1/P2。不要改代码。
""".trimIndent(),
            skillNames = listOf("code-review", "systematic-debugging"),
            toolNames = listOf(
                "file_read", "list_dir", "grep", "grep_source", "glob",
                "shell_execute", "code_graph", "web_search",
            ),
            kind = SubAgentKind.PLAN,
            toolScope = SCOPE_READ_ONLY,
            builtin = true,
        ),
        SubAgentType(
            name = "方案规划",
            description = "只读设计：拆解目标、比较方案、给出落地步骤与风险",
            systemPrompt = """
你是方案规划师。只读：先读代码/文档，再给方案。不要改任何文件。

输出结构：
1. 现状（关键文件与调用链，带路径）。
2. 可选方案 2–3 个，各自代价/收益/风险。
3. 推荐方案与分步落地（每步可独立验证）。
4. 未决问题（需要用户拍板的点）。

不确定的地方明确写“需要确认”，不要编造 API/文件。
""".trimIndent(),
            skillNames = listOf("plan"),
            toolNames = listOf(
                "file_read", "list_dir", "grep", "grep_source", "glob",
                "code_graph", "search_sessions", "read_session", "memory_get",
            ),
            kind = SubAgentKind.PLAN,
            toolScope = SCOPE_READ_ONLY,
            builtin = true,
        ),
        SubAgentType(
            name = "调研",
            description = "联网调研：多源检索、交叉验证、结论带出处",
            systemPrompt = """
你是调研员。用 web_search / web_fetch 收集信息，必要时读本地文件补上下文。

规则：
- 每条事实带 URL；同一结论尽量两个独立来源。
- 区分「官方文档」与「博客/论坛」；冲突时说明分歧。
- 明确写出检索日期与查询词。
- 找不到可靠来源就写“未找到可靠来源”，不要编造。
""".trimIndent(),
            skillNames = emptyList(),
            toolNames = listOf(
                "web_search", "web_fetch", "browser_use",
                "file_read", "memory_get", "search_sessions", "read_session",
            ),
            kind = SubAgentKind.EXPLORE,
            toolScope = SCOPE_READ_ONLY,
            builtin = true,
        ),
        SubAgentType(
            name = "复现",
            description = "只读复现：最小步骤重现问题，收集证据但不修",
            systemPrompt = """
你是复现工程师。目标：用最小步骤稳定复现问题并收集证据。只读——不要改文件（临时输出写到 /tmp）。

步骤：
1. 读相关代码与日志，列出复现所需前提。
2. 逐步执行命令，记录完整命令、退出码、关键输出。
3. 给出最小复现步骤与失败证据（日志片段 / 行号）。
4. 说明未能复现时的下一步排查方向。

不要“顺手修一下”；修复是 worker 的职责。
""".trimIndent(),
            skillNames = listOf("systematic-debugging"),
            toolNames = listOf(
                "file_read", "list_dir", "grep", "grep_source", "glob",
                "shell_execute", "code_graph",
            ),
            kind = SubAgentKind.EXPLORE,
            toolScope = SCOPE_READ_ONLY,
            builtin = true,
        ),
        SubAgentType(
            name = "提炼",
            description = "只读提炼：从长材料/会话里抽结论、决策与待办",
            systemPrompt = """
你是提炼员。把长材料压缩成可执行的结论。只读。

输出：
1. 一句话结论。
2. 关键事实/决策（带来源路径或会话 id）。
3. 未决问题与建议下一步。
4. 如果材料互相矛盾，明确指出矛盾点。

不要发明材料里没有的结论；引用尽量给原文短句。
""".trimIndent(),
            skillNames = emptyList(),
            toolNames = listOf(
                "file_read", "grep", "grep_source", "glob", "list_dir",
                "memory_get", "search_sessions", "read_session",
            ),
            kind = SubAgentKind.PLAN,
            toolScope = SCOPE_READ_ONLY,
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
                val kind = o.optString("kind", SubAgentKind.PLAN)
                custom += SubAgentType(
                    name = o.getString("name"),
                    description = o.optString("description"),
                    systemPrompt = o.optString("systemPrompt"),
                    skillNames = o.optJSONArray("skillNames")?.toStringList() ?: emptyList(),
                    toolNames = o.optJSONArray("toolNames")?.toStringList() ?: emptyList(),
                    kind = kind,
                    // [GH#530] Legacy rows have no toolScope: derive it from the
                    // kind so an existing 编码员 (worker) does not silently become
                    // read-only after the upgrade.
                    toolScope = o.optString("toolScope", legacyScopeFor(kind)),
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
                    .put("toolScope", t.toolScope)
                    .put("builtin", t.builtin),
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    fun find(context: Context, name: String): SubAgentType? {
        val n = name.trim()
        if (n.isEmpty()) return null
        return load(context).firstOrNull { it.name.equals(n, ignoreCase = true) }
    }

    /** 预设名列表，供工具描述展示。 */
    fun namesCsv(): String = builtins().joinToString(" / ") { it.name }

    /**
     * [GH#530] 老数据没有 toolScope 时的推导：worker / general-purpose 视为可写，
     * 其余一律只读（默认只读）。
     */
    internal fun legacyScopeFor(kind: String): String =
        if (SubAgentKind.canWrite(kind)) SCOPE_WRITE else SCOPE_READ_ONLY

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).mapNotNull { i -> optString(i).takeIf { it.isNotBlank() } }
}
