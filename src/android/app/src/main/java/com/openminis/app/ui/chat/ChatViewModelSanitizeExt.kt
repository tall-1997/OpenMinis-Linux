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

        // ZCode session-persistence: a visible call that produced no result
        // gets a NEUTRAL interrupted result, never deletion and never a
        // failure. Every emitted call owns exactly one result, and the model
        // must not re-issue it as if it had failed.
        val interrupted = toolUses.filter { it.id in missingIds }.map { use ->
            AgentContentPart.ToolResult(
                id = use.id, name = use.name,
                content = "[Tool execution was interrupted before resume]",
                isError = false,
            )
        }
        Log.w(
            ChatViewModel.TAG,
            "sanitize: pairing ${interrupted.size} interrupted tool_result(s) after history[$i]",
        )
        val next = agentHistory.getOrNull(i + 1)
        if (next != null && next.role == LLMMessage.Role.USER &&
            next.contentParts.any { it is AgentContentPart.ToolResult }
        ) {
            agentHistory[i + 1] = next.copy(contentParts = next.contentParts + interrupted)
        } else {
            agentHistory.add(i + 1, LLMMessage(
                role = LLMMessage.Role.USER, content = "",
                contentParts = interrupted,
            ))
            trimAgentHistory()
        }
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
