package com.openminis.app.harness.operation

import com.openminis.app.harness.model.HarnessEntryEntity
import com.openminis.app.harness.model.HarnessLaneEntity
import com.openminis.app.harness.model.HarnessLaneResultEntity
import com.openminis.app.harness.model.HarnessOperationEntity
import com.openminis.app.harness.model.HarnessUsageEntity

/**
 * Adapted from taixu HarnessRuntimeRepository 的操作面 (GPL-3.0-or-later).
 * https://github.com/wkbin/taixu
 *
 * [OperationCoordinator] 需要的持久化接缝。taixu 里这些方法与 lane 树、
 * 队列、用量查询同处一个 Room 仓储接口；我方按消费方收窄——operation 只
 * 声明它真正调用的 11 个方法，队列走 [com.openminis.app.harness.queue.PromptQueuePersistence]，
 * lane 树将来由 SessionTreeStore 的接缝另行声明。窄接缝 = 宿主可以分块实现，
 * 也逼着每个能力的持久化责任落在自己的文件里。
 *
 * 事务语义由实现保证：accept 族 / begin 族 / settle 族 / finish 族各自是**一个**
 * 原子写（entry + lane + operation 同进同出），Coordinator 依赖这一点来保证崩溃后
 * 不会出现「lane 指向不存在的 operation」或「operation 活着但 lane 没挂上」。
 */
interface OperationRuntimeRepository {

    suspend fun ensureLane(sessionId: String, laneName: String): HarnessLaneEntity

    suspend fun findLane(sessionId: String, laneName: String): HarnessLaneEntity?

    /** 清掉 lane 上的活动操作指针（operation 行已不存在的悬空指针）。 */
    suspend fun clearLaneOperation(sessionId: String, laneName: String)

    suspend fun findOperation(operationId: String): HarnessOperationEntity?

    suspend fun listActiveOperations(sessionId: String): List<HarnessOperationEntity>

    /** 原子：追加用户消息 entry + 挂 operation + 移动 lane 叶子。 */
    suspend fun acceptOperation(
        entry: HarnessEntryEntity,
        lane: HarnessLaneEntity,
        operation: HarnessOperationEntity,
    )

    /** 同 [acceptOperation]，但同时消费一条队列项（出队与入运行同事务）。 */
    suspend fun acceptQueuedOperation(
        queueItemId: String,
        entry: HarnessEntryEntity,
        lane: HarnessLaneEntity,
        operation: HarnessOperationEntity,
    )

    /** 原子：挂 operation + 更新 lane（不追加 entry）。 */
    suspend fun beginOperation(lane: HarnessLaneEntity, operation: HarnessOperationEntity)

    suspend fun saveOperation(operation: HarnessOperationEntity)

    /** 原子：追加效果 entry（可空）+ 用量行（可空）+ 保存 operation + 更新 lane。 */
    suspend fun settleEffect(
        entry: HarnessEntryEntity?,
        usage: HarnessUsageEntity?,
        operation: HarnessOperationEntity,
        lane: HarnessLaneEntity,
    )

    /** 原子：写终态结果行 + 解除 lane 的活动指针（含 faulted 标记）。 */
    suspend fun finishOperation(result: HarnessLaneResultEntity, lane: HarnessLaneEntity)
}
