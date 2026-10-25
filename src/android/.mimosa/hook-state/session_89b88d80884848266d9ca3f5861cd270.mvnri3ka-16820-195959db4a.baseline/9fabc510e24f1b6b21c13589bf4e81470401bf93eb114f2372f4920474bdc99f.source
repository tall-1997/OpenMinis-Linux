package com.openminis.app.tools

/**
 * [T-plan-board-announcement] The board used to announce itself only while it
 * had items: [AgentPlanStore.boardSnapshotPrompt] returns null on an empty
 * board, and nothing else in the prompt ever said when to create one —
 * `agent_plan`'s own description is mechanics (add / update / remove / list /
 * clear), not timing. So the model had no evidence the feature existed, ran
 * thirty-file changes with no board at all, and because the card renders
 * nothing on an empty board the user watched a composer with no progress UI
 * for the whole run. A chicken-and-egg loop: the board is announced once it
 * exists, and it never comes to exist.
 *
 * This fragment closes the loop from the other side — it is injected exactly
 * when the snapshot is absent because there is nothing on the board yet, and
 * stays out of the way otherwise.
 */
internal object PlanBoardGuidance {

    /**
     * @param boardEmpty true when the session holds no items at all. A board
     *   that still holds finished items gets no fragment: it is on its way out
     *   through [AgentPlanStore.DONE_LINGER_MS], and "create one" would be
     *   wrong while the user is looking at a completed board.
     * @return the fragment, or null when the board should stay unannounced.
     */
    fun fragment(boardEmpty: Boolean): String? {
        if (!boardEmpty) return null
        return """
            <plan_board>
            This chat has no plan board yet. The user watches the board live above the composer, so a multi-step run with no board reads as a frozen UI.
            - Work spanning 3+ steps or several tool rounds: create the board FIRST with agent_plan (op=add), one item per deliverable step.
            - Keep it true as you go: flip the item you are on to active, and to done the moment it lands - in the turn the work happens, not batched at the end.
            - Single-shot answers, chat, and one-command lookups: no board.
            </plan_board>
        """.trimIndent()
    }
}
