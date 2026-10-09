package com.openminis.app.harness.runtime

import com.openminis.app.data.model.LLMMessage

/**
 * [T-android-seam-extraction] 批次三接缝三：会话历史。
 *
 * 循环迁移（P0-1 剩余）与 2.4 compaction 本体都需要「读历史 / 追加消息」的
 * 窄口径，而不是整个 ChatViewModel。宿主实现盖在 appendBoundedHistory 之上
 * （带上界裁剪），:harness 侧从此不碰内存列表本身。
 *
 * 有界语义是接缝契约的一部分：append 不是无限增长——宿主负责裁剪，消费方
 * 不能假设 append 过的消息永远可读（被裁掉的历史去 DB 找，那是另一道接缝）。
 */
interface ConversationPort {

    /** 当前活窗口内的历史（有界，正序）。 */
    fun history(): List<LLMMessage>

    /** 追加一条（宿主负责上界裁剪）。 */
    fun append(message: LLMMessage)

    /** 追加一批（同一次裁剪窗口内生效）。 */
    fun appendAll(messages: Collection<LLMMessage>)
}
