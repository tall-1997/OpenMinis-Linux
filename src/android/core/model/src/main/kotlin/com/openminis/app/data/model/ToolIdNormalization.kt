package com.openminis.app.data.model

/**
 * 把历史里的工具 ID 归一成所有 API 都接受的形式。
 *
 * 根因：sanitizeToolId 之前只在发往 Anthropic 的出口套用，历史里存的是原始 id。
 * 会话从 OpenAI Responses（id 形如 `call_abc|fc_def`）切到 Anthropic 后，
 * tool_use 被清洗而配对的 tool_result 仍带原始 id，两端对不上，请求被 400 拒绝。
 *
 * 归一化在发送前对整段历史做一次：同一个原始 id 处处换成同一个清洗结果，
 * 配对关系因此保持；清洗后发生碰撞的（`a|b` 与 `a-b`）追加序号保持唯一。
 * 清洗对已合规的 id 是恒等的，所以重复执行无副作用。
 */
object ToolIdNormalization {

    /**
     * 发送前的入口：改写消息里的工具 ID，并把相邻的同角色 user 消息合并成一条。
     *
     * 合并的根因不是样式问题：宿主写入的一次性通知（召回、目标、计划重试）是
     * user 角色，紧跟在工具结果的 user 载体之后。有的 provider 要求 user /
     * assistant 交替，两条相邻 user 会被 400 拒绝或语义错位；合并成一条
     * user（工具结果在前、通知文本在后）是所有 provider 都接受的形式，
     * 也正是 ZCode 把提醒并入 tool result 同一消息的做法。
     */
    fun normalizeMessages(messages: List<LLMMessage>): List<LLMMessage> {
        val normalized = normalize(messages.map { it.contentParts })
        val idFixed = if (normalized == messages.map { it.contentParts }) {
            messages
        } else {
            messages.mapIndexed { index, message ->
                message.copy(contentParts = normalized[index])
            }
        }
        return mergeConsecutiveUserTurns(idFixed)
    }

    /**
     * Collapse a following USER message into the previous USER message when the
     * follower is TEXT-ONLY (a host notice or a queued prompt). Tool-result
     * carriers stay their own message: merging a result group into a text turn
     * would reorder tool blocks, which providers validate. A blank follower is
     * dropped rather than appended as an empty text block.
     */
    fun mergeConsecutiveUserTurns(messages: List<LLMMessage>): List<LLMMessage> {
        if (messages.size < 2) return messages
        val out = ArrayList<LLMMessage>(messages.size)
        for (message in messages) {
            val previous = out.lastOrNull()
            val mergeable = previous != null &&
                previous.role == LLMMessage.Role.USER &&
                message.role == LLMMessage.Role.USER &&
                message.contentParts.none {
                    it is AgentContentPart.ToolUse || it is AgentContentPart.ToolResult
                }
            val hasPayload = message.content.isNotBlank() || message.contentParts.any {
                it !is AgentContentPart.Text || it.text.isNotBlank()
            }
            if (mergeable && !hasPayload) continue
            if (mergeable) {
                val appended = message.contentParts.ifEmpty {
                    listOf(AgentContentPart.Text(message.content))
                }
                val combinedText = listOf(previous!!.content, message.content)
                    .filter { it.isNotBlank() }
                    .joinToString("\n\n")
                out[out.lastIndex] = previous.copy(
                    content = combinedText,
                    contentParts = previous.contentParts + appended,
                )
                continue
            }
            out += message
        }
        return out
    }

    fun normalize(history: List<List<AgentContentPart>>): List<List<AgentContentPart>> {
        val assigned = mutableMapOf<String, String>()
        val used = mutableSetOf<String>()
        for (parts in history) {
            for (part in parts) {
                val raw = rawId(part) ?: continue
                if (raw in assigned) continue
                val base = sanitizeToolId(raw).ifEmpty { "tool" }
                var candidate = base
                var suffix = 2
                while (candidate in used) {
                    candidate = "$base-$suffix"
                    suffix++
                }
                assigned[raw] = candidate
                used.add(candidate)
            }
        }
        if (assigned.all { (raw, clean) -> raw == clean }) return history
        return history.map { parts ->
            parts.map { part -> rewrite(part, assigned) }
        }
    }

    private fun rawId(part: AgentContentPart): String? = when (part) {
        is AgentContentPart.ToolUse -> part.id
        is AgentContentPart.ToolResult -> part.id
        else -> null
    }

    private fun rewrite(
        part: AgentContentPart,
        assigned: Map<String, String>,
    ): AgentContentPart = when (part) {
        is AgentContentPart.ToolUse ->
            assigned[part.id]?.let { part.copy(id = it) } ?: part
        is AgentContentPart.ToolResult ->
            assigned[part.id]?.let { part.copy(id = it) } ?: part
        else -> part
    }
}
