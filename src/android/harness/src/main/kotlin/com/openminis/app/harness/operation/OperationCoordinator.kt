package com.openminis.app.harness.operation

import com.openminis.app.harness.HarnessLanes
import com.openminis.app.harness.events.HarnessEvent
import com.openminis.app.harness.events.HarnessEventBus
import com.openminis.app.harness.model.ChatUsage
import com.openminis.app.harness.model.HarnessEntryEntity
import com.openminis.app.harness.model.HarnessLaneEntity
import com.openminis.app.harness.model.HarnessLaneResultEntity
import com.openminis.app.harness.model.HarnessOperationEntity
import com.openminis.app.harness.model.HarnessUsageEntity
import com.openminis.app.harness.AssistantText
import com.openminis.app.harness.CapabilityEvent
import com.openminis.app.harness.HarnessMessage
import com.openminis.app.harness.ModelSwitchEvent
import com.openminis.app.harness.SkillSuggestion
import com.openminis.app.harness.ToolCall
import com.openminis.app.harness.ToolResult
import com.openminis.app.harness.UserMessage
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Adapted from taixu OperationCoordinator (GPL-3.0-or-later).
 * https://github.com/wkbin/taixu
 *
 * 拥有全部**持久化操作转移**及其事务边界：accept / begin / provider 轮 /
 * 工具意图与落定 / 审批等待 / 挂起 / 收尾。运行链路的每一跳都先在这里落盘，
 * 进程被杀后恢复层据此重建程序计数器（见 OperationSnapshot）。
 *
 * 与上游的差异只有接缝：`HarnessRuntimeRepository`（Room）收窄为
 * [OperationRuntimeRepository]，`SessionTreeStore.MAIN_LANE` 换成
 * [HarnessLanes.MAIN_LANE]（SessionTreeStore 属 2.4/2.5 闭包，尚未移植）。
 */
