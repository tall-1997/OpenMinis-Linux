package com.openminis.app.operation

import android.content.Context
import com.openminis.app.harness.HarnessMessage
import com.openminis.app.harness.ToolCall
import com.openminis.app.harness.ToolResult
import com.openminis.app.harness.events.HarnessEventBus
import com.openminis.app.harness.operation.OperationCoordinator
import com.openminis.app.harness.operation.OperationPhase
import com.openminis.app.harness.operation.OperationSnapshot
import com.openminis.app.harness.operation.OperationStatus
import com.openminis.app.harness.operation.ReplayPolicy
import com.openminis.app.harness.runtime.FileHarnessRuntimePersistence
import com.openminis.app.harness.session.SessionTreeStore
import com.openminis.app.data.model.LLMUsage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * [T-operation-wiring] OperationCoordinator / SessionTreeStore 的宿主接缝（P0-1 欠账二清）。
 *
 * 循环转移挂点（全部 best-effort，台账失败绝不挡运行）：
 *  - runAgentLoop 入口 beginRun → 持久化运行程序计数器开账；
 *  - 每轮 provider 请求前 providerIntent、落定后 providerSettled（带 usage 台账行）；
 *  - 每次工具执行前 toolIntent（**整条 ToolCall 消息**序列化进 effectPayloadJson，
 *    恢复期能还原出完整调用而不只是参数）、落定后 toolSettled；
 *  - 取消/后台挂起 suspendOperation；loop 收尾 finish。
 *
 * 崩溃窗口的消费者：模型已回 tool_use、Room 行还没落盘时进程死亡，内存历史与 DB
 * 都没有凭据，只有这份台账有。[unsettledIntents] 把 status=running 且 phase=tool_intent
 * 的悬空意图还原成 ToolCall 消息，恢复链（replaySafeDanglingToolCalls）把它们补进
 * 内存历史后交给 DanglingToolCallPlanner 判重放/占位——SessionTreeStore 的分支投影
 * 在同一条链上提供盘上转录，两件落地未接线的件由此同时有了消费者。
 */
object OperationBridge {

    private const val DIR = "harness_runtime"

    private val json = Json { ignoreUnknownKeys = true }

    private val persistences = java.util.concurrent.ConcurrentHashMap<String, FileHarnessRuntimePersistence>()
    private val coordinators = java.util.concurrent.ConcurrentHashMap<String, OperationCoordinator>()
    private val treeStores = java.util.concurrent.ConcurrentHashMap<String, SessionTreeStore>()

    fun persistence(filesDir: File): FileHarnessRuntimePersistence =
        persistences.computeIfAbsent(filesDir.absolutePath) { FileHarnessRuntimePersistence(File(filesDir, DIR)) }

    fun coordinator(filesDir: File): OperationCoordinator =
        coordinators.computeIfAbsent(filesDir.absolutePath) {
            OperationCoordinator(persistence(filesDir), json, HarnessEventBus())
        }

    /** SessionTreeStore 的宿主出口：恢复链用它读盘上分支投影。 */
    fun treeStore(filesDir: File): SessionTreeStore =
        treeStores.computeIfAbsent(filesDir.absolutePath) { SessionTreeStore(persistence(filesDir), json) }

    // ─── 循环转移挂点（best-effort） ───────────────────────────────────

    suspend fun beginRun(context: Context, sessionId: String): String? = io {
        coordinator(context.filesDir).beginRun(sessionId)
    }

    suspend fun providerIntent(context: Context, operationId: String, round: Int) = io {
        coordinator(context.filesDir).providerIntent(operationId, "round-$round", round, 1, 1)
    }

    suspend fun providerSettled(context: Context, sessionId: String, operationId: String, usage: LLMUsage?, round: Int) = io {
        val coordinator = coordinator(context.filesDir)
        val entity = usage?.let {
            coordinator.usageEntity(
                sessionId = sessionId,
                operationId = operationId,
                entryId = null,
                provider = null,
                modelId = null,
                usage = com.openminis.app.harness.model.ChatUsage(
                    inputTokens = it.inputTokens.toLong(),
                    outputTokens = it.outputTokens.toLong(),
                    cacheReadTokens = (it.cacheReadInputTokens ?: 0).toLong(),
                    cacheWriteTokens = (it.cacheCreationInputTokens ?: 0).toLong(),
                ),
            )
        }
        coordinator.providerSettled(operationId, null, entity, round)
    }

    suspend fun toolIntent(context: Context, operationId: String, call: ToolCall, replay: ReplayPolicy, round: Int) = io {
        coordinator(context.filesDir).toolIntent(
            operationId,
            call,
            json.encodeToString(HarnessMessage.serializer(), call),
            replay,
            round,
        )
    }

    suspend fun toolSettled(context: Context, operationId: String, result: ToolResult, round: Int, toolName: String) = io {
        coordinator(context.filesDir).toolSettled(operationId, result, round, toolName)
    }

    suspend fun suspendRun(context: Context, operationId: String, reason: String) = io {
        coordinator(context.filesDir).suspendOperation(operationId, reason)
    }

    suspend fun finishRun(context: Context, sessionId: String, outcome: String) = io {
        coordinator(context.filesDir).finish(sessionId, outcome)
    }

    /** 盘上分支投影（SessionTreeStore 的消费出口）：当前 lane 叶链的消息视图。 */
    suspend fun durableTranscript(context: Context, sessionId: String): List<HarnessMessage> = io {
        treeStore(context.filesDir).load(sessionId)
    } ?: emptyList()

    /**
     * 崩溃窗口恢复查询：status=running 且 phase=tool_intent 且带 pendingEffect 的操作行，
     * 还原成 ToolCall 消息列表（effectPayloadJson 里是整条消息）。
     */
    suspend fun unsettledIntents(context: Context, sessionId: String): List<ToolCall> = io {
        persistence(context.filesDir).listActiveOperations(sessionId)
            .filter { it.status == OperationStatus.RUNNING.id && it.phase == OperationPhase.TOOL_INTENT.id }
            .mapNotNull { op ->
                val snapshot = runCatching {
                    json.decodeFromString(OperationSnapshot.serializer(), op.stateJson)
                }.getOrNull()
                val payload = snapshot?.effectPayloadJson ?: return@mapNotNull null
                runCatching { json.decodeFromString(HarnessMessage.serializer(), payload) }.getOrNull() as? ToolCall
            }
    } ?: emptyList()

    private suspend fun <T> io(block: suspend () -> T): T? =
        withContext(Dispatchers.IO) { runCatching { block() }.getOrNull() }
}
