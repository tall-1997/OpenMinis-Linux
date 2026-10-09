package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.harness.HarnessMessage
import com.openminis.app.harness.HarnessTool
import com.openminis.app.harness.ToolCall
import com.openminis.app.harness.ToolResult
import com.openminis.app.harness.effects.DanglingToolCallPlanner
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * [T-recovery-layer] P0-3 统一重试/恢复层的恢复半边：把 harness 的
 * [DanglingToolCallPlanner] / [ToolReplayPolicy] 接进宿主的历史修复与冷启动恢复。
 *
 * 两条消费路径：
 *  - **修复文案**：[stubNotesFor] 给 [dropOrphanedToolParts] 的占位结果提供
 *    策略化文案（进程中断 vs 用户停止），替换原先写死的一句英文；
 *  - **SAFE 重放**：[replaySafeDanglingToolCalls] 在冷启动恢复期执行 planner
 *    判为可重放的只读调用（file_read / search_sessions / read_session），把结果
 *    补进内存历史——崩溃前的读操作无缝续接；写操作与外部副作用一律不重放，
 *    占位文案交给模型重新发起（防止未经用户确认二次执行写）。
 *
 * 用户取消路径不走这里：取消处理器已经用 CANCELLED 标记落盘了配对结果，
 * planner 在历史里看不到悬空调用，天然满足「interrupted 一律不重放」。
 */

/** 只关心 SAFE 集的三个名字；其余一律落 BASE（= 不可重放）。 */
internal fun harnessToolFor(name: String): HarnessTool = when (name) {
    "file_read" -> HarnessTool.READ
    "search_sessions" -> HarnessTool.HISTORY_SEARCH
    "read_session" -> HarnessTool.HISTORY_READ
    else -> HarnessTool.BASE
}

/** 把历史里的 tool_use / tool_result 部件投影成 planner 需要的消息视图。 */
internal fun toolMessagesOf(history: List<LLMMessage>): List<HarnessMessage> = history.flatMap { message ->
    message.contentParts.mapNotNull { part ->
        when (part) {
            is AgentContentPart.ToolUse -> ToolCall(
                id = part.id,
                createdAt = 0L,
                tool = harnessToolFor(part.name),
                args = runCatching {
                    Json.parseToJsonElement(part.input.toString()) as? JsonObject ?: JsonObject(emptyMap())
                }.getOrDefault(JsonObject(emptyMap())),
                rawToolName = part.name,
            )
            is AgentContentPart.ToolResult -> ToolResult(
                // 部件 id 就是 toolUseId；消息 id 必须唯一，加后缀避开调用 id。
                id = part.id + ":result",
                createdAt = 0L,
                toolCallId = part.id,
                success = !part.isError,
                output = part.content,
            )
            else -> null
        }
    }
}

/** 悬空调用 → 占位文案（planner 的 Stubbed 分支）。无悬空则空 map。 */
internal fun stubNotesFor(history: List<LLMMessage>): Map<String, String> =
    DanglingToolCallPlanner.plan(toolMessagesOf(history), interrupted = false)
        .filterIsInstance<DanglingToolCallPlanner.Stubbed>()
        .associate { it.call.id to it.note }

/** planner 判为可重放的悬空调用（含它在历史里的位置，供补结果用）。 */
internal data class ReplayableUse(
    val callId: String,
    val toolName: String,
    val argsJson: String,
    val messageIndex: Int,
)

internal fun replayableUses(history: List<LLMMessage>): List<ReplayableUse> {
    val replayIds = DanglingToolCallPlanner.plan(toolMessagesOf(history), interrupted = false)
        .filterIsInstance<DanglingToolCallPlanner.Replay>()
        .mapTo(mutableSetOf()) { it.call.id }
    if (replayIds.isEmpty()) return emptyList()
    val answered = history.flatMap { m -> m.contentParts }
        .filterIsInstance<AgentContentPart.ToolResult>()
        .mapTo(mutableSetOf()) { it.id }
    // [T-p1-5-recovery-user-turn] 同一条 assistant 消息可以有**多个**可重放调用
    //（并行 tool_calls 崩在落盘前）——firstOrNull 只重放第一个，其余悬空。
    return history.mapIndexedNotNull { messageIndex, message ->
        val uses = message.contentParts.filterIsInstance<AgentContentPart.ToolUse>()
            .filter { it.id in replayIds && it.id !in answered }
        if (uses.isEmpty()) {
            null
        } else {
            uses.map { ReplayableUse(it.id, it.name, it.input.toString(), messageIndex) }
        }
    }.flatten()
}

/**
 * 崩溃窗口合成：操作台账里有 tool_intent（模型已回工具调用）但内存历史/Room 里
 * 没有对应 tool_use 行——进程死在「收到响应」与「落行」之间。把意图补成 assistant
 * tool_use 部件进内存历史，后续的 replay/stub 判定（DanglingToolCallPlanner）就能
 * 看见它：SAFE 只读重放，写操作占位说明。台账与 SessionTreeStore 的盘上投影由此
 * 成为恢复链的凭据源，而不是两件落地的死代码。
 */