class OperationCoordinator(
    private val repository: OperationRuntimeRepository,
    private val json: Json,
    private val eventBus: HarnessEventBus,
) {
    /**
     * [T-p1-6-finish-begin-race] lane 运行闸（mutex/收尾登记/锁序，见 LaneRunGate
     * 的 KDoc——为架构门 400 行帽整体搬出）。Mutex 不可重入，锁内路径（beginRun
     * 接管、reclaim）的收尾必须走 [finishLocked] 而不是 [finish]。
     */
    private val gate = LaneRunGate()

    suspend fun acceptRun(
        sessionId: String,
        userMessage: HarnessMessage,
        laneName: String = HarnessLanes.MAIN_LANE,
    ): String = gate.withLaneMutex(sessionId, laneName) {
        val lane = reclaimInterruptedLane(sessionId, laneName)
        check(lane.currentOperationId == null) { "Lane ${lane.name} is busy" }
        val now = System.currentTimeMillis()
        val operationId = UUID.randomUUID().toString()
        val operation = newOperation(operationId, sessionId, lane, now)
        val entry = messageEntry(sessionId, lane.leafId, userMessage)
        repository.acceptOperation(
            entry = entry,
            lane = lane.copy(leafId = entry.id, currentOperationId = operationId, updatedAt = now),
            operation = operation,
        )
        eventBus.emit(HarnessEvent.OperationStarted(sessionId, now, operationId, laneName))
        operationId
    }

    suspend fun acceptQueuedRun(sessionId: String, queueItemId: String, userMessage: HarnessMessage): String =
        gate.withLaneMutex(sessionId, HarnessLanes.MAIN_LANE) {
            val lane = reclaimInterruptedLane(sessionId, HarnessLanes.MAIN_LANE)
            check(lane.currentOperationId == null) { "Lane ${lane.name} is busy" }
            val now = System.currentTimeMillis()
            val operationId = UUID.randomUUID().toString()
            val operation = newOperation(operationId, sessionId, lane, now)
            val entry = messageEntry(sessionId, lane.leafId, userMessage)
            repository.acceptQueuedOperation(
                queueItemId = queueItemId,
                entry = entry,
                lane = lane.copy(leafId = entry.id, currentOperationId = operationId, updatedAt = now),
                operation = operation,
            )
            eventBus.emit(HarnessEvent.OperationStarted(sessionId, now, operationId, HarnessLanes.MAIN_LANE))
            operationId
        }

    suspend fun beginRun(sessionId: String, laneName: String = HarnessLanes.MAIN_LANE): String =
        gate.withLaneMutex(sessionId, laneName) {
            var lane = repository.ensureLane(sessionId, laneName)
            lane.currentOperationId?.let { existingId ->
                val existing = repository.findOperation(existingId)
                if (existing != null && existing.status != OperationStatus.SUSPENDED.id) return@withLaneMutex existingId
                if (existing == null) {
                    repository.clearLaneOperation(sessionId, laneName)
                } else {
                    finishLocked(sessionId, "aborted", details = "挂起的旧运行已被新请求接管", laneName = laneName)
                }
                lane = repository.ensureLane(sessionId, laneName)
            }
            val now = System.currentTimeMillis()
            val operationId = UUID.randomUUID().toString()
            repository.beginOperation(
                lane.copy(currentOperationId = operationId, updatedAt = now),
                newOperation(operationId, sessionId, lane, now),
            )
            eventBus.emit(HarnessEvent.OperationStarted(sessionId, now, operationId, laneName))
            operationId
        }

    suspend fun providerIntent(operationId: String, effectId: String, round: Int, attempt: Int, maxAttempts: Int) {
        transition(
            operationId,
            OperationStatus.RUNNING,
            OperationSnapshot(
                phase = OperationPhase.PROVIDER_INTENT.id,
                round = round,
                effectKind = "provider",
                effectId = effectId,
                reservedEntryId = effectId,
                attempt = attempt,
                maxAttempts = maxAttempts,
            ),
            ReplayPolicy.NEVER,
        )
        emitFor(operationId) { sessionId, timestamp, _ ->
            HarnessEvent.ProviderRoundStarted(sessionId, timestamp, operationId, round, attempt)
        }
    }

    suspend fun providerSettled(
        operationId: String,
        message: HarnessMessage?,
        usage: HarnessUsageEntity? = null,
        round: Int,
    ) {
        settle(
            operationId = operationId,
            message = message,
            usage = usage,
            snapshot = OperationSnapshot(phase = OperationPhase.PROVIDER_SETTLED.id, round = round),
        )
        emitFor(operationId) { sessionId, timestamp, _ ->
            HarnessEvent.ProviderRoundSettled(
                sessionId, timestamp, operationId, round,
                entryId = message?.id,
                inputTokens = usage?.inputTokens ?: 0,
                outputTokens = usage?.outputTokens ?: 0,
            )
        }
    }

    suspend fun toolIntent(
        operationId: String,
        message: HarnessMessage,
        payloadJson: String,
        replay: ReplayPolicy,
        round: Int,
    ) {
        val snapshot = OperationSnapshot(
            phase = OperationPhase.TOOL_INTENT.id,
            round = round,
            effectKind = "tool",
            effectId = message.id,
            effectPayloadJson = payloadJson,
            replayPolicy = replay.id,
        )
        settle(operationId, message, null, snapshot, replay)
        emitFor(operationId) { sessionId, timestamp, _ ->
            val toolCall = message as? ToolCall
            HarnessEvent.ToolCallStarted(
                sessionId, timestamp, operationId,
                toolCallId = message.id,
                toolName = toolCall?.rawToolName ?: "tool",
            )
        }
    }

    suspend fun toolSettled(operationId: String, message: HarnessMessage, round: Int, toolName: String? = null) {
        settle(
            operationId = operationId,
            message = message,
            usage = null,
            snapshot = OperationSnapshot(phase = OperationPhase.TOOL_SETTLED.id, round = round),
        )
        emitFor(operationId) { sessionId, timestamp, _ ->
            val result = message as? ToolResult
            HarnessEvent.ToolCallSettled(
                sessionId, timestamp, operationId,
                toolCallId = result?.toolCallId ?: message.id,
                toolName = toolName ?: "tool",
                success = result?.success ?: true,
                durationMs = result?.durationMs,
            )
        }
    }

    suspend fun waitingApproval(operationId: String) {
        val current = requireOperation(operationId)
        val snapshot = decode(current).copy(phase = OperationPhase.WAITING_APPROVAL.id)
        transition(operationId, OperationStatus.WAITING_APPROVAL, snapshot, current.replayPolicy?.let(::replayPolicy))
    }

    suspend fun suspendOperation(operationId: String, reason: String) {
        val current = requireOperation(operationId)
        // 状态 JSON 可能已损坏（半截写入）；损坏时保留原 stateJson、只更新 lastError 不可行
        // （snapshot 序列化字段固定），因此降级为携带 reason 的最小快照，保证挂起事务总能落盘。
        val snapshot = runCatching { decode(current) }
            .getOrElse { OperationSnapshot(phase = current.phase, lastError = reason) }
            .copy(lastError = reason)
        transition(operationId, OperationStatus.SUSPENDED, snapshot, current.replayPolicy?.let(::replayPolicy))
    }

    /**
     * 收尾入口：登记 lane 级收尾信号 → 拿锁 → [finishLocked]（锁序与登记时机见
     * [LaneRunGate.withFinishMutex]）。锁内路径（beginRun 接管、reclaim）直接走
     * [finishLocked]。
     */
    suspend fun finish(
        sessionId: String,
        outcome: String,
        finalEntryId: String? = null,
        details: String? = null,
        laneName: String = HarnessLanes.MAIN_LANE,
    ) {
        gate.withFinishMutex(sessionId, laneName) {
            finishLocked(sessionId, outcome, finalEntryId, details, laneName)
        }
    }

    /**
     * 锁内收尾主体（[finish] 与锁内接管路径共用）。调用方必须已持有 acceptMutex。
     * faulted 必须在这里落盘：lane 汇总用它给子智能体 lane 的圆点着色
     * （红=中断 / 绿=正常）。此前全仓库没有写入点，字段恒为 false，于是
     * “批次汇总判失败、每个子任务圆点却全是绿色”。每次收尾都按本轮结果整体
     * 覆盖，成功即自动清除上一轮的标记。
     * 只把 "failed" 视为中断："aborted" 同时被用户主动停止与进程中断复用，
     * 计入会把“用户点了停止”的主线也标成故障。
     */
    private suspend fun finishLocked(
        sessionId: String,
        outcome: String,
        finalEntryId: String? = null,
        details: String? = null,
        laneName: String = HarnessLanes.MAIN_LANE,
    ) {
        val lane = repository.ensureLane(sessionId, laneName)
        val operationId = lane.currentOperationId ?: return
        val now = System.currentTimeMillis()
        repository.finishOperation(
            HarnessLaneResultEntity(sessionId, lane.name, operationId, outcome, finalEntryId, details, now),
            lane.copy(currentOperationId = null, updatedAt = now, faulted = outcome == "failed"),
        )
        eventBus.emit(HarnessEvent.OperationFinished(sessionId, now, operationId, laneName, outcome, details))
    }

    suspend fun active(sessionId: String, laneName: String = HarnessLanes.MAIN_LANE): HarnessOperationEntity? {
        // lane 指针是权威来源：优先取 currentOperationId 指向的操作，
        // 避免历史遗留的活动行（如等待审批期间被接管的旧操作）抢占判定。
        val lane = repository.findLane(sessionId, laneName) ?: return null
        lane.currentOperationId?.let { id -> repository.findOperation(id)?.let { return it } }
        return repository.listActiveOperations(sessionId).firstOrNull { it.laneName == laneName }
    }

    suspend fun operationExists(operationId: String): Boolean = repository.findOperation(operationId) != null

    /**
     * current operation 已不挂在任何活进程运行上的 lane 是残留：进程死于运行中途
     * （恢复层把它挂起了），或审批等待被放弃。把它收尾成 aborted，下一次发送才能
     * 开新操作，而不是撞 “lane busy” 把消息静默丢掉。
     *
     * 正常运行时等审批的 lane 走不到这里：运行状态门在审批挂起期间把发送路由进队列。
     */
    private suspend fun reclaimInterruptedLane(sessionId: String, laneName: String): HarnessLaneEntity {
        val lane = repository.ensureLane(sessionId, laneName)
        val staleOperationId = lane.currentOperationId ?: return lane
        val staleOperation = repository.findOperation(staleOperationId)
        return when {
            // 悬空指针（没有活操作行）：直接清掉。
            staleOperation == null -> {
                repository.clearLaneOperation(sessionId, laneName)
                lane.copy(currentOperationId = null)
            }
            else -> {
                finishLocked(
                    sessionId,
                    "aborted",
                    details = "上次运行未完成（进程中断或审批等待失效），已被新请求接管",
                    laneName = laneName,
                )
                repository.ensureLane(sessionId, laneName)
            }
        }
    }

    /** 由 provider 上报的用量构建 append-only 台账行。 */
    fun usageEntity(
        sessionId: String,
        operationId: String,
        entryId: String?,
        provider: String?,
        modelId: String?,
        usage: ChatUsage,
    ): HarnessUsageEntity = HarnessUsageEntity(
        id = UUID.randomUUID().toString(),
        sessionId = sessionId,
        operationId = operationId,
        entryId = entryId,
        provider = provider,
        modelId = modelId,
        inputTokens = usage.inputTokens,
        outputTokens = usage.outputTokens,
        reasoningTokens = usage.reasoningTokens,
        cacheReadTokens = usage.cacheReadTokens,
        cacheWriteTokens = usage.cacheWriteTokens,
        createdAt = System.currentTimeMillis(),
    )

    private suspend fun settle(
        operationId: String,
        message: HarnessMessage?,
        usage: HarnessUsageEntity?,
        snapshot: OperationSnapshot,
        replay: ReplayPolicy? = null,
    ) {
        val current = requireOperation(operationId)
        val lane = repository.findLane(current.sessionId, current.laneName)
            ?: error("Missing lane ${current.laneName}")
        val entry = message?.let { messageEntry(current.sessionId, lane.leafId, it) }
        val now = System.currentTimeMillis()
        val next = current.copy(
            status = OperationStatus.RUNNING.id,
            phase = snapshot.phase,
            updatedAt = now,
            stateJson = json.encodeToString(OperationSnapshot.serializer(), snapshot),
            pendingEffectKind = snapshot.effectKind,
            pendingEffectId = snapshot.effectId,
            replayPolicy = replay?.id,
            attempt = snapshot.attempt,
        )
        repository.settleEffect(entry, usage, next, lane.copy(leafId = entry?.id ?: lane.leafId, updatedAt = now))
    }

    private suspend fun transition(
        operationId: String,
        status: OperationStatus,
        snapshot: OperationSnapshot,
        replay: ReplayPolicy?,
    ) {
        val current = requireOperation(operationId)
        repository.saveOperation(
            current.copy(
                status = status.id,
                phase = snapshot.phase,
                updatedAt = System.currentTimeMillis(),
                stateJson = json.encodeToString(OperationSnapshot.serializer(), snapshot),
                pendingEffectKind = snapshot.effectKind,
                pendingEffectId = snapshot.effectId,
                replayPolicy = replay?.id,
                attempt = snapshot.attempt,
            ),
        )
    }

    private suspend fun requireOperation(operationId: String) =
        repository.findOperation(operationId) ?: error("Missing harness operation $operationId")

    /** 从 operation 行补齐事件路由键；operation 已被清理时静默跳过（事件尽力投递）。 */
    private suspend fun emitFor(
        operationId: String,
        build: (sessionId: String, timestamp: Long, laneName: String) -> HarnessEvent,
    ) {
        val operation = repository.findOperation(operationId) ?: return
        eventBus.emit(build(operation.sessionId, System.currentTimeMillis(), operation.laneName))
    }

    private fun newOperation(id: String, sessionId: String, lane: HarnessLaneEntity, now: Long): HarnessOperationEntity {
        val snapshot = OperationSnapshot(phase = OperationPhase.CHECKPOINT.id)
        return HarnessOperationEntity(
            id = id,
            sessionId = sessionId,
            laneName = lane.name,
            kind = OperationKind.RUN.id,
            status = OperationStatus.RUNNING.id,
            phase = snapshot.phase,
            startedAt = now,
            updatedAt = now,
            startLeafId = lane.leafId,
            stateJson = json.encodeToString(OperationSnapshot.serializer(), snapshot),
        )
    }

    private fun messageEntry(sessionId: String, parentId: String?, message: HarnessMessage) = HarnessEntryEntity(
        id = message.id,
        sessionId = sessionId,
        parentId = parentId,
        createdAt = message.createdAt,
        entryType = "message",
        customType = message.serialType(),
        payloadJson = json.encodeToString(HarnessMessage.serializer(), message),
    )

    private fun decode(operation: HarnessOperationEntity): OperationSnapshot =
        json.decodeFromString(OperationSnapshot.serializer(), operation.stateJson)

    private fun replayPolicy(id: String): ReplayPolicy = ReplayPolicy.entries.first { it.id == id }
}

private fun HarnessMessage.serialType(): String = when (this) {
    is UserMessage -> "user"
    is AssistantText -> "assistant"
    is ToolCall -> "tool_call"
    is ToolResult -> "tool_result"
    is CapabilityEvent -> "capability_event"
    is SkillSuggestion -> "skill_suggestion"
    is ModelSwitchEvent -> "model_switch"
}
