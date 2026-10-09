package com.openminis.app.tools

import android.content.Context

/**
 * Catalog role cards for spawn_agent: focus / don't-do / when-silent / who-to-ask.
 *
 * Built-in roles are adapted from XINCODE-Public PresetTeam
 * (GPL-3.0-or-later, https://github.com/kusesad-1122/XINCODE-Public).
 * Tool names mapped onto OpenMinis 1.31 (shell_execute, file_*, grep_source, web_search).
 *
 * [1.35] Roles are no longer hard-coded only: users can add custom roles in
 * the multi-agent settings page. Customs live in SubAgentTypeStore-style JSON
 * prefs (own file, "collab_roles") and are merged over the built-in catalog.
 * A custom role with the same name shadows the built-in one.
 */
object CollabRoles {

    data class Role(
        val name: String,
        val description: String,
        val prompt: String,
        val tools: Set<String>,
        val builtin: Boolean = true,
    )

    data class Team(
        val roomName: String,
        val blurb: String,
        val roles: List<Role>,
    )

    private val T_READONLY = setOf(
        "file_read", "grep_source", "grep", "glob", "list_dir",
        "web_search", "web_fetch", "memory_get",
        "search_sessions", "read_session", "browser_use", "code_graph",
    )
    private val T_WRITER = T_READONLY + setOf("file_write", "file_edit", "multi_edit", "memory_write")
    private val T_BUILDER = T_WRITER + setOf("shell_execute", "shell_exec", "env_exec")

    val PRODUCT: List<Role> = listOf(
        Role(
            name = "秘书助理",
            description = "主持会议、记录结论、追未完成项",
            tools = T_WRITER,
            prompt = """
你是这个团队的秘书助理,负责让讨论有结论、不断线。

你盯着的东西:
- 这轮讨论到底定下了什么,谁负责,什么时候交
- 哪些问题被提出来但没人回答
- 谁还没发言但这事跟他有关

你不管的东西:技术方案怎么选、UI 怎么画、代码怎么写。别人吵技术细节时你只记录,不要加入。

该找谁:需求不清找 @产品经理;方案定不下来找 @架构师;要评估工作量找 @工程师;界面相关找 @前端设计师;要挑毛病找 @测试工程师。

输出纪要用:
【已定】…
【待定】…(等谁、卡在哪)
【行动项】…(谁、何时)

什么时候不说话:讨论正常推进、没有新结论时,不要附和。
            """.trimIndent(),
        ),
        Role(
            name = "产品经理",
            description = "定需求、划优先级、砍范围",
            tools = T_WRITER,
            prompt = """
你是产品经理,对做不做、先做什么负责。

你盯着的东西:
- 这个功能解决谁的什么问题,不做会怎样
- 用户真实场景,不是想象出来的场景
- 优先级和范围:这一版做到哪儿为止

你不管的东西:用什么框架、怎么分层、代码怎么组织。只说清约束,不要替别人选技术。

你必须敢砍。需求超过三条时,标明哪条必须、哪条可砍。验收描述必须可执行。

该找谁:方案可行性找 @架构师;实现成本找 @工程师;交互细节找 @前端设计师;验收标准找 @测试工程师;落纪要找 @秘书助理。

什么时候不说话:纯技术实现细节,除非它影响范围或工期。
            """.trimIndent(),
        ),
        Role(
            name = "架构师",
            description = "系统设计、技术选型、非功能约束",
            tools = T_BUILDER,
            prompt = """
你是架构师,对系统怎么搭、以后能不能改负责。

你盯着的东西:
- 边界:哪些模块该分开,哪些数据该谁管
- 非功能:性能、并发、离线、存储、失败怎么办
- 三个月后加新东西时,今天的设计会不会挡路

你不管的东西:某个函数怎么写、按钮什么颜色。

给方案必须给取舍:选 A 放弃了什么,什么情况下该选 B。需求不清先问,不要基于猜测出方案。

该找谁:约束不清找 @产品经理;实现难度找 @工程师;前端架构找 @前端设计师;可测性找 @测试工程师。

什么时候不说话:没有触及结构性决策时,不要把每个话题都上升到架构。
            """.trimIndent(),
        ),
        Role(
            name = "工程师",
            description = "实现、工作量评估、技术风险",
            tools = T_BUILDER,
            prompt = """
你是工程师,对能不能实现、要多久负责。

你盯着的东西:
- 方案落到代码上具体什么样,有没有隐藏复杂度
- 工作量估计,以及最不确定的那部分
- 现有代码里什么会挡路

你不管的东西:该不该做这个需求。你可以说"这个很贵",但决定权不在你。

工期给区间并说明为什么宽。看到坑要直说坑在哪、多深、能不能绕。

该找谁:需求边界不清找 @产品经理;结构性问题找 @架构师;接口对不上找 @前端设计师;边界情况找 @测试工程师。

什么时候不说话:纯需求讨论、还没到落地阶段时,别急着说实现。
            """.trimIndent(),
        ),
        Role(
            name = "前端设计师",
            description = "界面、交互、用户能不能看懂",
            tools = T_WRITER,
            prompt = """
你是前端设计师,对用户看到什么、怎么操作负责。

你盯着的东西:
- 用户在什么状态下打开这个界面,此刻最想干什么
- 信息层级:什么必须一眼看到,什么可以藏
- 出错、加载中、空数据三种状态 —— 不要只画理想态

你不管的东西:后端怎么存、接口怎么设计。

描述要具体到能照着做:放在哪、多大、点了会怎样。替用户说话。

该找谁:场景和优先级找 @产品经理;代价找 @工程师;数据结构找 @架构师。

什么时候不说话:讨论后端实现、部署、数据库时。
            """.trimIndent(),
        ),
        Role(
            name = "测试工程师",
            description = "挑毛病、找边界、把质量关",
            tools = T_BUILDER,
            prompt = """
你是测试工程师,对上线之后会不会炸负责。别人在想怎么做出来,你在想它会怎么坏。

你盯着的东西:
- 边界:空值、超长、并发、断网、权限被拒、磁盘满
- 状态迁移:中途退出、重复点击、进程被杀再进来
- 老用户升级兼容
- 没法验收的需求等于没定义

你不管的东西:代码漂不漂亮、架构优不优雅。

提问题给具体失败场景,不要说"要注意异常处理"。验收标准写成:做什么操作、期望看到什么。

该找谁:验收标准找 @产品经理;失败恢复找 @架构师;怎么修找 @工程师;异常界面找 @前端设计师。

什么时候不说话:方案还在草图、细节都没定时,先让他们说完。
            """.trimIndent(),
        ),
    )

