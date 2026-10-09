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
    // [T-loop-context-cut] 有序配对修复的纯半边进 harness
    // （ToolRoundPairing）；占位文案仍走宿主策略源（stubNotesFor：
    // 进程中断 vs 用户停止 vs 不可重放说明），日志接 AppLogger。
    val stubNotes = stubNotesFor(history)
    return com.openminis.app.harness.agent.ToolRoundPairing.dropOrphanedToolParts(
        history = history,
        stubNotes = { id -> stubNotes[id] },
        log = { message -> AppLogger.warning(ChatViewModel.TAG, message) },
    )
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
