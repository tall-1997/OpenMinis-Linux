package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

/**
 * [T-harness-note-fold] Markers identifying harness-generated USER messages
 * in agentHistory. These are injected by the host (not typed by the user) to
 * carry goal/objective, recalled memory, session state snapshot, WorkingMemory
 * ledger records, plan retry steering, and once-notice reminders.
 *
 * The provider wire format serializes each as `role: "user"` — which means
 * the model sees them as "the user spoke again" and produces interstitial
 * prose between tool calls. The fold in [ChatViewModelAgentHistoryExt]
 * collects these and merges their content into the nearest real user turn,
 * so the model sees them as part of the original request rather than as
 * separate "turn to speak" invitations.
 *
 * Matching is CONTAINS-based, not startsWith: every committed note carries a
 * `<system-reminder>` framing (see [ChatViewModelHiddenNote.commitHiddenUserNote]),
 * and the compact path prepends a `<context-summary>` prefix onto the first
 * post-anchor user message — a startsWith match on the inner marker fails on
 * both, which made the head-only fold a no-op on device.
 *
 * Deliberately NOT in this set: the steering rounds that follow an assistant
 * turn (goal continuation, sub-agent collect nudge, empty-turn recovery,
 * stream-drop, resume). Those must stay independent user turns — the model
 * ended its turn and the harness is re-prompting it to continue; folding them
 * back into an earlier user turn would reorder the instruction before the
 * model's own turn and break the continue-now semantics.
 */
internal object HarnessNoteMarkers {
    val RECALL = RECALL_NOTE_MARKER
    val GOAL = GOAL_NOTE_MARKER
    val STATE = SessionStateSnapshot.MARKER
    val WORKING_MEMORY = "[working memory"
    /** Real producer prefix: PlanStepRetryRequest.promptLine ("[Tool retry" matched no producer). */
    val PLAN_RETRY = "RETRY REQUESTED by the user"
    /** Assembly artifact of PostAnchorTail.apply — a USER notice inserted mid-list on every dropping request. */
    val DROPPED_ROUNDS = "[context window: "

    /** All markers that identify a harness-generated USER message. */
    val ALL: List<String> = listOf(RECALL, GOAL, STATE, WORKING_MEMORY, PLAN_RETRY, DROPPED_ROUNDS)

    /** True if [content] carries any harness marker (framing-aware, contains-based). */
    fun isHarnessNote(content: String): Boolean {
        val c = content.trimStart()
        if (c.isEmpty()) return false
        return ALL.any { c.contains(it) }
    }

    /**
     * True if [msg] is a foldable harness note. Besides the marker match, the
     * message must be TEXT-ONLY: every committed note is pure text, while
     * tool-result carriers (USER role, ToolResult parts) are structural. The
     * exclusion is what makes the fold IDEMPOTENT — a carrier that already
     * absorbed a note's marker text (fold output) is never re-classified as a
     * note on a later pass.
     */
    fun isUserHarnessNote(msg: LLMMessage): Boolean =
        msg.role == LLMMessage.Role.USER &&
            msg.contentParts.none { it is AgentContentPart.ToolResult || it is AgentContentPart.ToolUse } &&
            isHarnessNote(msg.content)
}