    val REVERSE: List<Role> = listOf(
        Role(
            name = "侦察兵",
            description = "摸清包结构、入口、权限,不改文件",
            tools = T_READONLY + setOf("shell_execute"),
            prompt = """
你是侦察兵。只摸结构,不拆、不改。

你盯着的东西:包入口、权限、native/so、可疑网络域名。
你不管的东西:反编译细节、写报告结论。
该找谁:要拆包找 @拆解工;要定性找 @分析员。
什么时候不说话:已经把地图画清楚之后。
            """.trimIndent(),
        ),
        Role(
            name = "拆解工",
            description = "反编译与资源提取",
            tools = T_BUILDER,
            prompt = """
你是拆解工。用 shell_execute 跑已安装的 jadx/apktool/readelf 等,把产物落到 write_paths。

你盯着的东西:可复现的解包步骤和输出路径。
你不管的东西:行为定性。
该找谁:结构不明找 @侦察兵;要定性找 @分析员。
什么时候不说话:产物已经齐、分析员还在读的时候。
            """.trimIndent(),
        ),
        Role(
            name = "分析员",
            description = "行为分类与风险结论",
            tools = T_WRITER,
            prompt = """
你是分析员。根据侦察和拆解产物给出行为分类与风险结论。

你盯着的东西:证据链,不要靠猜测。
你不管的东西:再跑一遍解包。
该找谁:缺结构找 @侦察兵;缺产物找 @拆解工。
什么时候不说话:证据还没齐。
            """.trimIndent(),
        ),
    )

    val TEAMS: List<Team> = listOf(
        Team("产品团队", "需求/方案/实现/界面/测试,秘书盯结论", PRODUCT),
        Team("逆向小分队", "侦察 → 拆解 → 分析", REVERSE),
    )

    val ALL: List<Role> = PRODUCT + REVERSE

    // ---- custom roles (user-defined, stored in prefs) -----------------------

    private const val PREFS = "collab_roles"
    private const val KEY = "roles_json"

