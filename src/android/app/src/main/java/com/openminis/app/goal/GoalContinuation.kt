package com.openminis.app.goal

import com.openminis.app.data.db.SessionGoalEntity

/**
 * [T-goal-continuation-round] Structured continuation round, aligned with
 * the ZCode continuation shape: a goal-driven synthetic turn is not user
 * speech, so it is delivered as a `<system-reminder source="goal-continuation">`
 * notice — the same harness framing [ChatViewModel.commitHiddenUserNote]
 * uses for one-shot notices, plus a SOURCE marker the runtime can recognize.
 *
 * Every goal-driven synthetic round (re-affirm, auto-continuation, verifier
 * feed-back) goes through this ONE marker, so the model never mistakes a
 * host-injected nudge for a request to answer, and the loop can tell these
 * rounds apart from ordinary user turns.
 */
object GoalContinuation {

    /** The source attribute every goal-driven synthetic round carries. */
    const val SOURCE = "goal-continuation"

    /** Opening tag of the structured continuation round. */
    const val OPEN_TAG = "<system-reminder source=\"$SOURCE\">"

    private const val CLOSE_TAG = "</system-reminder>"

    /**
     * Build one structured continuation round: the harness framing around
     * the goal's own `<goal_context>` block ([goalContextPrompt]), plus an
     * optional [reason] line (the verifier's verdict on a rejected
     * completion, or any other host note). Rendered verbatim — the reason
     * is the rejection's channel back to the model.
     */
    fun note(goal: SessionGoalEntity, reason: String? = null): String = buildString {
        appendLine(OPEN_TAG)
        append(goalContextPrompt(goal).trimEnd())
        reason?.takeIf { it.isNotBlank() }?.let {
            appendLine()
            appendLine(it.trim())
        }
        appendLine()
        append(CLOSE_TAG)
    }

    /**
     * Recognize a structured continuation round in history. Aligned with the
     * ZCode shape: a startsWith check on the opening tag — the marker is the
     * first thing the round carries, so detection never depends on what the
     * goal's prompt body happens to quote inside.
     */
    fun isGoalContinuation(content: String): Boolean =
        content.trimStart().startsWith(OPEN_TAG)
}
