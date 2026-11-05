package com.openminis.app.ui.chat

import com.openminis.app.tools.AgentReportStore
import com.openminis.app.tools.DiscussionOutcome
import com.openminis.app.tools.GroupChat

/**
 * [T-discussion-outcome] Records an unresolved group discussion as a question.
 *
 * A split decision used to end as a merged summary — indistinguishable from a
 * unanimous one — and the objections disappeared into prose. Writing the
 * disagreement into the session's report turns it into what the prototype calls
 * an attention request: the stage becomes `needs-answer`, the question is shown
 * to the user, and it rides on every later request until someone answers it.
 *
 * Silent when the group actually agreed: no objection, nothing to ask.
 */
internal fun ChatViewModel.recordDiscussionOutcome(
    lines: List<GroupChat.Line>,
    host: String?,
) {
    val outcome = DiscussionOutcome.of(lines, host)
    if (!outcome.hasDisagreement) return
    runCatching {
        AgentReportStore.record(
            sessionId = activeSessionId,
            context = context,
            status = "progress",
            summary = "group discussion ended with ${outcome.objections.size} open objection(s)",
            question = outcome.question,
        )
    }
}
