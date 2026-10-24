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

    /** A validated instruction message: task bullets, acceptance bullets, declared write paths. */
    data class Instruction(
        val task: List<String>,
        val acceptance: List<String>,
        val writePaths: List<String>,
    )

    /** Result of validating a supervisor instruction message against the envelope contract. */
    sealed class InstructionCheck {
        /** The instruction satisfies the envelope and is safe to hand the worker seat. */
        data class Valid(val instruction: Instruction) : InstructionCheck()

        /** The instruction is bounced back to the supervisor with the contract violation. */
        data class Rejected(val reason: String) : InstructionCheck()
    }

    /**
     * Validates a supervisor instruction message.
     *
     * The envelope reuses the SubAgentBrief section markers: a `## Task`
     * section with at least one bullet and an `## Expected result` section
     * with at least one bullet (the acceptance criteria the next review
     * judges against). Section order is free. A `write_paths:` bullet
     * inside Task is extracted and routed through [WritePathGuard.parse]
     * so the worker seat's guard and the instruction agree on scope.
     */
    fun validateInstruction(raw: String): InstructionCheck {
        val taskBody = sectionBody(raw, SubAgentBrief.TASK)
            ?: return InstructionCheck.Rejected("instruction is missing the ${SubAgentBrief.TASK} section")
        val expectedBody = sectionBody(raw, SubAgentBrief.EXPECTED)
            ?: return InstructionCheck.Rejected("instruction is missing the ${SubAgentBrief.EXPECTED} section")

        val taskBullets = bullets(taskBody)
        if (taskBullets.isEmpty()) {
            return InstructionCheck.Rejected("${SubAgentBrief.TASK} section has no task bullets")
        }
        val acceptanceBullets = bullets(expectedBody)
        if (acceptanceBullets.isEmpty()) {
            return InstructionCheck.Rejected("${SubAgentBrief.EXPECTED} section has no acceptance bullets")
        }

        val writePaths = taskBullets
            .filter { bullet -> bullet.startsWith(WRITE_PATHS_PREFIX, ignoreCase = true) }
            .flatMap { bullet -> WritePathGuard.parse(bullet.substringAfter(':')) }

        return InstructionCheck.Valid(
            Instruction(
                task = taskBullets.filter { bullet -> !bullet.startsWith(WRITE_PATHS_PREFIX, ignoreCase = true) },
                acceptance = acceptanceBullets,
                writePaths = writePaths,
            ),
        )
    }

    private const val WRITE_PATHS_PREFIX = "write_paths:"

    /** Returns the body between a section marker and the next marker, or null when absent. */
    private fun sectionBody(raw: String, marker: String): String? {
        val lines = raw.lines()
        val start = lines.indexOfFirst { line -> line.trim() == marker }
        if (start < 0) return null
        val end = lines.drop(start + 1).indexOfFirst { line ->
            line.trim().startsWith("## ")
        }
        val body = if (end < 0) {
            lines.drop(start + 1)
        } else {
            lines.subList(start + 1, start + 1 + end)
        }
        return body.joinToString("\n").trim().ifEmpty { null }
    }

    private fun bullets(body: String): List<String> = body.lines()
        .map { line -> line.trim() }
        .filter { line -> line.startsWith("- ") }
        .map { line -> line.removePrefix("- ").trim() }
        .filter { line -> line.isNotEmpty() }
}
