package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

/**
 * [port-upstream-1.16][T-b13-empty-recovery] 空回合的一次性提醒注入。
 *
 * 从 runAgentLoopBody 的局部函数抽出（架构行数上限），行为逐字节保持：
 * 返回 (是否已注入, 是否应置 once-per-run 标记)。GiveUp 既不注入也不置位。
 */
internal fun injectEmptyTurnReminderImpl(
    plan: EmptyTurnRecovery.Plan,
    agentHistory: MutableList<LLMMessage>,
): Pair<Boolean, Boolean> = when (plan) {
    is EmptyTurnRecovery.Plan.NudgeExistingPart -> {
        // Drop the empty assistant turn we just appended.
        agentHistory.removeAt(agentHistory.size - 1)
        val tail = agentHistory[plan.tailIndex]
        val newParts = tail.contentParts.toMutableList()
        newParts[plan.partIndex] = when (val part = newParts[plan.partIndex]) {
            is AgentContentPart.ToolResult -> part.copy(content = part.content + EmptyTurnRecovery.REMINDER)
            is AgentContentPart.Text -> AgentContentPart.Text(part.text + EmptyTurnRecovery.REMINDER)
            else -> part
        }
        agentHistory[plan.tailIndex] = tail.copy(contentParts = newParts)
        true to true
    }
    is EmptyTurnRecovery.Plan.AppendStandalone -> {
        agentHistory.removeAt(agentHistory.size - 1)
        agentHistory.add(
            LLMMessage(
                role = LLMMessage.Role.USER,
                content = EmptyTurnRecovery.REMINDER,
                contentParts = listOf(AgentContentPart.Text(EmptyTurnRecovery.REMINDER)),
            ),
        )
        true to true
    }
    is EmptyTurnRecovery.Plan.GiveUp -> false to false
}