internal suspend fun ChatViewModel.synthesizeUnsettledOperationIntents() {
    val intents = com.openminis.app.operation.OperationBridge.unsettledIntents(context.applicationContext, activeSessionId)
        ?: return
    if (intents.isEmpty()) return
    val known = agentHistory.flatMap { m -> m.contentParts }
        .filterIsInstance<AgentContentPart.ToolUse>()
        .mapTo(mutableSetOf()) { it.id }
    val missing = intents.filter { it.id !in known }
    if (missing.isEmpty()) return
    val port = conversationPort() // [T-android-seam-extraction] 接缝三的真实消费点
    for (call in missing) {
        port.append(
            LLMMessage(
                role = LLMMessage.Role.ASSISTANT,
                content = "",
                contentParts = listOf(
                    AgentContentPart.ToolUse(
                        id = call.id,
                        name = call.rawToolName ?: "tool",
                        input = org.json.JSONObject(call.args.toString()),
                    ),
                ),
            ),
        )
    }
    AppLogger.warning(
        ChatViewModel.TAG,
        "[Recovery] synthesized ${missing.size} unsettled tool intent(s) from the operation ledger",
    )
}

/**
 * 冷启动恢复期执行 SAFE 悬空调用并把结果补进内存历史。
 *
 * 只补内存、不落盘：下一次冷启动会重新修复（读操作幂等且廉价），避免在恢复期
 * 往 DB 写合成行造成 UI 噪声。执行失败按不可重放降级——planner 的修复文案路径
 * 会在下一次历史修复时兜住它。
 */
internal suspend fun ChatViewModel.replaySafeDanglingToolCalls() {
    if (_isStreaming.value) return
    synthesizeUnsettledOperationIntents()
    val uses = replayableUses(agentHistory)
    if (uses.isEmpty()) return
    // [T-p1-5-recovery-user-turn] tool_result 必须以 USER 角色进历史：Anthropic
    // 协议要求 tool_result 块在 user 轮（且 stripOrphanToolResults 只清洗 USER 轮
    // 的孤儿），旧实现把结果追加进持有 tool_use 的同一条 ASSISTANT 消息——恢复后
    // 首个请求对 Anthropic 系端点直接 400，重试/回退救不了。主循环的形状是
    // ToolRoundOutcome.toolResultMessage（user 角色），这里对齐它。
    // 同一条 assistant 消息的多个可重放调用合并进**一条** user 结果消息（与主循环
    // 并行工具落盘同形）；按 messageIndex 聚组后倒序插入，保持前面的索引有效。
    val resultsByIndex = linkedMapOf<Int, MutableList<AgentContentPart.ToolResult>>()
    for (use in uses) {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                when (use.toolName) {
                    "file_read" -> com.openminis.app.tools.FileReadTool.execute(use.argsJson, activeSessionId, context)
                    "search_sessions" -> com.openminis.app.tools.SessionLookupTool.executeSearch(use.argsJson, activeSessionId, context)
                    "read_session" -> com.openminis.app.tools.SessionLookupTool.executeRead(use.argsJson, activeSessionId, context)
                    else -> null
                }
            }.getOrNull()
        } ?: continue
        val index = use.messageIndex
        if (index !in agentHistory.indices) continue
        resultsByIndex.getOrPut(index) { mutableListOf() }.add(
            AgentContentPart.ToolResult(
                id = use.callId,
                name = use.toolName,
                content = result.output,
                isError = !result.success,
            ),
        )
    }
    val updated = insertRecoveryToolResults(agentHistory, resultsByIndex)
    val replayed = updated.size - agentHistory.size
    if (updated !== agentHistory) {
        agentHistory.clear()
        agentHistory.addAll(updated)
    }
    if (replayed > 0) {
        AppLogger.info(
            ChatViewModel.TAG,
            "[Recovery] replayed $replayed safe dangling tool call(s) of ${uses.size} planned",
        )
    }
}

/**
 * [T-p1-5-recovery-user-turn] 纯函数：把按 messageIndex 聚组的重放结果以 USER
 * 结果消息插入历史副本（倒序插入保持前面的索引有效；越界索引跳过——与执行侧
 * 同一守卫）。恢复链端到端测试对准这里；[replaySafeDanglingToolCalls] 把
 * agentHistory（MutableList）按其结果同步。
 */
internal fun insertRecoveryToolResults(
    history: List<LLMMessage>,
    resultsByIndex: Map<Int, List<AgentContentPart.ToolResult>>,
): List<LLMMessage> {
    val mutable = history.toMutableList()
    var inserted = 0
    for ((index, parts) in resultsByIndex.entries.toList().asReversed()) {
        if (index !in mutable.indices) continue
        mutable.add(
            (index + 1).coerceAtMost(mutable.size),
            com.openminis.app.harness.agent.ToolRoundOutcome.toolResultMessage(parts, dbMessageId = null),
        )
        inserted += parts.size
    }
    return mutable
}
