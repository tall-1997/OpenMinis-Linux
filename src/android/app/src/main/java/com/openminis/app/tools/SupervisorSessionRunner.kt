package com.openminis.app.tools

import kotlinx.coroutines.CancellationException

/**
 * Orchestrates one supervisor-worker session
 * (spec: docs/superpowers/specs/2026-10-24-supervisor-worker-loop-design.md §3.2).
 *
 * The seats are injected as suspend lambdas, so this loop is pure
 * orchestration: it never calls an LLM and never touches Android types.
 * The ViewModel extension wires each seat to [SubAgentRunner] with the
 * right kind, tool set, and write-path guard.
 *
 * Loop shape: supervisor speaks → envelope check → worker speaks →
 * review (with acceptance of the last instruction) → repeat until a
 * DONE verdict, a rubber-stamp failure, the worker-turn cap, or a user
 * halt (cancellation). Envelope rejections bounce the supervisor
 * without consuming a worker turn. The user inbox is drained in order
 * ahead of every supervisor turn. The worker seat receives the
 * instruction's write_paths so it can open the matching WritePathGuard
 * scope; the supervisor seat always receives an empty list (review never
 * writes).
 */
object SupervisorSessionRunner {

    /**
     * One seat: speak a turn, return its full text. [writePaths] carries the
     * validated instruction's declared write scope — the worker seat opens
     * its WritePathGuard with it; the supervisor seat ignores it (NO_WRITE).
     */
    fun interface Seat {
        suspend fun speak(user: String, system: String, writePaths: List<String>): String
    }

    sealed class Outcome {
        /** Supervisor emitted an evidence-citing DONE line. */
        data class DONE(val summary: String) : Outcome()

        /** Rubber-stamp failure, worker-turn cap, or seat error. */
        data class FAILED(val reason: String) : Outcome()
    }

    /** Per worker round: round number, instruction digest, report digest. */
    fun interface RoundObserver {
        suspend fun onRound(n: Int, instructionDigest: String, reportDigest: String)
    }

    /** Terminal state hook: run store + UI close. */
    fun interface TerminalObserver {
        suspend fun onTerminal(state: String, summary: String?)
    }

    /** Guard against a supervisor that never converges. */
    private const val MAX_SUPERVISOR_CALLS = 400

    suspend fun run(
        objective: String,
        contextDigest: String,
        maxWorkerTurns: Int,
        supervisorSystem: String,
        supervisor: Seat,
        worker: Seat,
        inbox: SupervisorChat.SupervisorInbox,
        recovery: String? = null,
        onRound: RoundObserver = RoundObserver { _, _, _ -> },
        onTerminal: TerminalObserver = TerminalObserver { _, _ -> },
    ): Outcome {
        val machine = SupervisorChat.TurnMachine(maxWorkerTurns)
        var acceptance: List<String> = emptyList()
        var lastReport = ""
        var lastInstructionDigest = ""
        var bounceNote: String? = null
        var calls = 0

        suspend fun supervisorTurn(prompt: String): String {
            calls++
            if (calls > MAX_SUPERVISOR_CALLS) {
                onTerminal.onTerminal(SupervisorRunStore.STATE_FAILED, "supervisor call budget exceeded")
                throw IllegalStateException("supervisor call budget exceeded")
            }
            return supervisor.speak(prompt, supervisorSystem, emptyList())
        }

        suspend fun terminal(state: String, summary: String?): Outcome {
            onTerminal.onTerminal(state, summary)
            return when (state) {
                SupervisorRunStore.STATE_DONE -> Outcome.DONE(summary.orEmpty())
                else -> Outcome.FAILED(summary ?: state)
            }
        }

        try {
            var prompt = SupervisorPrompts.firstTurn(
                objective, contextDigest, inbox.drain(), recovery,
            )
            while (true) {
                val text = supervisorTurn(prompt)
                val verdict = SupervisorChat.parseDone(text, acceptance)
                if (verdict == null) {
                    when (val check = SupervisorChat.validateInstruction(text)) {
                        is SupervisorChat.InstructionCheck.Rejected -> {
                            bounceNote = SupervisorPrompts.bounceEnvelope(check.reason)
                            prompt = SupervisorPrompts.reviewTurn(
                                objective, lastReport, acceptance, inbox.drain(),
                                machine.workerTurns, maxWorkerTurns, bounceNote,
                            )
                            continue
                        }
                        is SupervisorChat.InstructionCheck.Valid -> {
                            acceptance = check.instruction.acceptance
                            lastInstructionDigest = check.instruction.task.joinToString("; ")
                            val phase = machine.onSupervisorMessage(text, acceptance)
                            if (phase == SupervisorChat.Phase.EXECUTING) {
                                val workerPrompt = buildString {
                                    append("目标：")
                                    append(objective.trim())
                                    append("\n\n")
                                    append(text.trim())
                                }
                                val report = worker.speak(
                                    workerPrompt, supervisorSystem,
                                    check.instruction.writePaths,
                                )
                                machine.onWorkerReport()
                                lastReport = report
                                onRound.onRound(
                                    machine.workerTurns,
                                    lastInstructionDigest,
                                    report,
                                )
                                if (machine.workerTurns >= maxWorkerTurns) {
                                    prompt = SupervisorPrompts.capReached(objective, machine.workerTurns)
                                    continue
                                }
                            }
                            prompt = SupervisorPrompts.reviewTurn(
                                objective, lastReport, acceptance, inbox.drain(),
                                machine.workerTurns, maxWorkerTurns, null,
                            )
                            continue
                        }
                    }
                }
                // A DONE verdict: the machine owns the transition rules.
                val phase = machine.onSupervisorMessage(text, acceptance)
                when (phase) {
                    SupervisorChat.Phase.DONE -> {
                        return terminal(
                            SupervisorRunStore.STATE_DONE,
                            verdict.summary,
                        )
                    }
                    SupervisorChat.Phase.FAILED -> {
                        val reason = if (machine.forceClosed()) {
                            "worker turn budget exhausted (${machine.workerTurns} turns)"
                        } else {
                            "second rubber-stamped DONE without acceptance evidence"
                        }
                        return terminal(SupervisorRunStore.STATE_FAILED, reason)
                    }
                    else -> {
                        // Rubber-stamp strike: re-review with the bounce note.
                        prompt = SupervisorPrompts.reviewTurn(
                            objective, lastReport, acceptance, inbox.drain(),
                            machine.workerTurns, maxWorkerTurns,
                            SupervisorPrompts.bounceRubberStamp(acceptance),
                        )
                    }
                }
            }
        } catch (e: CancellationException) {
            onTerminal.onTerminal(SupervisorRunStore.STATE_USER_HALTED, null)
            throw e
        } catch (e: Exception) {
            onTerminal.onTerminal(SupervisorRunStore.STATE_FAILED, e.message ?: e.javaClass.simpleName)
            return Outcome.FAILED(e.message ?: e.javaClass.simpleName)
        }
    }
}