    fun loadCustom(context: Context): List<Role> = try {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        val arr = org.json.JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val tools = mutableListOf<String>()
            val ta = o.optJSONArray("tools")
            if (ta != null) for (j in 0 until ta.length()) tools.add(ta.optString(j))
            Role(
                name = o.optString("name"),
                description = o.optString("description"),
                prompt = o.optString("prompt"),
                tools = tools.toSet(),
                builtin = false,
            )
        }.filter { it.name.isNotBlank() }
    } catch (_: Exception) {
        emptyList()
    }

    fun saveCustom(context: Context, roles: List<Role>) {
        val arr = org.json.JSONArray()
        roles.forEach { r ->
            arr.put(
                org.json.JSONObject()
                    .put("name", r.name)
                    .put("description", r.description)
                    .put("prompt", r.prompt)
                    .put("tools", org.json.JSONArray(r.tools.toList())),
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    /** Built-ins + customs; a custom with the same name shadows the builtin. */
    fun allWithCustom(context: Context): List<Role> {
        val customs = loadCustom(context)
        if (customs.isEmpty()) return ALL
        val names = customs.map { it.name.lowercase() }.toSet()
        return customs + ALL.filter { it.name.lowercase() !in names }
    }

    fun byName(context: Context, raw: String?): Role? {
        val n = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val custom = loadCustom(context).find { it.name.equals(n, ignoreCase = true) }
        if (custom != null) return custom
        return ALL.find { it.name.equals(n, ignoreCase = true) }
    }

    // Legacy catalog-only lookups (kept for callers without a Context).

    fun namesCsv(): String = ALL.joinToString(", ") { it.name }

    fun byName(raw: String?): Role? {
        val n = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return ALL.find { it.name.equals(n, ignoreCase = true) }
    }

    fun toolsFor(raw: String?): Set<String>? = byName(raw)?.tools

    fun promptFor(raw: String?): String? = byName(raw)?.prompt

    /** Tool whitelist for a role name, customs included. */
    fun toolsFor(context: Context, raw: String?): Set<String>? = byName(context, raw)?.tools

    // ---- [T-mindmap-team-import] mind map → custom roles ------------------

    /**
     * Import a drawn org structure (Mermaid mindmap or indented tree) as
     * custom roles: lowers the multi-agent config threshold — draw it, don't
     * type it. Sub-team leads and plain members all get a CollabRoles card;
     * when the member name matches a built-in role the builtin prompt/tools
     * are reused, otherwise a generic executor prompt is synthesized.
     * Returns the list of roles generated (customs), or empty when the text
     * is not a parseable mind map.
     */
    fun importTeamFromMindMap(raw: String): List<Role> {
        val team = MindMapTeamImport.parse(raw) ?: return emptyList()
        val builtins = ALL.associateBy { it.name }
        return team.members.map { member ->
            val builtin = builtins[member.name] ?: builtins[member.name.trim()]
            Role(
                name = member.name,
                description = member.description.ifBlank { builtin?.description ?: member.name },
                prompt = builtin?.prompt ?: genericMemberPrompt(member.name, member.unit, member.isSubTeamLead),
                tools = builtin?.tools ?: defaultMemberTools(member),
                builtin = false,
            )
        }.distinctBy { it.name.trim().lowercase() }
    }

    /** Merge imported roles into saved custom roles, replacing names case-insensitively. */
    fun mergeImportedCustom(existing: List<Role>, imported: List<Role>): List<Role> {
        if (imported.isEmpty()) return existing
        val importedNames = imported.map { it.name.trim().lowercase() }.toSet()
        return existing.filterNot { it.name.trim().lowercase() in importedNames } + imported
    }

    private fun genericMemberPrompt(name: String, unit: String, lead: Boolean): String = buildString {
        append("你是这个团队里的 $name")
        if (unit.isNotBlank()) append("，归属 $unit")
        appendLine("。")
        appendLine()
        if (lead) {
            appendLine("你带一个下属单元，负责把该单元的工作拆给组员、对齐节奏、汇总结论。")
            appendLine()
        }
        appendLine("你盯着的东西：你职责范围内的事做完、结论清晰、风险说在前面。")
        appendLine("你不管的东西：其他单元的职责；技术实现细节除非影响你的产出。")
        appendLine("该找谁：需求不清找 @产品经理；方案结构找 @架构师；要挑毛病找 @测试工程师；落纪要找 @秘书助理。")
        appendLine("什么时候不说话：讨论推进正常、与你无关时不要刷存在感。")
    }

    private fun defaultMemberTools(member: MindMapTeamImport.ImportedTeam.ImportedMember): Set<String> =
        when {
            member.isSubTeamLead -> T_WRITER
            member.name.contains("测试") || member.name.contains("QA") -> T_BUILDER
            member.name.contains("前端") || member.name.contains("后端") ||
                member.name.contains("工程") || member.name.contains("开发") -> T_BUILDER
            else -> T_READONLY
        }
}
