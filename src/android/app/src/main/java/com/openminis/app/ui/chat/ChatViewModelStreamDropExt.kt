package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.repository.AssistantDisplayUpdate

/** Only the immediately following accepted text response can finish this partial answer. */
internal data class StreamReplyPrefix(
    val rowId: String,
    val replyBlockIds: List<String>,
    val completedParts: List<AgentContentPart.Text>,
) {
    fun displayUpdate() = AssistantDisplayUpdate(rowId, buildAssistantPartsJson(completedParts, emptyMap()))

    /**
     * Fold the cut-off prose into the continuation's first reply block. Upgrading the
     * old block in place would leave the answer split across two paragraphs mid-sentence.
     */
    fun applyTo(blocks: MutableList<AssistantBlock>) {
        val ids = replyBlockIds.toHashSet()
        if (ids.isEmpty()) return
        val lastPrefixIndex = blocks.indexOfLast { it.id in ids }
        val merged = blocks.filterIndexed { i, b -> i <= lastPrefixIndex && b.id in ids }.joinToString("") { it.content }
        if (merged.isEmpty()) return
        val target = (lastPrefixIndex + 1 until blocks.size).firstOrNull { blocks[it].kind == "text" }
            ?: return
        for (i in 0..lastPrefixIndex) {
            val block = blocks[i]
            if (block.id in ids) blocks[i] = block.copy(content = "")
        }
        val block = blocks[target]
        blocks[target] = block.copy(content = merged + block.content, isProcessText = false)
    }
}
