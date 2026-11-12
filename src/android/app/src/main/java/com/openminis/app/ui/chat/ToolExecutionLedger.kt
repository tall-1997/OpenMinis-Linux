package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart

/**
 * [T-tool-execution-ledger] Run-scoped record of tool calls that already
 * produced a result.
 *
 * ZCode's streaming-tool-execution contract: a call is executed once and owns
 * exactly one result, and a duplicate emission "must not create a second core
 * tool call". The match is (id, name, args): some providers number calls per
 * response (`call_0`, `call_1`), so an id alone can name a DIFFERENT call in a
 * later turn — reusing by id only would inject a stale result for new work.
 */
internal class ToolExecutionLedger {

    private data class Key(val id: String, val name: String, val argsSha: String)

    private val completed = HashMap<Key, AgentContentPart.ToolResult>()

    /** Record a finished result. First writer wins: a replay cannot overwrite it. */
    fun record(id: String, name: String, argsSha: String, part: AgentContentPart.ToolResult) {
        if (id.isBlank()) return
        completed.putIfAbsent(Key(id, name, argsSha), part)
    }

    /** The recorded result for this exact call, or null when it has not run. */
    fun reuse(id: String, name: String, argsSha: String): AgentContentPart.ToolResult? =
        if (id.isBlank()) null else completed[Key(id, name, argsSha)]

    internal fun size(): Int = completed.size
}
