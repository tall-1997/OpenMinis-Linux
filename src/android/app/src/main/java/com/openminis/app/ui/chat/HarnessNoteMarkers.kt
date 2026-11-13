package com.openminis.app.ui.chat

/**
 * [T-harness-note-fold] Markers identifying harness-generated USER messages
 * in agentHistory. These are injected by the host (not typed by the user) to
 * carry goal/objective, recalled memory, session state snapshot, WorkingMemory
 * ledger records, plan retry steering, and once-notice reminders.
 *
 * The provider wire format serializes each as `role: "user"` — which means
 * the model sees them as "the user spoke again" and produces interstitial
 * prose between tool calls. The fold in [ChatViewModelAgentHistoryExt]
 * collects these and merges their content into the prefix of the first real
 * user message, so the model sees them as part of the original request
 * rather than as separate "turn to speak" invitations.
 */
internal object HarnessNoteMarkers {
    val RECALL = RECALL_NOTE_MARKER
    val GOAL = GOAL_NOTE_MARKER
    val STATE = SessionStateSnapshot.MARKER
    val WORKING_MEMORY = "[working memory"
    val PLAN_RETRY = "[Tool retry requested"

    /** All markers that identify a harness-generated USER message. */
    val ALL: List<String> = listOf(RECALL, GOAL, STATE, WORKING_MEMORY, PLAN_RETRY)

    /** True if [content] starts with any harness marker. */
    fun isHarnessNote(content: String): Boolean =
        ALL.any { content.trimStart().startsWith(it) }
}
