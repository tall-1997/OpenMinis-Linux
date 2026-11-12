package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart

/**
 * [T-tool-execution-ledger] Run-scoped record of every tool call that already
 * produced a result.
 *
 * ZCode's streaming-tool-execution contract is the shape this follows: a tool
 * call is executed once and paired with exactly one result, and a duplicate
 * emission of the same call id "must not create a second core tool call".
 * Retries, stream replays and resume re-requests re-deliver entire responses,
 * so the same id can legitimately arrive twice; re-running the tool would
 * repeat side effects (a second append, a second commit) and then the only
 * remaining defence was a guard that killed the run.
 *
 * Duplicate results are reused, never re-executed. The ledger lives for one
 * [runAgentLoopBody] and is dropped with it.
 */
internal class ToolExecutionLedger {

    private val completed = HashMap<String, AgentContentPart.ToolResult>()

    /** Record a finished result. First writer wins: a replay cannot overwrite it. */
    fun record(id: String, part: AgentContentPart.ToolResult) {
        if (id.isBlank()) return
        completed.putIfAbsent(id, part)
    }

    /** The recorded result for [id], or null when the call has not run yet. */
    fun reuse(id: String): AgentContentPart.ToolResult? =
        if (id.isBlank()) null else completed[id]

    internal fun size(): Int = completed.size
}
