package com.openminis.app.tools

/**
 * Pure-logic core of the Supervisor-Worker chat.
 *
 * The supervisor-worker session is a bounded two-seat conversation: the
 * session model (supervisor) instructs, a pinned cheap model (worker)
 * executes with tools, and the supervisor reviews the worker's streamed
 * output. Corrections are messages, not structured payloads. The session
 * ends when the supervisor emits a line-anchored `DONE: <summary>` line
 * that cites acceptance evidence, when the worker-turn cap is hit, or when
 * the user halts.
 *
 * This file owns only the protocol and state rules. It never calls an LLM
 * and never touches Android framework types: the caller drives the machine
 * with the strings each seat produced, which keeps every rule unit-testable
 * on the JVM.
 */
object SupervisorChat {

    /** Line-anchored termination marker, mirroring DiscussionGraph's VERDICT line protocol. */
    val DONE_LINE: Regex = Regex("""(?im)^DONE:\s*(.+)$""")

    /** Minimum length for an acceptance word to count as citable evidence. */
    private const val MIN_EVIDENCE_WORD_LEN = 4

    /**
     * A parsed termination verdict: the supervisor's one-line delivery
     * summary plus the acceptance terms its text actually references.
     */
    data class DoneVerdict(
        val summary: String,
        val evidenceRefs: List<String>,
    )

    /**
     * Extracts the DONE verdict from a supervisor review message.
     *
     * Returns null when the message carries no line-anchored DONE line —
     * including a "DONE:" that appears mid-paragraph, which is prose, not
     * a termination. The verdict's [DoneVerdict.evidenceRefs] lists the
     * acceptance terms referenced (case-insensitively) by the summary.
     */
    fun parseDone(text: String, acceptance: List<String>): DoneVerdict? {
        val match = DONE_LINE.find(text) ?: return null
        val summary = match.groupValues[1].trim()
        if (summary.isEmpty()) return null
        return DoneVerdict(
            summary = summary,
            evidenceRefs = referencedTerms(summary, acceptance),
        )
    }

    /**
     * True when the verdict's summary references no acceptance criterion.
     *
     * A rubber-stamped DONE is rejected once (the supervisor gets one
     * strike to re-review with real evidence); a second one fails the
     * session. Matching is case-insensitive substring on acceptance
     * words of [MIN_EVIDENCE_WORD_LEN] chars or more, so short filler
     * words cannot fake evidence.
     */
    fun isRubberStamp(verdict: DoneVerdict, acceptance: List<String>): Boolean {
        return referencedTerms(verdict.summary, acceptance).isEmpty()
    }

    private fun referencedTerms(summary: String, acceptance: List<String>): List<String> {
        val lower = summary.lowercase()
        return acceptance.flatMap { criterion ->
            criterion.lowercase().split(Regex("\\s+"))
        }
            .filter { it.length >= MIN_EVIDENCE_WORD_LEN }
            .filter { term -> lower.contains(term) }
            .distinct()
    }
}
