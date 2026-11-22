package com.openminis.app.ui.chat

import android.content.Context
import com.openminis.app.logging.AppLogger
import com.openminis.app.tools.SubAgentBatchRegistry

/**
 * Loop-tail helpers extracted verbatim from runAgentLoop so the loop body
 * stays under its line ceiling: pure string assembly and failure-bounded
 * registry reads over the detached sub-agent batches. No state, no model
 * cost. Goal drift reconciliation lives in [com.openminis.app.goal]
 * (GoalManager.recordUsage / driftNudgeIfOwed) where the Goal row it reads
 * belongs.
 */
internal object AgentLoopNudges {

    /**
     * Extracted verbatim from runAgentLoop: the sub-agent collect reminder
     * text. Pure string assembly over the batch registry.
     */
    internal fun renderSubAgentCollectNudge(pending: List<SubAgentBatchRegistry.Batch>): String = buildString {
        append("<system-reminder>\n")
        append(SubAgentBatchRegistry.renderPending(pending))
        append('\n')
        pending.forEach { b ->
            append("- dispatch ").append(b.id.take(8)).append(": ")
            append(
                if (b.complete) {
                    "finished, reports NOT collected yet"
                } else {
                    "${b.running}/${b.lanes.size} lane(s) still running"
                },
            )
            append(" (").append(b.done).append(" done, ").append(b.failed).append(" failed)\n")
        }
        append(
            "\nDo not end the turn as if nothing had been dispatched. Call check_agent now: " +
                "op=await with timeout_sec to wait for running lanes, op=collect to read the finished " +
                "reports, then fold those results into your answer. If a lane genuinely cannot finish " +
                "in time, say so explicitly — name the dispatch id, state that it is still running, and " +
                "tell the user it can be collected later with check_agent.",
        )
        append("\n</system-reminder>")
    }

    /** Failure-bounded read of the detached sub-agent waves that still owe a result. */
    internal fun pendingSubAgentCollects(sessionId: String, context: Context?): List<SubAgentBatchRegistry.Batch> =
        runCatching { SubAgentBatchRegistry.unfinished(sessionId, context) }
            .getOrElse { e ->
                AppLogger.warning("ChatViewModel", "sub-agent registry read failed: ${e.message}")
                emptyList()
            }
}
