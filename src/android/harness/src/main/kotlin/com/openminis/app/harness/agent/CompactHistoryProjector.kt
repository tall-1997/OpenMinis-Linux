package com.openminis.app.harness.agent

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

/**
 * [T-loop-context-cut] 上下文治理投影的纯半边（循环本体收官刀）。
 *
 * 把 effectiveAgentHistoryUncounted 的三路切片（v2 锚点 / v1 遗留 / 分离
 * 锚点）与回走边界判定收进 harness：输入是普通数据形状（历史 + 摘要 +
 * 标记），输出是发给 provider 的投影。宿主保留：标记/摘要状态的读取、
 * chunk 检索器（selectSummaryChunks）、日志与 detached-once 标记。
 *
 * 决策表逐条对齐原实现：
 * - 无摘要或无标记 → 全量历史原样返回；
 * - v2 锚点可解析 → 回走 N 个用户文本轮（cap 100 条）→ preAnchor 剪枝
 *   （>1000 字符的 tool_result 连带同 id tool_use 一起丢）→ 角色对齐
 *   （剥头部非 user）→ 摘要内联进锚后首个 user 轮（保严格交替）；
 * - v2 锚点被逐出有界窗 → 摘要 + 尾部 DETACHED_TAIL_MESSAGES 条逐字
 *   （摘要与锚无关，丢摘要会让会话永远超预算）；
 * - v1 遗留标记 → summaryHead + firstKept/boundary 起切片，行为原样保留；
 * - 标记完全不可解析 → 全量历史（过度告知优于只发摘要——M-Team 会话
 *   bug 的教训：孤摘要 + 热工具会让模型打转）。
 */
object CompactHistoryProjector {

    /** 宿主标记的接缝形状（CompactMarkerEntity 的纯投影）。 */
    data class Marker(
        val id: String,
        val version: Int,
        val lastCompactedMessageId: String? = null,
        val firstKeptMessageId: String? = null,
        val boundaryMessageId: String? = null,
        val summaryChunks: String? = null,
    )

    /**
     * 有界回走的结果。`priorIdx` 是 preAnchor 的起始索引；null 表示连
     * 包含锚的首个用户轮都会超 maxMessages，preAnchor 应为空。
     */
    data class WalkBackResult(
        val priorIdx: Int?,
        val userTextTurnsFound: Int,
        val messageCount: Int,
        /** "userTextTargetMet" | "messageCapWouldExceed" | "reachedStart" | "invalidAnchor" */
        val stopReason: String,
    )

    /**
     * 从 anchorIdx 向 0 回走，只在**用户消息边界**决策是否纳入下一轮。
     * 停止条件：收满 maxUserTextTurns 个用户文本轮（成功）；纳入下一轮
     * 会超 maxMessages（cap——不把 user/assistant/tool 轮劈半，否则
     * tool_use 会失去配对的 tool_result）；走到索引 0。
     *
     * 携带 tool_result 的 user 消息是轮的**后半**不是轮头（tool result
     * 本身按 USER 角色落库）——在它上面停会把 assistant 的 tool_use 与
     * 它自己的 tool_result 劈开，OpenAI 系会答 400 No tool call found。
     */
    fun walkBackUserTurnsBounded(
        history: List<LLMMessage>,
        anchorIdx: Int,
        maxUserTextTurns: Int,
        maxMessages: Int,
    ): WalkBackResult {
        if (anchorIdx < 0 || anchorIdx >= history.size) {
            return WalkBackResult(null, 0, 0, "invalidAnchor")
        }
        var acceptedPriorIdx: Int? = null
        var acceptedUserTextTurns = 0
        var acceptedMessageCount = 0

        var i = anchorIdx
        while (i >= 0) {
            val msg = history[i]
            if (msg.role != LLMMessage.Role.USER) {
                i -= 1
                continue
            }
            if (msg.contentParts.any { it is AgentContentPart.ToolResult }) {
                i -= 1
                continue
            }
            val candidateMessageCount = anchorIdx - i + 1
            if (candidateMessageCount > maxMessages) {
                return WalkBackResult(
                    priorIdx = acceptedPriorIdx,
                    userTextTurnsFound = acceptedUserTextTurns,
                    messageCount = acceptedMessageCount,
                    stopReason = "messageCapWouldExceed",
                )
            }
            acceptedPriorIdx = i
            acceptedMessageCount = candidateMessageCount
            val hasText = msg.content.isNotBlank() ||
                msg.contentParts.any { it is AgentContentPart.Text && it.text.isNotBlank() }
            if (hasText) {
                acceptedUserTextTurns += 1
                if (acceptedUserTextTurns >= maxUserTextTurns) {
                    return WalkBackResult(
                        priorIdx = acceptedPriorIdx,
                        userTextTurnsFound = acceptedUserTextTurns,
                        messageCount = acceptedMessageCount,
                        stopReason = "userTextTargetMet",
                    )
                }
            }
            i -= 1
        }
        return WalkBackResult(
            priorIdx = acceptedPriorIdx,
            userTextTurnsFound = acceptedUserTextTurns,
            messageCount = acceptedMessageCount,
            stopReason = "reachedStart",
        )
    }

