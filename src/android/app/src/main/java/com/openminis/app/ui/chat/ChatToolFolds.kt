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
 *
 * [FIX-R2-14, review §三 逻辑] A run that nothing non-tool follows - the last
 * items of the list, or one whose next row is the next message's first tool -
 * is NOT offered a synthetic reply: the planner then leaves it unfolded, so a
 * turn that ends in tools (cancelled, or the run simply never got a reply)
 * stays as rows the user can open and compare. Only a run with a real non-tool
 * item after it (barrier / info / text / anything else) folds, as before.
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
        // A run folds only when something non-tool follows it: another item of
        // this or a later message. A following AssistantToolUse belongs to the
        // NEXT message's run (same-message tools were consumed by the loop
        // above), and end-of-list follows nothing - both are "no reply after".
        val followedByNonTool = end + 1 < items.size && items[end + 1] !is FlatChatItem.AssistantToolUse
        val folded = foldRun(head.messageId, runItems, followedByNonTool)
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
 * this run (too few calls, a barrier inside, no non-empty text after it, or
 * [followedByNonTool] false - nothing follows the run to fold against).
 *
 * The planner requires the run to END at non-empty text, so when the list
 * really continues with a non-tool item, a synthetic text block stands in for
 * "the reply that follows": at this layer the run is only ever the tool items
 * between two other items, which is exactly the shape the planner accepts.
 */
private fun foldRun(
    messageId: String,
    runItems: List<FlatChatItem>,
    followedByNonTool: Boolean,
): FlatChatItem.AssistantToolGroup? {
    if (!followedByNonTool) return null
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
