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
 * Unlike the older [com.openminis.app.offload] path this does NOT spill on
 * its own initiative: the caller may hand in a [compact] `spill` hook (the
 * sub-agent runner does, when it knows the session) and every stub then says
 * how to get the folded text back — an embedded spill path when the result
 * carries one, a freshly written spill file for oversized pathless results,
 * or the explicit "re-run this tool" instruction. Without a hook the stubs
 * still name the re-read/re-run recovery instead of dropping the middle
 * silently.
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
     * [T-subagent-history-spill] Size at or above which a stubbed result MUST
     * carry a recovery line (a spill path when one can be produced, otherwise
     * the explicit "re-run this tool" instruction). Keep in lockstep with
     * [ToolResultAging.SPILL_LIMIT_CHARS] / `ToolOutputSpill.LIMIT`: a result
     * this large was worth persisting when it was fresh, so silently dropping
     * its middle from the history without saying how to get it back is a
     * silent loss, not a trim.
     */
    const val SPILL_LIMIT_CHARS = 16_000

    /**
     * Returns a compacted copy of [history]. The input list is never mutated;
     * messages that need no change are returned by reference (zero-copy fast path).
     *
     * [T-subagent-history-spill] [spill] persists an oversized pathless result
     * into a recoverable store and returns the guest path the model can
     * `file_read` (null = nothing could be persisted). It is invoked only for
     * stubbed results at or above [SPILL_LIMIT_CHARS] that carry no embedded
     * spill path; deterministic spillers (content-keyed) make the per-turn
     * re-compaction rewrite the same file instead of accumulating duplicates.
     */
    fun compact(
        history: List<com.openminis.app.data.model.LLMMessage>,
        spill: (name: String, content: String) -> String? = { _, _ -> null },
    ): List<com.openminis.app.data.model.LLMMessage> {
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
                    part.copy(content = truncateMiddle(part.content, part.name, spill))
                } else {
                    part
                }
            }
            out.add(msg.copy(contentParts = newParts))
        }
        return out
    }

    private fun truncateMiddle(
        content: String,
        toolName: String,
        spill: (name: String, content: String) -> String?,
    ): String {
        if (content.length <= HEAD + TAIL + 200) return content
        val dropped = content.length - HEAD - TAIL
        // [T-subagent-history-spill] Mirror ToolResultAging's recoverable-stub
        // pattern: a stub must say how to get the folded text back. An
        // embedded spill path (the unified exit's placeholder, or a workspace
        // path the result itself carries) is reused as-is; otherwise an
        // oversized result is spilled now — or, when nothing can be persisted,
        // the stub admits the only recovery is re-running the tool.
        val path = ToolOutputSpill.parseGuestPath(content)
            ?: if (content.length >= SPILL_LIMIT_CHARS) spill(toolName, content) else null
        return buildString(HEAD + TAIL + 240) {
            append(content, 0, HEAD)
            append("\n\n…[sub-agent history compaction: dropped ")
            append(dropped)
            append(" chars of earlier `")
            append(toolName)
            append("` output; re-read the file if you need it back]…\n\n")
            append(content.substring(content.length - TAIL))
            if (path != null) {
                append("\nfull output: ").append(path)
            } else if (content.length >= SPILL_LIMIT_CHARS) {
                append("\nfull content: re-run this tool")
            }
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
