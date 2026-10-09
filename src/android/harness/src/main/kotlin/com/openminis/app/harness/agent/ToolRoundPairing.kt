package com.openminis.app.harness.agent

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

/**
 * [T-loop-context-cut] 工具轮有序配对修复（原 dropOrphanedToolParts 的纯
 * 半边，循环本体收官刀）。
 *
 * 按 wire 顺序校验工具轮：result 只有在跟在同 id 同名的未配对 use **之后**
 * 才有效——集合成员判定不安全（重复 id / 错名 / result 先于 use 都会以
 * 畸形请求打到 provider）。修复动作：
 * - 悬空 result（含 result 先于 use、错名、重复 result）→ 丢弃；
 * - 未应答 use → 在其 assistant 消息后补一条错误 result 占位（尾部等待
 *   执行的 use 豁免——那是合法的在飞轮）。
 *
 * 占位文案由宿主注入（stubNotes：进程中断 vs 用户停止 vs 不可重放说明，
 * 单一策略源在宿主的 DanglingToolCallPlanner）；无意见时用兜底文案。
 */
object ToolRoundPairing {

    /**
     * @param history 待修复的历史
     * @param stubNotes id → 占位文案（宿主策略源）
     * @param log 诊断日志回调（宿主接 AppLogger）
     */
    fun dropOrphanedToolParts(
        history: List<LLMMessage>,
        stubNotes: (toolCallId: String) -> String? = { null },
        log: (String) -> Unit = {},
    ): List<LLMMessage> {
        data class Use(
            val id: String,
            val name: String,
            val messageIndex: Int,
            val partIndex: Int,
        )
        val pending = ArrayList<Use>()
        val unmatchedUses = ArrayList<Use>()
        val droppedResults = HashSet<Pair<Int, Int>>()

        history.forEachIndexed { messageIndex, message ->
            message.contentParts.forEachIndexed { partIndex, part ->
                when (part) {
                    is AgentContentPart.ToolUse -> {
                        val use = Use(part.id, part.name, messageIndex, partIndex)
                        pending += use
                        unmatchedUses += use
                    }
                    is AgentContentPart.ToolResult -> {
                        val matchIndex = pending.indexOfFirst { it.id == part.id && it.name == part.name }
                        if (matchIndex >= 0) {
                            val use = pending.removeAt(matchIndex)
                            unmatchedUses.remove(use)
                        } else {
                            droppedResults += messageIndex to partIndex
                        }
                    }
                    else -> Unit
                }
            }
        }

        // 尾部 assistant 可能正在等执行——只有**恰好是最后一条消息**上的
        // use 豁免；更早的未应答 use 补错误 result。
        val tailUses = if (history.lastOrNull()?.role == LLMMessage.Role.ASSISTANT) {
            history.last().contentParts.mapIndexedNotNull { partIndex, part ->
                (part as? AgentContentPart.ToolUse)?.let { it.id to it.name to partIndex }
            }.toSet()
        } else emptySet()
        val repairUses = unmatchedUses.filterNot { use ->
            use.messageIndex == history.lastIndex &&
                (use.id to use.name to use.partIndex) in tailUses
        }
        if (droppedResults.isEmpty() && repairUses.isEmpty()) return history

        log("[CompactDiag] ordered tool pairing repair: droppedResults=${droppedResults.size} " +
            "unansweredUses=${repairUses.size} historyCount=${history.size}")
        val repairByMessage = repairUses.groupBy { it.messageIndex }
        val cleaned = ArrayList<LLMMessage>(history.size + repairUses.size)
        history.forEachIndexed { index, message ->
            val kept = message.contentParts.mapIndexedNotNull { partIndex, part ->
                if (part is AgentContentPart.ToolResult && (index to partIndex) in droppedResults) null else part
            }
            if (kept.isNotEmpty() || message.contentParts.isEmpty()) {
                cleaned += if (kept.size == message.contentParts.size) message else message.copy(contentParts = kept)
            }
            val repairs = repairByMessage[index].orEmpty()
            if (repairs.isNotEmpty()) {
                cleaned += LLMMessage(
                    role = LLMMessage.Role.USER,
                    content = "",
                    contentParts = repairs.map {
                        AgentContentPart.ToolResult(
                            id = it.id,
                            name = it.name,
                            content = stubNotes(it.id) ?: "Tool execution was interrupted by an unexpected error.",
                            isError = true,
                        )
                    },
                )
            }
        }
        return cleaned
    }
}
