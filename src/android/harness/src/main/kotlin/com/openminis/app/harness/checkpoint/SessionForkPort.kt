package com.openminis.app.harness.checkpoint

/**
 * Adapted from taixu HarnessSessionRepository / HarnessRuntimeRepository
 * (GPL-3.0-or-later). https://github.com/wkbin/taixu
 *
 * 会话 fork 的存储端口。
 *
 * 为什么是端口而不是直接依赖 Room 仓储：`harness` 是纯 Kotlin JVM 库
 * （架构策略 pureKotlin 组，import 黑名单禁止 android/androidx），
 * 且宿主与上游的会话模型不同——上游是「entry 树 + lane 游标」，
 * 宿主是「扁平消息表（sessionId + sortOrder）」。端口让同一套 fork 语义
 * 同时承载两种模型，并让测试无需数据库。
 */

/** 会话元数据（harness 视角，不依赖平台实体）。 */
data class SessionForkSource(
    val id: String,
    /** 会话标题；null = 无标题。 */
    val title: String? = null,
    /**
     * 需要继承到新会话的透传字段（模型 id、工作区、审批模式、思考等级等）。
     * harness 不解释这些键值，只原样转交宿主，避免为每个平台字段改一次端口。
     */
    val inherited: Map<String, String> = emptyMap(),
)

/** 会话中的一条消息（harness 视角）。 */
data class SessionForkMessage(
    val id: String,
    /**
     * 父消息 id；树形模型用于重建链，扁平模型可恒为 null。
     * fork 时按前缀顺序重映射，因此实现方只需保证「父在子之前」的返回顺序。
     */
    val parentId: String? = null,
    val createdAt: Long,
    /** 消息内容（宿主自有序列化格式，harness 不解析）。 */
    val payloadJson: String,
)

interface SessionForkPort {
    suspend fun findSession(sessionId: String): SessionForkSource?

    /**
     * 当前分支上的消息，按时间/顺序**升序**返回。
     * 扁平模型（宿主）即该会话全部消息按 sortOrder 升序；
     * 树形模型（上游）即主 lane 从根到叶的链。
     */
    suspend fun branchMessages(sessionId: String): List<SessionForkMessage>

    /**
     * 创建派生会话并继承 [source] 的字段。
     *
     * 会话 id 由宿主生成（Room 主键策略、UUID、服务端 id 都可能），
     * harness 不强加，避免与宿主主键方案冲突。
     *
     * @return 新会话 id。
     */
    suspend fun createForkedSession(source: SessionForkSource, title: String, now: Long): String

    /**
     * 按给定顺序把消息追加到 [sessionId]。
     *
     * 实现方需在同一事务内按序写入，保证派生会话的顺序与源分支前缀一致；
     * 部分写入会让派生会话停在半截对话上（模型看到残缺上下文）。
     */
    suspend fun appendMessages(sessionId: String, messages: List<SessionForkMessage>)
}
