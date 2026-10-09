package com.openminis.app.harness.context

import com.openminis.app.harness.effects.OutputRetention
import com.openminis.app.harness.effects.ToolOutputRetention
import com.openminis.app.harness.effects.keepHeadWholeLines
import com.openminis.app.harness.effects.keepTailWholeLines

/**
 * Adapted from taixu ContextWindowPolicy (GPL-3.0-or-later).
 * https://github.com/wkbin/taixu
 *
 * 上下文预算与体积治理的**纯策略层**。上游 1075 行里与 ApiMessage / 折叠线 UI
 * 耦合的部分不移植；移的是我方缺的三件（COMPARISON-TAIXU P1-4）：
 *
 *  1. [estimateTokens] 的分桶估算（CJK / ascii / 标点三价），中文标点与 CJK 同价——
 *     落进 ascii 标点桶按 /2.8 会对中文上下文系统性低估；
 *  2. [truncateOversizedUserTurns] 巨型用户消息兜底：折叠线只能按消息边界切，单条
 *     自身超线的用户消息（粘贴长文档/日志）无法折叠，保留尾区恒超预算 → 每次
 *     请求必被 provider 400 且压缩判定每次命中每次失败，形成稳定失败循环。
 *     投影级截断（头尾保留 + 指针标记），落库 transcript 与 UI 不变；
 *  3. [REQUEST_BODY_HARD_LIMIT_BYTES] 请求体物理上限：token 预算看不见 UTF-8/JSON
 *     转义膨胀与 Base64 图片，中转 Nginx `client_max_body_size` 往往只有 1–10MB，
 *     超限即 HTTP 413。[compactToolOutputForByteBudget] 是超限时的第一把刀
 *     （压当前轮超长工具输出），图片剥离由调用方按 [stripImagesNote] 执行。
 *
 * 与上游的差异：消息模型泛型化成 [ProjectedUserTurn] / 工具输出四元组，宿主拿
 * 自己的 LLMMessage / ToolResult 投影进来再落回去——策略不绑死任何消息类型。
 * 截断方向复用 effects 的 [ToolOutputRetention]（命令尾偏向 / 读取头偏向）。
 */
object ContextWindowPolicy {

    /** 发给 Provider 的 JSON 请求体物理上限（字节）。 */
    const val REQUEST_BODY_HARD_LIMIT_BYTES = 4 * 1024 * 1024

    /** 输出预留（与上游同值）：折叠线要从窗口里先扣掉它。 */
    const val RESERVED_OUTPUT_TOKENS = 8_192

    /** 超过此 token 数的用户消息才参与巨型截断（普通消息交给折叠线，避免误伤）。 */
    const val MIN_GIANT_USER_MESSAGE_TOKENS = 2_000

    /** 巨型用户消息截断后至少保留的头部 token 数。 */
    const val MIN_KEPT_USER_TURN_TOKENS = 800

    /** 图片的保守 token 估算（每张）。低估会让请求溢出 provider 400，高估只是折叠稍早。 */
    const val ESTIMATED_IMAGE_TOKENS = 1_600

    /** 字节治理压工具输出时的头/尾保留字符。 */
    const val ACTIVE_TOOL_KEEP_HEAD_CHARS = 6_000
    const val ACTIVE_TOOL_KEEP_TAIL_CHARS = 1_500

    const val USER_TRUNCATION_MARKER =
        "\n\n[…… 消息过长已截断：完整原文保留在会话记录中，需要时可用 read_session 读取 ……]\n\n"

    /**
     * 分桶 token 估算：CJK 与全角标点 /1.8、字母数字 /2.5、其余标点 /2.8。
     * 全角区（FF00-FFEF，如 ，！？：）与 CJK 同价——中文标点实际 ~1 token/字。
     */
    fun estimateTokens(text: String): Int {
        if (text.isBlank()) return 0
        var cjk = 0
        var ascii = 0
        var punctuation = 0
        text.forEach { ch ->
            when {
                ch.code in 0x2E80..0x9FFF || ch.code in 0xAC00..0xD7AF || ch.code in 0xFF00..0xFFEF -> cjk++
                ch.isWhitespace() -> Unit
                ch.isLetterOrDigit() -> ascii++
                else -> punctuation++
            }
        }
        return (cjk / 1.8f + ascii / 2.5f + punctuation / 2.8f).toInt().coerceAtLeast(1)
    }

    /** 一条用户轮在投影里的形状（文本 + 图片数）。 */
    data class ProjectedUserTurn(val id: String, val text: String, val imageCount: Int)