    /**
     * 三路投影。无摘要/无标记 → 全量。chunkRetriever 非空且标记带
     * summaryChunks 时做块检索（检索块是相关性加速器，累积摘要仍是真源
     * ——块池逐出旧块不等于丢历史）。
     */
    fun project(
        history: List<LLMMessage>,
        summary: String?,
        marker: Marker?,
        detachedTailSize: Int,
        keepRecentUserTurns: Int,
        chunkRetriever: ((chunksJson: String, query: String) -> List<String>)? = null,
        log: (String) -> Unit = {},
    ): List<LLMMessage> {
        if (summary.isNullOrBlank() || marker == null) return history.toList()

        val summaryWrappedText = "<context-summary>\n" +
            "The following is a summary of the earlier conversation that was compacted to save context space.\n" +
            "Treat it as background context only. The user's most recent message (below or in the next turn) takes precedence — if it changes the task, the goal, or any numbers/scope, follow the new instruction and do not resume the old plan from this summary. The current persona in the system prompt overrides any voice implied by this summary. Do not re-run discovery (reading memory, scanning skills, re-reading files) unless the new instruction requires it.\n\n" +
            summary +
            "\n</context-summary>"

        val chunkRetrieved: String? = marker.summaryChunks?.let { json ->
            val query = history.lastOrNull { it.role == LLMMessage.Role.USER }?.content.orEmpty()
            chunkRetriever?.invoke(json, query)
                ?.takeIf { it.isNotEmpty() }
                ?.joinToString("\n\n---\n\n")
        }
        val effectiveSummaryWrappedText = if (chunkRetrieved != null && chunkRetrieved != summary) {
            "<context-summary>\n" +
                "The following cumulative summary preserves the earlier conversation. A few relevant compaction excerpts follow as additional detail.\n" +
                "Treat it as background context only. The user's most recent message (below or in the next turn) takes precedence — if it changes the task, the goal, or any numbers/scope, follow the new instruction and do not resume the old plan from this summary.\n\n" +
                summary +
                "\n\n--- relevant compaction excerpts ---\n" +
                chunkRetrieved +
                "\n</context-summary>"
        } else {
            summaryWrappedText
        }

        // ─── v2 markers (id-only anchor model) ─────────────────────────
        if (marker.version >= 2) {
            val anchorId = marker.lastCompactedMessageId?.takeIf { it.isNotEmpty() }
            val anchorIdx = anchorId?.let { id ->
                history.indexOfLast { it.dbMessageId == id }
            } ?: -1
            if (anchorIdx < 0) {
                // 锚点被逐出有界窗：摘要与锚无关，降级为「摘要 + 尾部逐字」，
                // 不能丢摘要（否则会话永远超预算且无法自愈）。
                log("[Compact] marker ${marker.id.take(8)} anchor ${anchorId?.take(8) ?: "nil"} " +
                    "evicted from history(size=${history.size}) - summary+tail injection (tail=$detachedTailSize)")
                return detachedCompactHistory(history, effectiveSummaryWrappedText, detachedTailSize)
            }

            val walkBack = walkBackUserTurnsBounded(
                history = history,
                anchorIdx = anchorIdx,
                maxUserTextTurns = keepRecentUserTurns,
                maxMessages = 100,
            )
            val priorIdxResolved: Int? = walkBack.priorIdx
            val priorIdx = walkBack.priorIdx ?: (anchorIdx + 1) // empty preAnchor sentinel
            if (walkBack.stopReason != "userTextTargetMet") {
                log("[CompactDiag] eAH v2 walkBack stopped: reason=${walkBack.stopReason} priorIdx=$priorIdx userTextTurnsFound=${walkBack.userTextTurnsFound} preAnchorMsgs=${walkBack.messageCount}")
            }

            // PRE-ANCHOR PRUNE：重工具会话里回走 N 轮会拖进几万 token 的
            // tool_result（摘要已覆盖）。丢 >1000 字符的 tool_result 并连带
            // 剥同 id 的 tool_use——模型不能看到悬空配对。
            val preAnchorRaw: List<LLMMessage> =
                if (priorIdx <= anchorIdx) history.subList(priorIdx, anchorIdx + 1).toList()
                else emptyList()

            val droppedToolIds = mutableSetOf<String>()
            var droppedToolResultCount = 0
            for (msg in preAnchorRaw) {
                for (part in msg.contentParts) {
                    if (part is AgentContentPart.ToolResult && part.content.length > 1000) {
                        droppedToolIds.add(part.id)
                        droppedToolResultCount += 1
                    }
                }
            }

            val preAnchorPruned: MutableList<LLMMessage> = ArrayList(preAnchorRaw.size)
            for (msg in preAnchorRaw) {
                if (msg.contentParts.isEmpty()) {
                    preAnchorPruned.add(msg)
                    continue
                }
                val kept = msg.contentParts.filter { part ->
                    when (part) {
                        is AgentContentPart.ToolUse -> !droppedToolIds.contains(part.id)
                        is AgentContentPart.ToolResult -> !droppedToolIds.contains(part.id)
                        else -> true
                    }
                }
                if (kept.isEmpty()) continue // skip empty shells
                preAnchorPruned.add(msg.copy(contentParts = kept))
            }

            if (droppedToolResultCount > 0) {
                log("[CompactDiag] eAH v2 preAnchor prune: dropped $droppedToolResultCount toolResult(>1kc) + paired toolUse, ${preAnchorRaw.size - preAnchorPruned.size} messages emptied; pruned slice=${preAnchorPruned.size}")
            }

            // ROLE ALIGNMENT：API 要求首条消息是 user。cap 落在 assistant 或
            // 剪枝清空头轮之后，剥掉头部非 user。
            while (preAnchorPruned.isNotEmpty() && preAnchorPruned.first().role != LLMMessage.Role.USER) {
                preAnchorPruned.removeAt(0)
            }

            val result = mutableListOf<LLMMessage>()
            result.addAll(preAnchorPruned)

            val postAnchor = if (anchorIdx + 1 < history.size) {
                history.subList(anchorIdx + 1, history.size)
            } else {
                emptyList()
            }

            val preAnchorRawCount = maxOf(0, anchorIdx - priorIdx + 1)
            val priorIdxSource =
                if (priorIdxResolved == null) "fallback=empty(<$keepRecentUserTurns user-text turns before anchor or cap hit)"
                else "userTextWalkBack(N=$keepRecentUserTurns)"
            log("[CompactDiag] eAH v2 slice: priorIdx=$priorIdx anchorIdx=$anchorIdx history.size=${history.size} → preAnchorRaw=$preAnchorRawCount preAnchorSent=${preAnchorPruned.size} postAnchor=${postAnchor.size} summaryChars=${summary.length} priorIdxSource=$priorIdxSource markerId=${marker.id.take(8)}")

            val firstUserOffset = postAnchor.indexOfFirst { it.role == LLMMessage.Role.USER }
            if (firstUserOffset >= 0) {
                if (firstUserOffset > 0) {
                    result.addAll(postAnchor.subList(0, firstUserOffset))
                }
                val target = postAnchor[firstUserOffset]
                val injected = target.copy(
                    content = effectiveSummaryWrappedText + "\n\n" + target.content,
                )
                result.add(injected)
                if (firstUserOffset + 1 < postAnchor.size) {
                    result.addAll(postAnchor.subList(firstUserOffset + 1, postAnchor.size))
                }
            } else {
                result.addAll(postAnchor)
                result.add(LLMMessage(role = LLMMessage.Role.USER, content = effectiveSummaryWrappedText))
            }
            return result
        }

        // ─── v1 (legacy) markers ──────────────────────────────────────
        val summaryHead = LLMMessage(role = LLMMessage.Role.USER, content = effectiveSummaryWrappedText)
        val firstKeptId = (marker.firstKeptMessageId?.takeIf { it.isNotEmpty() })
            ?: (marker.boundaryMessageId?.takeIf { it.isNotEmpty() })

        if (firstKeptId != null) {
            val keepStart = history.indexOfFirst { it.dbMessageId == firstKeptId }
            if (keepStart >= 0) {
                return buildList(history.size - keepStart + 1) {
                    add(summaryHead)
                    addAll(history.subList(keepStart, history.size))
                }
            }
            // Fall through to safety net.
        } else {
            val lcmId = marker.lastCompactedMessageId?.takeIf { it.isNotEmpty() }
            val lcmIdx = lcmId?.let { id ->
                history.indexOfLast { it.dbMessageId == id }
            } ?: -1
            val postCompactStart = lcmIdx + 1
            return buildList(history.size - postCompactStart + 1) {
                add(summaryHead)
                if (postCompactStart < history.size) {
                    addAll(history.subList(postCompactStart, history.size))
                }
            }
        }

        log("[Compact] effectiveAgentHistory: marker ${marker.id.take(8)} unresolvable in history (size=${history.size}); returning full history")
        return history.toList()
    }

    /**
     * 分离锚点的降级投影：摘要内联为尾部首条 user 消息的
     * `<context-summary>` 前缀（角色交替安全），尾部逐字跟随。
     */
    fun detachedCompactHistory(
        history: List<LLMMessage>,
        summaryWrappedText: String,
        tailSize: Int,
    ): List<LLMMessage> {
        val tail = if (history.size > tailSize) {
            history.subList(history.size - tailSize, history.size).toList()
        } else {
            history.toList()
        }
        val start = tail.indexOfFirst { it.role == LLMMessage.Role.USER }
        if (start < 0) {
            return tail + LLMMessage(role = LLMMessage.Role.USER, content = summaryWrappedText)
        }
        val out = ArrayList<LLMMessage>(tail.size - start + 1)
        val first = tail[start]
        out.add(first.copy(content = summaryWrappedText + "\n\n" + first.content))
        out.addAll(tail.subList(start + 1, tail.size))
        return out
    }
}
