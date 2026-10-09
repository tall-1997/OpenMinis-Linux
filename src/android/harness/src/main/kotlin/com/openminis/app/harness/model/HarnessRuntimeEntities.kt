package com.openminis.app.harness.model

import kotlinx.serialization.Serializable

/**
 * Adapted from taixu core/database 实体 (GPL-3.0-or-later).
 * https://github.com/wkbin/taixu
 *
 * 运行时持久化形状（lane / entry / operation / usage / lane result）。
 * taixu 里它们是 Room `@Entity`；我方 `:harness` 是纯 JVM 库（禁 androidx），
 * 所以去掉注解、只保留字段形状——Room 实现留在 `:app`，按这些形状建表或
 * 用文件持久化均可（见 queue 的 FilePromptQueuePersistence 先例）。
 *
 * `sequence` 在 taixu 是 Room 自增主键；这里保留为普通字段（默认 0），
 * 让未来 Room 实现能一比一映射，纯 JVM 侧不依赖它排序。
 */

/** 不可变会话树的节点。entryType=message 的节点 payload 是 HarnessMessage。 */
@Serializable
data class HarnessEntryEntity(
    val sequence: Long = 0,
    val id: String,
    val sessionId: String,
    val parentId: String?,
    val createdAt: Long,
    /** message | compaction | branch_summary | custom */
    val entryType: String,
    /** user | assistant | tool_call | tool_result，或应用自定义类型。 */
    val customType: String? = null,
    val payloadJson: String,
)

/** 会话内的一条并行轨道（主线 / 子智能体 lane）。leafId 指向活动分支叶节点。 */
@Serializable
data class HarnessLaneEntity(
    val sessionId: String,
    val name: String,
    val leafId: String?,
    val currentOperationId: String? = null,
    val modelId: String? = null,
    val thinkingLevel: String = "off",
    val faulted: Boolean = false,
    val updatedAt: Long,
)

/** 最近一次终态结果；完成的操作不留活动程序状态行。 */
@Serializable
data class HarnessLaneResultEntity(
    val sessionId: String,
    val laneName: String,
    val operationId: String,
    val outcome: String,
    val finalEntryId: String?,
    val detailsJson: String? = null,
    val completedAt: Long,
)

/** 一次 run / compaction / navigation 的持久化程序计数器。stateJson 永远是完整快照。 */
@Serializable
data class HarnessOperationEntity(
    val id: String,
    val sessionId: String,
    val laneName: String,
    val kind: String,
    val status: String,
    val phase: String,
    val startedAt: Long,
    val updatedAt: Long,
    val startLeafId: String?,
    val stateJson: String,
    val pendingEffectKind: String? = null,
    val pendingEffectId: String? = null,
    /** safe | never */
    val replayPolicy: String? = null,
    val attempt: Int = 0,
)

/** 用量台账行（append-only）。 */
@Serializable
data class HarnessUsageEntity(
    val sequence: Long = 0,
    val id: String,
    val sessionId: String,
    val operationId: String?,
    val entryId: String?,
    val provider: String?,
    val modelId: String?,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val reasoningTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    val estimatedCostUsd: Double? = null,
    val adjustment: Boolean = false,
    val detailsJson: String? = null,
    val createdAt: Long,
)
