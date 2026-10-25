package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import org.json.JSONObject

/**
 * Discussion mode as a bounded role graph, not a fixed round-robin.
 *
 * The shape is the usable subset of the mainstream multi-agent designs:
 * CrewAI / MetaGPT / ChatDev supply the seats (product, architect, engineer,
 * tester, secretary). AutoGen supplies directed objections and a speaker
 * selector. LangGraph supplies the edges: brief → design → parallel critique
 * → at most one revise → revote by objectors only → secretary contract.
 * Missing a verdict is an objection, so a vague reply cannot rubber-stamp.
 * Discussion tools stay read-only; the main session executes the contract.
 */
object DiscussionGraph {
    const val MAX_REVISE = 1
    const val STATEMENT_CAP = 4_000

    enum class Phase {
        BRIEF,
        DESIGN,
        CRITIQUE,
        CLARIFY,
        REVISE,
        REVOTE,
        MINUTES,
    }

    enum class Verdict { ACCEPT, OBJECT, NONE }

    data class Seat(
        val role: String,
        val duty: String,
        val providerIndex: Int,
    )

    data class Statement(
        val role: String,
        val phase: Phase,
        val body: String,
        val verdict: Verdict,
        val objection: String,
        val ask: String,
    )

    private val UI_TASK = Regex("""界面|页面|按钮|布局|屏幕|交互|对话框|UI|Compose""", RegexOption.IGNORE_CASE)
    private val VERDICT_LINE = Regex("""(?im)^VERDICT:\s*(ACCEPT|OBJECT)\b""")
    private val OBJECTION_LINE = Regex("""(?im)^OBJECTION:\s*(.+)$""")
    private val ASK_LINE = Regex("""(?im)^ASK:\s*(.+)$""")
    private val ASK_ROLE = Regex("""@([^\s，。,：:]+)""")

    private val EXTRA_DENY = setOf(
        ExecuteCodeTool.NAME,
        "su_exec",
        "file_write",
        "file_edit",
        "multi_edit",
        "memory_write",
    )

    private val SHELLS = setOf("shell_execute", "shell_exec", "env_exec")

    fun needsDesigner(userText: String): Boolean = UI_TASK.containsMatchIn(userText)

    /**
     * Secretary stays on the main model, because that model executes the
     * contract. Other seats share the teammate pool; an empty pool means the
     * main model plays every role under a different contract.
     */
    fun staff(userText: String, teammateCount: Int): List<Seat> {
        val roles = mutableListOf("产品经理", "架构师", "工程师", "测试工程师")
        if (needsDesigner(userText)) roles += "前端设计师"
        roles += "秘书助理"
        return roles.mapIndexed { index, role ->
            val providerIndex = when {
                role == "秘书助理" || teammateCount <= 0 -> -1
                else -> index % teammateCount
            }
            Seat(
                role = role,
                duty = CollabRoles.byName(role)?.description.orEmpty(),
                providerIndex = providerIndex,
            )
        }
    }

    fun seat(seats: List<Seat>, role: String): Seat =
        seats.first { it.role == role }

    fun critics(seats: List<Seat>): List<Seat> =
        seats.filter { it.role == "工程师" || it.role == "测试工程师" || it.role == "前端设计师" }

    fun parseStatement(role: String, phase: Phase, raw: String): Statement {
        val body = raw.trim().ifBlank { "(无发言)" }
        val parsed = VERDICT_LINE.find(body)?.groupValues?.get(1)?.uppercase()
        val verdict = when (parsed) {
            "ACCEPT" -> Verdict.ACCEPT
            "OBJECT" -> Verdict.OBJECT
            else -> if (phase == Phase.CRITIQUE || phase == Phase.REVOTE) Verdict.OBJECT else Verdict.NONE
        }
        val objectionLine = OBJECTION_LINE.find(body)?.groupValues?.get(1)?.trim().orEmpty()
        val ask = ASK_LINE.find(body)?.groupValues?.get(1)?.trim().orEmpty()
        val objection = when {
            verdict != Verdict.OBJECT -> ""
            objectionLine.isNotBlank() && !objectionLine.equals("none", ignoreCase = true) -> objectionLine
            else -> body.lineSequence()
                .map { it.trim() }
                .lastOrNull {
                    it.isNotEmpty() &&
                        !it.startsWith("VERDICT:", ignoreCase = true) &&
                        !it.startsWith("ASK:", ignoreCase = true) &&
                        !it.startsWith("OBJECTION:", ignoreCase = true)
                }
                .orEmpty()
                .take(400)
        }
        return Statement(role, phase, clip(body), verdict, objection, ask)
    }

    /** Latest critique or revote per role. A later ACCEPT closes an objection. */
    fun openObjections(statements: List<Statement>): List<Statement> {
        val latest = linkedMapOf<String, Statement>()
        for (s in statements) {
            if (s.phase == Phase.CRITIQUE || s.phase == Phase.REVOTE) latest[s.role] = s
        }
        return latest.values.filter { it.verdict == Verdict.OBJECT }
    }

    fun afterCritique(statements: List<Statement>): Phase =
        if (openObjections(statements).isEmpty()) Phase.MINUTES else Phase.REVISE

    /** One revise is the budget. Revote never opens another revise. */
    fun afterRevote(): Phase = Phase.MINUTES

