package com.openminis.app.harness.agent

import org.json.JSONObject

/**
 * [T-taixu-2.3] 子代理车道（lane）执行半边的**纯决策**件（Adapted from
 * taixu SubagentLaneRunner 的轮预算/终局/输出合成，语义逐条对齐我方
 * SubAgentRunner 内联实现）。
 *
 * 宿主保留：provider 调用、工具执行、UI 卡片与 onStep/onUi 事件、
 * SubAgentKind 门禁（工具黑白名单是宿主策略源）。这里只住无副作用
 * 的判定与文案：
 * - 轮预算钳制（1..ABSOLUTE_MAX_TURNS，防失控）；
 * - 80%/95% 预算警告注入文案（95% 是「立即停止调用工具交报告」的
 *   强制令，不是建议）；
 * - 预算耗尽/令牌池耗尽的页脚文案（部分报告优于完美但没交的报告）；
 * - 输出合成（Trace + 报告 + 页脚的固定版式）；
 * - 工具参数预览（时间线里给人看的一行摘要）。
 */
object LaneRoundPolicy {

    /** 轮预算硬顶（失控护栏——不是默认值）。 */
    const val ABSOLUTE_MAX_TURNS = 200

    /** 预算消耗比例触发软警告。 */
    const val WARN_FRACTION = 0.80

    /** 预算消耗比例触发强制收场令。 */
    const val FORCE_FRACTION = 0.95

    /** 单条报告的字符上限。 */
    const val MAX_REPORT_CHARS = 64_000

    /** 钳制请求的轮预算：显式值夹进 [1, ABSOLUTE_MAX_TURNS]。 */
    fun clampTurns(maxTurns: Int): Int = maxTurns.coerceIn(1, ABSOLUTE_MAX_TURNS)

    /** 本轮的预算警告级别。 */
    enum class BudgetLevel { NONE, WARN, FORCE }

    fun budgetLevel(turn: Int, turns: Int): BudgetLevel {
        val frac = turn.toDouble() / turns
        return when {
            frac >= FORCE_FRACTION -> BudgetLevel.FORCE
            frac >= WARN_FRACTION -> BudgetLevel.WARN
            else -> BudgetLevel.NONE
        }
    }

    /**
     * 80% 软警告：优先收尾、别再广撒网。
     * 95% 强制令：立即停止工具调用，本轮交部分报告。
     */
    fun budgetWarningMessage(turn: Int, turns: Int, level: BudgetLevel): String? = when (level) {
        BudgetLevel.FORCE ->
            "<budget_warning used=\"$turn/$turns\" force=\"true\">\n" +
                "You are at the final stretch of your turn budget. STOP calling tools NOW and return your findings so far as your final report in THIS turn — partial results are far more valuable than a perfect result you never submit. Lead with what you already confirmed, then list what is still unverified.\n" +
                "</budget_warning>"
        BudgetLevel.WARN ->
            "<budget_warning used=\"$turn/$turns\">\n" +
                "You have used $turn of your $turns turns (~${(turn.toDouble() / turns * 100).toInt()}%). Prioritize finishing: avoid further broad searches, consolidate what you have, and prepare to submit your report.\n" +
                "</budget_warning>"
        BudgetLevel.NONE -> null
    }

    /** 轮预算耗尽的页脚（有发现 vs 无发现两种）。 */
    fun turnBudgetFooter(turns: Int, hasFindings: Boolean): String = if (hasFindings) {
        "(reached the $turns-turn budget — partial report above)"
    } else {
        "(sub-agent reached the $turns-turn budget with no findings to report)"
    }

    /** 共享令牌池耗尽的页脚。 */
    fun tokenExhaustedFooter(turn: Int, turns: Int): String =
        "(stopped: shared token budget exhausted at $turn/$turns turns)"

    /** 失败页脚。 */
    fun failureFooter(error: String): String = "Sub-agent failed: $error"

    /**
     * 输出合成：## Trace（工具时间线）+ ## Report（正文）+ 页脚，整体超长
     * 时保尾截断（最新结论在尾部）。空输出兜底文案逐字保留。
     */
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
     * 卡片与详情显示**当前结果**而非步骤历史：取 ## Report 起的正文；
     * 只有 Trace 没有报告 = 已结束；无标记 = 原文。
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

    /** 报告保尾截断：丢头部（旧内容），保尾部（最新结论）。 */
    fun truncate(text: String): String {
        if (text.length <= MAX_REPORT_CHARS) return text
        return "…(truncated)\n" + text.takeLast(MAX_REPORT_CHARS)
    }

    /**
     * 工具参数预览：command/path/query/url/pattern 取首个非空，压成一行
     * 80 字符。时间线与 UI 卡片共用。
     */
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
}
