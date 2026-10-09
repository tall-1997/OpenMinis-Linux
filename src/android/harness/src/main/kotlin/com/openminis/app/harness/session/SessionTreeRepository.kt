package com.openminis.app.harness.session

import com.openminis.app.harness.model.HarnessEntryEntity
import com.openminis.app.harness.model.HarnessLaneEntity

/**
 * Adapted from taixu HarnessRuntimeRepository 的会话树面 (GPL-3.0-or-later).
 * https://github.com/wkbin/taixu
 *
 * [SessionTreeStore] 需要的持久化接缝。与 [com.openminis.app.harness.operation.OperationRuntimeRepository]
 * 共享 ensureLane / findLane 两个车道操作——宿主用一个类同时实现两个接口即可，
 * 接缝各自保持窄：谁消费谁声明，持久化责任落在自己的文件里。
 *
 * 语义约定：
 *  - [branch] 返回从根到 leaf 的**正序**链（parent 在前）；
 *  - [branchTail] 是 branch 的有界后缀（limit 条），用于活投影，避免整链解码；
 *  - [appendToLane] 原子地「追加 entry + 移动 lane 叶子」；
 *  - [moveLane] 只动指针（rewind / fork 用），不删任何 entry——被放弃的分支保留。
 */
interface SessionTreeRepository {

    suspend fun ensureLane(sessionId: String, laneName: String): HarnessLaneEntity

    suspend fun findLane(sessionId: String, laneName: String): HarnessLaneEntity?

    suspend fun branch(sessionId: String, leafId: String?): List<HarnessEntryEntity>

    suspend fun branchTail(sessionId: String, leafId: String?, limit: Int): List<HarnessEntryEntity>

    suspend fun appendToLane(sessionId: String, laneName: String, entry: HarnessEntryEntity)

    suspend fun moveLane(sessionId: String, laneName: String, leafId: String?)

    suspend fun findEntry(sessionId: String, entryId: String): HarnessEntryEntity?

    suspend fun deleteSessionData(sessionId: String)
}
