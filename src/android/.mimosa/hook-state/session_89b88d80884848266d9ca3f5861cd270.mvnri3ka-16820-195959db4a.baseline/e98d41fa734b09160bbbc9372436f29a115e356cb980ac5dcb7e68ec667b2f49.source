package com.openminis.app.tools

import com.openminis.app.data.model.AgentContentPart

/**
 * Sliding-window compaction for a sub-agent's turn history.
 *
 * The sub-agent loop re-sends the full history to the model on every turn.
 * A few large `file_read` tool results (tens of KB each) will quickly blow
 * past the model's context window, so old tool results are progressively
 * truncated to a head+tail excerpt while the newest ones are kept verbatim
 * (the model almost always needs the freshest tool output in full).
 *
 * Unlike the older [com.openminis.app.offload] path this does NOT spill the
 * original content to disk: a sub-agent is a short-lived self-contained loop
 * with no offload persistence wired in, so dropping the middle is the correct
 * trade-off — the model can always re-read a file if it truly needs it back.
 */
object SubAgentHistoryCompactor {

    /** Chars of an individual tool result allowed to survive verbatim. */
    private const val PER_RESULT_CAP = 12_000

    /** Approximate total chars budget for the whole history before compaction kicks in. */
    private const val TOTAL_BUDGET = 120_000

    /** How many of the most recent tool results are always left untouched. */
    private const val KEEP_FRESH = 4

    /** Head / tail chars kept when a tool result is compacted. */
    private const val HEAD = 1_500
    private const val TAIL = 500

    /**
     * Returns a compacted copy of [history]. The input list is never mutated;
     * messages that need no change are returned by reference (zero-copy fast path).
     */
    fun compact(history: List<com.openminis.app.data.model.LLMMessage>): List<com.openminis.app.data.model.LLMMessage> {
        // Fast path: nothing large enough to bother with.
        if (estimateChars(history) <= TOTAL_BUDGET) return history

        // Collect indices of tool-result messages (USER role carrying ToolResult parts).
        val toolResultIndices = history.indices.filter { idx ->
            val m = history[idx]
            m.role == com.openminis.app.data.model.LLMMessage.Role.USER &&
                m.contentParts.any { it is AgentContentPart.ToolResult }
        }
        val freshSet = toolResultIndices.takeLast(KEEP_FRESH).toHashSet()

        var toolResultOrdinal = 0
        val out = ArrayList<com.openminis.app.data.model.LLMMessage>(history.size)
        for ((idx, msg) in history.withIndex()) {
            if (idx in freshSet ||
                msg.role != com.openminis.app.data.model.LLMMessage.Role.USER ||
                msg.contentParts.none { it is AgentContentPart.ToolResult }
            ) {
                out.add(msg)
                continue
            }
            val newParts = msg.contentParts.map { part ->
                if (part is AgentContentPart.ToolResult && part.content.length > PER_RESULT_CAP) {
                    toolResultOrdinal++
                    part.copy(content = truncateMiddle(part.content, part.name))
                } else {
                    part
                }
            }
            out.add(msg.copy(contentParts = newParts))
        }
        return out
    }

    private fun truncateMiddle(content: String, toolName: String): String {
        if (content.length <= HEAD + TAIL + 200) return content
        val dropped = content.length - HEAD - TAIL
        return buildString(HEAD + TAIL + 120) {
            append(content, 0, HEAD)
            append("\n\n…[sub-agent history compaction: dropped ")
            append(dropped)
            append(" chars of earlier `")
            append(toolName)
            append("` output; re-read the file if you need it back]…\n\n")
            append(content.substring(content.length - TAIL))
        }
    }

    private fun estimateChars(history: List<com.openminis.app.data.model.LLMMessage>): Int {
        var total = 0
        for (m in history) {
            total += m.content.length
            for (p in m.contentParts) {
                if (p is AgentContentPart.ToolResult) total += p.content.length
                if (p is AgentContentPart.Text) total += p.text.length
            }
        }
        return total
    }
}