    /**
     * At most one non-architect role named by an open objection. That is the
     * speaker selector: unanswered @mentions get one turn, then the architect
     * revises. Everyone else stays silent.
     */
    fun clarificationRole(seats: List<Seat>, objections: List<Statement>): String? {
        val staffed = seats.map { it.role }.toSet()
        for (item in objections) {
            val mentioned = ASK_ROLE.find(item.ask)?.groupValues?.get(1) ?: continue
            val known = CollabRoles.byName(mentioned)?.name ?: continue
            if (known != "架构师" && known in staffed) return known
        }
        return null
    }

    fun objectorSeats(seats: List<Seat>, objections: List<Statement>): List<Seat> {
        val names = objections.map { it.role }.toSet()
        return seats.filter { it.role in names }
    }

    fun allowedTools(role: String, tools: List<AgentToolDefinition>): List<AgentToolDefinition> {
        val roleTools = CollabRoles.toolsFor(role)
        return tools.filter { def ->
            !SubAgentKind.blocks(SubAgentKind.PLAN, def.name) &&
                def.name !in EXTRA_DENY &&
                (roleTools == null || def.name in roleTools)
        }
    }

    fun denyExecution(name: String, argsJson: String): String? {
        if (SubAgentKind.isSpawnTool(name) || SubAgentKind.blocks(SubAgentKind.PLAN, name) || name in EXTRA_DENY) {
            return "讨论模式只读，拒绝 $name。"
        }
        if (name in SHELLS) {
            val cmd = commandOf(argsJson) ?: return "讨论模式拒绝无法解析的 shell。"
            return SubAgentKind.readOnlyShellDenial(cmd)
        }
        return null
    }

    fun fallbackContract(brief: String, design: String, open: List<Statement>): String = buildString {
        appendLine("【已定】")
        appendLine(design.ifBlank { brief }.ifBlank { "按用户请求的最小改动实现。" }.take(2000))
        appendLine("【未采纳】")
        if (open.isEmpty()) appendLine("无")
        else open.forEach { append(it.role).append("：").appendLine(it.objection.ifBlank { "未关闭" }) }
        appendLine("【任务】")
        appendLine("1. 按已定方案实现，不扩大范围。")
        appendLine("【风险】")
        appendLine("秘书未单独列出风险时，先核对未采纳项。")
        appendLine("【停止条件】")
        appendLine("验收未满足，或要实现非目标时，停止并说明。")
    }

    fun extractContract(body: String, fallback: String): String {
        val idx = body.indexOf("【已定】")
        if (idx >= 0) return body.substring(idx).trim()
        if (body.isBlank() || body.startsWith("(")) return fallback
        return body.trim()
    }

    fun phaseHeading(phase: Phase): String = when (phase) {
        Phase.BRIEF -> "简报"
        Phase.DESIGN -> "方案"
        Phase.CRITIQUE -> "审查"
        Phase.CLARIFY -> "澄清"
        Phase.REVISE -> "修订"
        Phase.REVOTE -> "复审"
        Phase.MINUTES -> "执行契约"
    }

    fun renderBoard(userText: String, excerpt: String, statements: List<Statement>): String = buildString {
        append("用户请求:\n").append(userText.trim()).append('\n')
        if (excerpt.isNotBlank()) {
            append("\n最近对话:\n").append(excerpt.take(4000)).append('\n')
        }
        for (s in statements) {
            append("\n### ").append(phaseHeading(s.phase)).append("（").append(s.role).append("）\n")
            append(s.body).append('\n')
        }
    }

    fun discussionMarkdown(board: String, contract: String): String = buildString {
        append("## 计划讨论（角色图）\n\n")
        append("图：产品简报 → 架构方案 → 工程师/测试并行审查 → 有异议才修订一次 → 秘书写执行契约。")
        append("讨论只读，不改仓库。结束后主会话按契约执行，不要再开一轮讨论。关闭入口：设置 → 多智能体。\n\n")
        append("### 执行契约\n\n")
        append(contract.trim()).append("\n\n")
        append("### Synthesis\n\n")
        append(contract.trim()).append("\n\n")
        append(board.trim())
        append("\n\n---\n讨论结束，本轮将按执行契约开始执行。未关闭的异议是约束，不是再讨论的理由。\n")
    }

    fun roleSystem(role: String): String {
        val catalog = CollabRoles.byName(role)
        return buildString {
            append("你是").append(role)
            if (catalog != null) {
                append("，").append(catalog.description).append("。\n\n")
                append(catalog.prompt)
            }
            append("\n\n")
            append(HARD_CONSTRAINT)
        }
    }

    fun clip(text: String, max: Int = STATEMENT_CAP): String =
        if (text.length <= max) text else text.take(max) + "\n…(截断)"

    private fun commandOf(argsJson: String): String? {
        val obj = try {
            JSONObject(argsJson)
        } catch (_: Exception) {
            return null
        }
        val cmd = obj.optString("command").ifBlank { obj.optString("cmd") }
        return cmd.trim().ifBlank { null }
    }

    private const val HARD_CONSTRAINT = """
讨论阶段硬约束，覆盖角色卡里任何写文件或改系统的指示：
- 只读。不能写文件，不能装包，不能改配置，不能派生子代理。
- 用用户请求的语言写正文。
- 审查和复审必须以这三行结束：
VERDICT: ACCEPT 或 OBJECT
OBJECTION: 没有就写 none
ASK: 没有就写 none，否则 @角色名
"""
}
