package com.openminis.app.ui.chat

/**
 * [T-port-fold-tools] Turns a message's flat items into the list with folded
 * tool runs (upstream `applyToolFolds`).
 *
 * Upstream folds at the LIST level: the message keeps its blocks and their
 * order, and only the rendered list swaps a run of consecutive tool calls for
 * one "Ran N tools" row. The local mechanism this replaces hid blocks behind a
 * round card, which is why folded turns lost their place in the transcript.
 *
 * Boundaries come straight from [ToolFoldPlanner]: a run needs at least
 * [ToolFoldPlanner.MINIMUM_TOOL_COUNT] finished tools and must be followed by
 * non-empty text, and barriers (info / unfinished tool / sub-agent / user-ask)
 * cut it. Items the planner does not fold are returned untouched, in order.
 */
internal fun applyToolFolds(items: List<FlatChatItem>): List<FlatChatItem> {
    if (items.size < ToolFoldPlanner.MINIMUM_TOOL_COUNT) return items
    val out = ArrayList<FlatChatItem>(items.size)
    var i = 0
    while (i < items.size) {
        val head = items[i] as? FlatChatItem.AssistantToolUse
        if (head == null) { out.add(items[i]); i++; continue }
        // Longest run of consecutive tool items belonging to the same message.
        var end = i
        while (end + 1 < items.size) {
            val next = items[end + 1] as? FlatChatItem.AssistantToolUse ?: break
            if (next.messageId != head.messageId) break
            end++
        }
        val runItems = items.subList(i, end + 1)
        val folded = foldRun(head.messageId, runItems)
        if (folded == null) {
            out.addAll(runItems)
        } else {
            out.add(folded)
        }
        i = end + 1
    }
    return out
}

/**
 * The single group item for [runItems], or null when the planner would not fold
 * this run (too few calls, a barrier inside, or no non-empty text after it).
 *
 * The planner requires the run to END at non-empty text, so a synthetic text
 * block stands in for "the reply that follows": at this layer the run is only
 * ever the tool items between two other items, which is exactly the shape the
 * planner accepts.
 */
private fun foldRun(messageId: String, runItems: List<FlatChatItem>): FlatChatItem.AssistantToolGroup? {
    if (runItems.size < ToolFoldPlanner.MINIMUM_TOOL_COUNT) return null
    val blocks = runItems.mapNotNull { (it as? FlatChatItem.AssistantToolUse)?.block } +
        AssistantBlock(id = "__fold_tail__", kind = "text", content = "tail")
    val runs = ToolFoldPlanner.foldedRuns(blocks)
    if (runs.size != 1) return null
    val members = runs[0].memberIds.filter { it != "__fold_tail__" }
    if (members.size != runItems.size) return null
    var failed = 0
    var cancelled = 0
    for (item in runItems) {
        val block = (item as? FlatChatItem.AssistantToolUse)?.block ?: continue
        when (block.toolStatus) {
            ToolBlockStatus.FAILED, ToolBlockStatus.TIMEOUT -> failed++
            ToolBlockStatus.CANCELLED -> cancelled++
            else -> Unit
        }
    }
    return FlatChatItem.AssistantToolGroup(
        messageId = messageId,
        runKey = runs[0].key,
        memberIds = members,
        toolCount = members.size,
        failedCount = failed,
        cancelledCount = cancelled,
        durationMs = runs[0].durationMs,
        expanded = false,
    )
}