    private fun tokensOf(turn: ProjectedUserTurn): Int =
        estimateTokens(turn.text) + turn.imageCount * ESTIMATED_IMAGE_TOKENS

    /**
     * 总量超线时从最大的用户消息开始截（粘贴的长文档是超限主因，也是唯一可无损
     * 压缩的正文）；文本截完仍超线则按从旧到新剥离图片并留标记。图片本体仍在
     * 落库 transcript 与 UI 中可见。总量不超线时原样返回。
     */
    fun truncateOversizedUserTurns(turns: List<ProjectedUserTurn>, limitTokens: Int): List<ProjectedUserTurn> {
        if (limitTokens <= 0 || turns.isEmpty()) return turns
        val total = turns.sumOf(::tokensOf)
        if (total <= limitTokens) return turns
        var overage = total - limitTokens
        val out = turns.toMutableList()
        val candidateIndexes = out.withIndex()
            .filter { tokensOf(it.value) > MIN_GIANT_USER_MESSAGE_TOKENS }
            .sortedByDescending { tokensOf(it.value) }
            .map { it.index }
        for (index in candidateIndexes) {
            if (overage <= 0) break
            val turn = out[index]
            val oldTokens = estimateTokens(turn.text)
            val targetTokens = (oldTokens - overage).coerceAtLeast(MIN_KEPT_USER_TURN_TOKENS)
            val fitted = fitUserText(turn.text, targetTokens) ?: continue
            val newTokens = estimateTokens(fitted)
            if (newTokens >= oldTokens) continue
            overage -= oldTokens - newTokens
            out[index] = turn.copy(text = fitted)
        }
        if (overage > 0) {
            for (index in out.indices) {
                if (overage <= 0) break
                val turn = out[index]
                if (turn.imageCount == 0) continue
                val saved = turn.imageCount * ESTIMATED_IMAGE_TOKENS
                out[index] = turn.copy(
                    imageCount = 0,
                    text = turn.text + "\n\n[…… 本消息携带的 ${turn.imageCount} 张图片因上下文预算已从模型上下文省略 ……]",
                )
                overage -= saved
            }
        }
        return out
    }

    /** 头尾保留 + 指针标记的迭代拟合；六轮不收敛返回 null（调用方保持原文）。 */
    fun fitUserText(text: String, targetTokens: Int): String? {
        var keptChars = (targetTokens * 1.5f).toInt().coerceAtLeast(USER_TRUNCATION_MARKER.length + 2)
        repeat(6) {
            if (text.length <= keptChars) return null
            val head = keptChars * 3 / 4
            val candidate = text.take(head) + USER_TRUNCATION_MARKER + text.takeLast(keptChars - head)
            if (estimateTokens(candidate) <= targetTokens) return candidate
            keptChars = (keptChars * 3 / 4).coerceAtLeast(USER_TRUNCATION_MARKER.length + 2)
        }
        return null
    }

    /**
     * 字节超限时的第一把刀：压当前轮最长的工具输出，方向随工具切换
     * （命令/构建尾偏向、读取头偏向），附回读指针。压缩后不比原文短则返回 null
     * （调用方换下一把刀）。
     */
    fun compactToolOutputForByteBudget(toolName: String?, output: String): String? {
        if (output.length <= ACTIVE_TOOL_KEEP_HEAD_CHARS + ACTIVE_TOOL_KEEP_TAIL_CHARS) return null
        val tailFirst = ToolOutputRetention.forTool(toolName) == OutputRetention.TAIL
        val headBudget = if (tailFirst) ACTIVE_TOOL_KEEP_TAIL_CHARS else ACTIVE_TOOL_KEEP_HEAD_CHARS
        val tailBudget = if (tailFirst) ACTIVE_TOOL_KEEP_HEAD_CHARS else ACTIVE_TOOL_KEEP_TAIL_CHARS
        val head = keepHeadWholeLines(output, headBudget)
        val tail = keepTailWholeLines(output, tailBudget)
        val compacted = head +
            "\n[工具输出因请求体体积限制已压缩；全文在会话记录中，需要细节时调用 read_session 回读]\n" +
            tail
        return if (compacted.length >= output.length) null else compacted
    }

    /** 图片剥离的标记文案（调用方把 imageCount 填进来）。 */
    fun stripImagesNote(imageCount: Int): String =
        "\n\n[…… 本消息携带的 $imageCount 张图片因请求体体积限制已从模型上下文省略 ……]"
}
