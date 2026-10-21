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

    /** 发送前的入口：只改写消息里的工具 ID，其余字段原样保留。 */
    fun normalizeMessages(messages: List<LLMMessage>): List<LLMMessage> {
        val normalized = normalize(messages.map { it.contentParts })
        if (normalized == messages.map { it.contentParts }) return messages
        return messages.mapIndexed { index, message ->
            message.copy(contentParts = normalized[index])
        }
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
