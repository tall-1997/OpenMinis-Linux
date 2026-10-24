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

    /** Lifecycle phases of one supervisor-worker session. */
    enum class Phase {
        /** Waiting for the supervisor's first instruction. */
        INSTRUCTING,

        /** Worker seat is running the current instruction. */
        EXECUTING,

        /** Supervisor is reviewing the worker's streamed output. */
        REVIEWING,

        /** Supervisor emitted an evidence-backed DONE line. */
        DONE,

        /** Session failed: double rubber-stamp or worker-turn cap. */
        FAILED,

        /** The user pressed stop. */
        USER_HALTED,
    }

    /**
     * Fixed-order turn machine for one supervisor-worker session:
     * supervisor message → worker turn → supervisor review, repeat.
     *
     * The caller drives transitions with the strings each seat produced.
     * A review message carrying an evidence-backed DONE line ends the
     * session; a rubber-stamped DONE earns one strike and a second one
     * fails it. A non-DONE supervisor message at the worker-turn cap
     * force-closes the session instead of starting another worker round.
     * Terminal phases absorb further input.
     */
    class TurnMachine(val maxWorkerTurns: Int) {

        /** Current phase; terminal phases never leave. */
        var phase: Phase = Phase.INSTRUCTING
            private set

        /** Worker turns consumed so far. */
        var workerTurns: Int = 0
            private set

        /** Rubber-stamp DONE rejections so far; two fails the session. */
        var rubberStampStrikes: Int = 0
            private set

        private var capClosed = false

        /** True when the session was force-closed by the worker-turn cap (not by rubber-stamp failure). */
        fun forceClosed(): Boolean = capClosed

        /**
         * Feeds a supervisor message.
         *
         * A valid DONE verdict transitions to [Phase.DONE] from any live
         * phase. A rubber-stamped DONE stays in review with a strike; the
         * second one fails. Any other message starts a worker turn —
         * unless the cap is exhausted, which force-closes the session.
         */
        fun onSupervisorMessage(text: String, acceptance: List<String>): Phase {
            if (phase == Phase.DONE || phase == Phase.FAILED || phase == Phase.USER_HALTED) {
                return phase
            }
            val verdict = parseDone(text, acceptance)
            if (verdict != null) {
                if (!isRubberStamp(verdict, acceptance)) {
                    phase = Phase.DONE
                    return phase
                }
                rubberStampStrikes += 1
                if (rubberStampStrikes >= MAX_RUBBER_STAMP_STRIKES) {
                    phase = Phase.FAILED
                } else {
                    phase = Phase.REVIEWING
                }
                return phase
            }
            if (workerTurns >= maxWorkerTurns) {
                capClosed = true
                phase = Phase.FAILED
                return phase
            }
            workerTurns += 1
            phase = Phase.EXECUTING
            return phase
        }

        /** Feeds the worker's report; only an executing turn moves to review. */
        fun onWorkerReport(): Phase {
            if (phase == Phase.EXECUTING) {
                phase = Phase.REVIEWING
            }
            return phase
        }

        /** User pressed stop; any live phase closes immediately. */
        fun onUserHalt(): Phase {
            if (phase != Phase.DONE && phase != Phase.FAILED) {
                phase = Phase.USER_HALTED
            }
            return phase
        }
    }

    private const val MAX_RUBBER_STAMP_STRIKES = 2

    /**
     * Inbox for user interjections in a supervisor-worker session.
     *
     * The user is the third speaker: their messages land here and are
     * drained exactly once, in order, ahead of the next supervisor turn.
     * The supervisor — never the user directly — decides how to fold
     * them into the next instruction, keeping the chain of command
     * single. Thread-safe: pushes can arrive from the UI thread while
     * the session loop drains on its own coroutine.
     */
    class SupervisorInbox {

        private val pending = mutableListOf<String>()

        /** Current number of undelivered user messages. */
        val size: Int
            get() = synchronized(pending) { pending.size }

        /** Queues one user message; blank messages are dropped. */
        fun push(message: String) {
            val trimmed = message.trim()
            if (trimmed.isEmpty()) return
            synchronized(pending) { pending.add(trimmed) }
        }

        /** Takes all pending messages in arrival order; a second call returns empty. */
        fun drain(): List<String> = synchronized(pending) {
            val out = pending.toList()
            pending.clear()
            out
        }
    }



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
