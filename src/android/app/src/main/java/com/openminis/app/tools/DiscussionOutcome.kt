package com.openminis.app.tools

/**
 * [T-discussion-outcome] Turns a finished group discussion into either agreement
 * or one concrete decision for the person who started it.
 *
 * The group chat already parses `VERDICT:` / `OBJECTION:` / `ASK:` lines
 * ([DiscussionGraph]). What it did not do was act on disagreement: the host wrote
 * a merged summary and the objections vanished into prose, so a split decision
 * read exactly like a unanimous one. The prototype's rule is that a concrete
 * decision becomes a structured attention request to a person, asked once, in the
 * conversation that raised it (`prompts/handoff.md:1`, `prompts/dispatch.md:19`).
 *
 * Pure: the caller decides what to do with the outcome. Recording it as a session
 * question is [com.openminis.app.ui.chat.recordDiscussionOutcome]'s job.
 */
object DiscussionOutcome {

    /** Objection text kept in the question; longer ones are clipped. */
    const val MAX_OBJECTION_CHARS = 200

    /** How many objections the question names before summarising the rest. */
    private const val MAX_NAMED = 3

    data class Outcome(
        val objections: List<DiscussionGraph.Statement>,
        val question: String,
    ) {
        val hasDisagreement: Boolean get() = objections.isNotEmpty()
    }

    /**
     * Parses the transcript as a review round.
     *
     * Deliberately [DiscussionGraph.Phase.DESIGN], whose default verdict is
     * [DiscussionGraph.Verdict.NONE]: a speech only counts as an objection when
     * it says so explicitly. Parsing as CRITIQUE would treat every silent speech
     * as an objection and manufacture disagreement where there is none.
     */
    fun of(lines: List<GroupChat.Line>, host: String? = null): Outcome {
        val statements = lines
            .filter { it.text.isNotBlank() }
            .filter { host.isNullOrBlank() || it.speaker != host }
            .map { line ->
                DiscussionGraph.parseStatement(
                    role = line.speaker,
                    phase = DiscussionGraph.Phase.DESIGN,
                    raw = line.text,
                )
            }
        val objections = openFrom(statements)
        return Outcome(objections = objections, question = questionOf(objections))
    }

    /**
     * Latest statement per speaker, keeping only those still objecting.
     *
     * Not [DiscussionGraph.openObjections]: that one only looks at CRITIQUE and
     * REVOTE statements, and a transcript parsed as DESIGN carries neither. The
     * rule is the same one — a later ACCEPT from the same speaker closes their
     * objection — expressed over "whatever that speaker said last".
     */
    private fun openFrom(statements: List<DiscussionGraph.Statement>): List<DiscussionGraph.Statement> {
        val latest = linkedMapOf<String, DiscussionGraph.Statement>()
        for (statement in statements) latest[statement.role] = statement
        return latest.values.filter { it.verdict == DiscussionGraph.Verdict.OBJECT }.toList()
    }

    /** One question, never a list: that is what makes it answerable. */
    fun questionOf(objections: List<DiscussionGraph.Statement>): String {
        if (objections.isEmpty()) return ""
        val named = objections.take(MAX_NAMED).map { statement ->
            val objection = statement.objection
                .replace('\n', ' ')
                .trim()
                .take(MAX_OBJECTION_CHARS)
                .ifBlank { "unspecified objection" }
            "${statement.role}: $objection"
        }
        val body = if (objections.size <= MAX_NAMED) {
            named.joinToString("; ")
        } else {
            named.joinToString("; ") + "; and ${objections.size - MAX_NAMED} more"
        }
        return if (objections.size == 1) {
            "The discussion left one open objection - $body. Address it first, or ship as is?"
        } else {
            "The group did not converge - $body. Which position should ship?"
        }
    }
}
