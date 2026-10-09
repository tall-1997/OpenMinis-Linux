package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.logging.AppLogger

/**
 * Validate tool rounds in wire order. A result is valid only when it follows
 * an unmatched use with the same id and name; set membership alone is unsafe
 * because duplicate ids, wrong names, and result-before-use all reach providers
 * as malformed requests.
 */
internal fun dropOrphanedToolParts(history: List<LLMMessage>): List<LLMMessage> {
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
                        // This includes result-before-use, wrong name, and a
                        // duplicate result after the occurrence was consumed.
                        droppedResults += messageIndex to partIndex
                    }
                }
                else -> Unit
            }
        }
    }

    // An assistant tail may legitimately be awaiting execution. Only those
    // exact final-message occurrences are exempt; earlier unanswered uses are
    // repaired with an error result after their assistant message.
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

    AppLogger.warning(
        ChatViewModel.TAG,
        "[CompactDiag] ordered tool pairing repair: droppedResults=${droppedResults.size} " +
            "unansweredUses=${repairUses.size} historyCount=${history.size}",
    )
    val repairByMessage = repairUses.groupBy { it.messageIndex }
    // [T-recovery-layer] 占位文案走 planner 单一策略源（进程中断 vs 用户停止 vs
    // 不可重放说明），替换原先写死的一句英文；planner 无意见时保留旧文案兜底。
    val stubNotes = stubNotesFor(history)
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
                        content = stubNotes[it.id] ?: "Tool execution was interrupted by an unexpected error.",
                        isError = true,
                    )
                },
            )
        }
    }
    return cleaned
}

/**
 * [T-context-window-policy] 工具 schema 预留：用我方自己的估算器对全量 schema
 * 实测一次并缓存（taixu 的校准口径——预留低于实测会从折叠线余量里挖走缺口）。
 */
private val SCHEMA_RESERVE_TOKENS: Int by lazy {
    com.openminis.app.tools.ToolSchemaResolver.schemas.values
        .sumOf { com.openminis.app.harness.context.ContextWindowPolicy.estimateTokens(it.toString()) }
}

/** 历史可用预算 = 模型窗口 - 输出预留 - schema 预留；窗口未知返回 0（不治理）。 */
internal fun ChatViewModel.contextWindowBudgetTokens(): Int {
    val window = currentProvider?.model?.contextWindow ?: return 0
    return window -
        com.openminis.app.harness.context.ContextWindowPolicy.RESERVED_OUTPUT_TOKENS -
        SCHEMA_RESERVE_TOKENS
}

/**
 * 巨型用户消息的投影级截断（[ContextWindowPolicy.truncateOversizedUserTurns]）：
 * 只改发给 provider 的正文，落库 transcript 与 UI 不变。预算非正时原样返回。
 */
internal fun applyContextWindowPolicy(history: List<LLMMessage>, budgetTokens: Int): List<LLMMessage> {
    if (budgetTokens <= 0 || history.isEmpty()) return history
        val userIndexes = history.indices.filter { history[it].role == LLMMessage.Role.USER }
    if (userIndexes.isEmpty()) return history
    val turns = userIndexes.map { index ->
        com.openminis.app.harness.context.ContextWindowPolicy.ProjectedUserTurn(
            id = index.toString(),
            text = history[index].content,
            imageCount = history[index].imageParts.size,
        )
    }
    val truncated = com.openminis.app.harness.context.ContextWindowPolicy.truncateOversizedUserTurns(turns, budgetTokens)
    if (truncated == turns) return history
    return history.toMutableList().also { out ->
        userIndexes.forEachIndexed { slot, index ->
            val turn = truncated[slot]
            out[index] = out[index].copy(
                content = turn.text,
                imageParts = if (turn.imageCount == 0) emptyList() else out[index].imageParts,
            )
        }
    }
}
