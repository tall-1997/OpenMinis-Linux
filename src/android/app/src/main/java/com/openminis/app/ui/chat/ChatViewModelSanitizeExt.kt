package com.openminis.app.ui.chat

import android.util.Log
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

/**
 * Sanitize agentHistory before each API call to ensure tool_use/tool_result pairing.
 * Mirrors iOS AIChatViewModel pre-API validation.
 *
 * Ensures: every assistant message with tool_use is immediately followed by a user
 * message containing the matching tool_result(s). Handles:
 * - Duplicate tool IDs across messages (from provider fallback/retry)
 * - Orphaned tool_use without any tool_result
 * - Orphaned tool_result without matching tool_use
 * - Assistant text after tool_use in the same message (Anthropic rejects this)
 */
internal fun ChatViewModel.sanitizeAgentHistory() {
    // Walk through history sequentially, checking each assistant message.
    // For each assistant message with tool_use blocks, verify the NEXT message
    // is a user message with matching tool_result blocks. If not, inject them.
    var i = 0
    while (i < agentHistory.size) {
        val msg = agentHistory[i]
        if (msg.role != LLMMessage.Role.ASSISTANT) { i++; continue }

        val toolUses = msg.contentParts.filterIsInstance<AgentContentPart.ToolUse>()
        if (toolUses.isEmpty()) { i++; continue }

        val toolUseIds = toolUses.map { it.id }.toSet()

        // [T-sanitize-orphan-removal] Pair against the WHOLE remaining history,
        // not just the next row: a compaction divider (a system message) can sit
        // between the tool_use and its tool_result, and scanning only the next
        // message would call a correctly paired call an orphan.
        val resultIdsAhead = HashSet<String>()
        for (j in (i + 1)..agentHistory.lastIndex) {
            for (part in agentHistory[j].contentParts) {
                if (part is AgentContentPart.ToolResult) resultIdsAhead.add(part.id)
            }
        }

        val missingIds = toolUseIds - resultIdsAhead
        if (missingIds.isEmpty()) { i++; continue }

        // A tool_use with no result this late is a history casualty (trim,
        // abort, crash), not a tool failure: normal execution appends the
        // result before the next request. The old repair fabricated an
        // "interrupted by an unexpected error" result — the model read its call
        // as FAILED and re-issued it on the next turn (the reported "repeats
        // itself and does no work"), which is also why dropOrphanedToolParts
        // stopped synthesising that placeholder. Remove the orphaned use
        // instead: the assistant row keeps its text, and the provider still
        // receives a fully paired transcript.
        val keptParts = msg.contentParts.filterNot {
            it is AgentContentPart.ToolUse && it.id in missingIds
        }
        Log.w(
            ChatViewModel.TAG,
            "sanitize: removing ${msg.contentParts.size - keptParts.size} orphaned tool_use(s) from history[$i]",
        )
        if (keptParts.isEmpty() && msg.content.isBlank()) {
            agentHistory.removeAt(i)
            continue // the next message shifted into this index; do not advance
        }
        agentHistory[i] = msg.copy(contentParts = keptParts)
        i++
    }

    // Remove orphaned tool_results (result IDs not found in any tool_use)
    val allToolUseIds = agentHistory.flatMap { it.contentParts }
        .filterIsInstance<AgentContentPart.ToolUse>().map { it.id }.toSet()
    // [T-android-agenthistory-cme] agentHistory is a CopyOnWriteArrayList —
    // its iterators are snapshot views and cannot remove/set. A reversed
    // pass over a toList() snapshot visits each element exactly once and
    // keeps removeAt/set index-stable (a removal only shifts indices we
    // have already visited). The bounds guard is crash-armor for a
    // concurrent clear(), not an expected path.
    val snapshot = agentHistory.toList()
    for (i in snapshot.indices.reversed()) {
        val msg = snapshot[i]
        if (msg.role != LLMMessage.Role.USER) continue
        val cleaned = msg.contentParts.filter { part ->
            part !is AgentContentPart.ToolResult || part.id in allToolUseIds
        }
        if (cleaned.isEmpty() && msg.content.isBlank()) {
            if (i < agentHistory.size) agentHistory.removeAt(i)
        } else if (cleaned.size < msg.contentParts.size) {
            if (i < agentHistory.size) agentHistory[i] = msg.copy(contentParts = cleaned)
        }
    }
}
