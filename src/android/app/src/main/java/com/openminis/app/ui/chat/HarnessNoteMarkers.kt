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
 * Matching is LEADING ([T-harness-note-leading-match]): every committed note
 * carries a `<system-reminder>` framing (see
 * [ChatViewModelHiddenNote.commitHiddenUserNote]) and the compact path may
 * prepend a `<context-summary>` block, so known framing artifacts are
 * stripped from the head and the marker must then START the remaining text —
 * the old contains-match folded real user questions that merely QUOTED a
 * marker mid-sentence.
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

    /**
     * [T-harness-note-envelope] Authority envelope that PREFIXES every injected
     * memory fragment ([com.openminis.app.data.authority.MemoryAuthority.
     * authorityEnvelope]) — so a recall note's text is
     * `[MEMORY_REFERENCE · use_as:… ]\n## Recalled relevant memories…`.
     * The leading-match rule below strips framing first and then needs the
     * remainder to START with a marker; without this entry the remainder starts
     * with the ENVELOPE, so every recall / 7-day fragment / project-instruction
     * note answered "not a harness note": the raw `<system-reminder>` markup
     * rendered in the user bubble and the fold stopped folding it. The constant
     * is shared with the producer so the two cannot drift again.
     */
    val MEMORY_ENVELOPE = com.openminis.app.data.authority.MemoryAuthority.ENVELOPE_PREFIX

    /** All markers that identify a harness-generated USER message. */
    val ALL: List<String> = listOf(
        RECALL, GOAL, STATE, WORKING_MEMORY, PLAN_RETRY, DROPPED_ROUNDS, MEMORY_ENVELOPE,
    )

    private const val REMINDER_OPEN = "<system-reminder>"
    private const val SUMMARY_OPEN = "<context-summary>"
    private const val SUMMARY_CLOSE = "</context-summary>"

    /**
     * True if [content] carries any harness marker (framing-aware, LEADING).
     *
     * [T-harness-note-leading-match] The match was CONTAINS-based, so a real
     * user message that merely QUOTED a marker mid-sentence ("what does
     * `[working memory]` in your prompt do?") was classified as a note and
     * folded into the previous turn — the user's question lost its own turn
     * boundary. The marker can legitimately sit after framing artifacts
     * (the compact `<context-summary>` block prepended by the v2 path), but
     * never after real prose: strip the known leading artifacts, then
     * require the marker to START the remaining text. The
     * source-attributed steering variant (`<system-reminder source=…>`,
     * deliberately outside this set) does not strip and does not match.
     */
    fun isHarnessNote(content: String): Boolean {
        var c = content.trimStart()
        if (c.isEmpty()) return false
        while (true) {
            c = when {
                c.startsWith(SUMMARY_OPEN) -> {
                    val end = c.indexOf(SUMMARY_CLOSE)
                    if (end < 0) return false
                    c.substring(end + SUMMARY_CLOSE.length).trimStart()
                }
                c.startsWith(REMINDER_OPEN) -> c.removePrefix(REMINDER_OPEN).trimStart()
                c.startsWith(com.openminis.app.data.repository.PreviewText.HOST_NOTE_NO_REPLY_HEAD) ->
                    c.removePrefix(com.openminis.app.data.repository.PreviewText.HOST_NOTE_NO_REPLY_HEAD).trimStart()
                else -> break
            }
        }
        if (c.isEmpty()) return false
        return ALL.any { c.startsWith(it) }
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
