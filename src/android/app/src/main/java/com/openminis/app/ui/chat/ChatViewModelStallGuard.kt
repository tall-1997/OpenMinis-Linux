package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.logging.AppLogger
import com.openminis.app.util.Digest
import org.json.JSONObject

/**
 * [T-android-loop-stall-guard] Per-run stall accounting for [runAgentLoop]
 * (zcode/codex-style). Three rules, all scoped to a single runAgentLoop —
 * state is cleared at run start and never consults history or persistence:
 *
 *  1. The same dispatch signature (canonical tool names + argument hashes)
 *     repeated N consecutive turns WITH an identical result signature → the
 *     next request assembly injects a one-shot nudge telling the model to
 *     stop repeating and change strategy.
 *  2. The same signature repeated [repeatBreakThreshold] consecutive frozen
 *     turns → break out of the loop and surface a final report quoting the
 *     last tool output, so the user no longer has to interrupt manually.
 *  3. [silentTurnNudgeThreshold] consecutive tool-dispatch turns with zero
 *     assistant text → one-shot "summarize progress" nudge.
 *
 * The result-signature gate is deliberate: poll-style tools (`check_agent`)
 * legitimately repeat identical arguments while a background wave runs, and
 * their results change as lanes progress — a result-aware streak only trips
 * on genuinely frozen repeats ("结果无变化"), matching the philosophy of
 * [com.openminis.app.agent.ToolLoopDetector.getNoProgressStreak] but at
 * per-run consecutive granularity (2/4) instead of session-window (10/30).
 *
 * Pure JVM and thread-confined: the agent loop's dispatch path is the only
 * writer, so no synchronization is needed.
 */
internal class ChatViewModelStallGuard(
    /** Rule 1: consecutive frozen repeats that trigger the one-shot nudge. */
    private val repeatNudgeThreshold: Int = 2,
    /** Rule 2: consecutive frozen repeats that break the loop. */
    private val repeatBreakThreshold: Int = 4,
    /** Rule 3: consecutive silent tool turns that trigger the progress nudge. */
    private val silentTurnNudgeThreshold: Int = 12,
) {

    /** One dispatched tool call reduced to its canonical (name, argsSha) pair. */
    internal data class DispatchedToolCall(val name: String, val argsSha: String)

    /** What the loop should do after recording a dispatched turn. */
    internal data class Decision(
        /**
         * Rule 1 / rule 3 nudge for the NEXT request assembly. One-shot per
         * streak: the loop must clear it after first use.
         */
        val nudge: String?,
        /** Rule 2: the loop must break out of runAgentLoop. */
        val stalled: Boolean,
        /** Rule 2 final user-facing report (assistant text), when [stalled]. */
        val breakReport: String?,
        /** Consecutive frozen-repeat count at decision time (diagnostics). */
        val repeatStreak: Int,
    ) {
        companion object {
            val QUIET = Decision(nudge = null, stalled = false, breakReport = null, repeatStreak = 0)
        }
    }

    /** Canonical signature of one turn's dispatch set: sorted (name:sha) pairs. */
    private var lastDispatchSig: String? = null

    /** Signature of the results those calls produced (frozen-repeat gate). */
    private var lastResultSig: String? = null

    /** Consecutive turns matching BOTH signatures above. */
    private var repeatStreak = 0

    /** Nudge already emitted for the current repeat streak. */
    private var repeatNudgeEmitted = false

    /** Consecutive tool-dispatch turns with no assistant text. */
    private var silentTurnStreak = 0

    /** Rule 3 nudge is emitted at most once per run. */
    private var silentNudgeEmitted = false

    /** Tail of the most recent tool output, for the rule-2 break report. */
    private var lastToolOutputTail: String = ""

    /** Drop all in-flight state. Called once at runAgentLoop start. */
    fun reset() {
        lastDispatchSig = null
        lastResultSig = null
        repeatStreak = 0
        repeatNudgeEmitted = false
        silentTurnStreak = 0
        silentNudgeEmitted = false
        lastToolOutputTail = ""
    }

    /**
     * Record one dispatched turn and decide what the loop does next.
     *
     * @param toolCalls the calls dispatched THIS turn (may be empty for a
     *   text-only turn, which ends the run — recorded as a full reset).
     * @param assistantTextLength length of THIS turn's assistant text.
     * @param resultSha hash of this turn's combined tool results (may be
     *   null when nothing produced output).
     * @param lastToolOutput the last tool result content, tail-quoted by the
     *   rule-2 break report.
     */
    fun recordTurn(
        toolCalls: List<DispatchedToolCall>,
        assistantTextLength: Int,
        resultSha: String?,
        lastToolOutput: String?,
    ): Decision {
        if (toolCalls.isEmpty()) {
            reset()
            return Decision.QUIET
        }

        val dispatchSig = toolCalls
            .map { "${it.name}:${it.argsSha}" }
            .sorted()
            .joinToString("|")

        if (dispatchSig == lastDispatchSig && resultSha != null && resultSha == lastResultSig) {
            repeatStreak += 1
        } else {
            // Different arguments, a different tool mix, or RESULTS THAT
            // CHANGED (progress observed) — the streak restarts from this
            // turn and a fresh streak may nudge again.
            repeatStreak = 1
            repeatNudgeEmitted = false
        }
        lastDispatchSig = dispatchSig
        lastResultSig = resultSha
        lastToolOutputTail = tail(lastToolOutput)

        var nudge: String? = null
        if (repeatStreak >= repeatBreakThreshold) {
            return Decision(
                nudge = null,
                stalled = true,
                breakReport = breakReport(toolCalls, repeatStreak),
                repeatStreak = repeatStreak,
            )
        }
        if (repeatStreak >= repeatNudgeThreshold && !repeatNudgeEmitted) {
            repeatNudgeEmitted = true
            nudge = repeatNudge(toolCalls, repeatStreak)
        }

        if (assistantTextLength == 0) {
            silentTurnStreak += 1
            if (silentTurnStreak >= silentTurnNudgeThreshold && !silentNudgeEmitted) {
                silentNudgeEmitted = true
                // Rule 1's stall nudge is the more specific signal — never
                // overwrite it on a turn where both fire.
                if (nudge == null) nudge = silentNudge(silentTurnStreak)
            }
        } else {
            silentTurnStreak = 0
        }

        return Decision(
            nudge = nudge,
            stalled = false,
            breakReport = null,
            repeatStreak = repeatStreak,
        )
    }

    /** Diagnostic view for logging at the dispatch site. */
    fun snapshot(): String =
        "repeatStreak=$repeatStreak silentTurnStreak=$silentTurnStreak"

    // ─── message builders ───────────────────────────────────────────────────

    private fun repeatNudge(toolCalls: List<DispatchedToolCall>, streak: Int): String {
        val names = toolCalls.map { it.name }.distinct().joinToString(", ")
        return "<system-reminder>\n" +
            "Tool $names with identical arguments has been executed $streak times in a row " +
            "with the same result. Do NOT repeat it: analyze the output, change strategy, " +
            "or report why the goal is unreachable.\n" +
            "</system-reminder>"
    }

    private fun silentNudge(silentTurns: Int): String =
        "<system-reminder>\n" +
            "You have made $silentTurns tool calls without any user-facing text. " +
            "Summarize progress briefly in your next reply.\n" +
            "</system-reminder>"

    private fun breakReport(toolCalls: List<DispatchedToolCall>, streak: Int): String {
        val names = toolCalls.map { it.name }.distinct().joinToString(", ")
        // Model-facing text: English, like every other loop-generated notice.
        return "Stopped: the same command ($names) was executed $streak times in a row with an " +
            "unchanged result. Last output: $lastToolOutputTail"
    }

    private fun tail(output: String?): String {
        if (output.isNullOrEmpty()) return "(no output)"
        val cleaned = output.replace("\r", "").takeLast(BREAK_REPORT_OUTPUT_TAIL_CHARS)
        return cleaned
    }

    internal companion object {
        /** Tail length the rule-2 break report quotes from the last output. */
        internal const val BREAK_REPORT_OUTPUT_TAIL_CHARS = 500

        /**
         * Canonical argument hash for one dispatched call. Mirrors
         * [com.openminis.app.agent.ToolLoopDetector]'s rule of dropping
         * `tool_title`: models routinely counter-suffix the display title
         * ("Read X #1", "#2") on logically identical retries, and letting it
         * into the hash would make every repeat look unique and mute the
         * guard exactly when it is needed.
         */
        internal fun dispatchedToolCall(name: String, args: JSONObject): DispatchedToolCall {
            val canonical = if (args.has("tool_title")) {
                JSONObject(args.toString()).also { it.remove("tool_title") }
            } else {
                args
            }
            return DispatchedToolCall(name, Digest.sha256Hex("$name:${canonical.toString()}"))
        }

        /**
         * Result-signature hash over one turn's assembled tool results.
         *
         * [T-goal-poll-counter-drift] Volatile accounting fields are scrubbed
         * before hashing: usage counters (`tokens=`), wall-clock timers
         * (`elapsed=`) and clock fields change on EVERY call by construction,
         * so a state-read tool whose only varying output is a counter
         * (update_goal op=get → tokens=/elapsed=) would otherwise hash
         * differently on every turn, reset the frozen-repeat streak and mute
         * the guard exactly where it is needed most (the 2026-11-12 7×
         * update_goal furnace burned ~440k tokens under a live guard).
         * Real progress still resets the streak: any content change outside
         * these accounting patterns produces a new hash.
         */
        internal fun resultShaOf(resultParts: List<String>): String? {
            if (resultParts.isEmpty()) return null
            return Digest.sha256Hex(resultParts.joinToString("\u0000") { part -> scrubVolatile(part) })
        }

        /** Accounting fields that tick on every call without carrying progress. */
        private val volatileNoise = listOf(
            Regex("""tokens=\d+"""),
            Regex("""elapsed=\d+ms"""),
            Regex("""timestamp_ms=\d+"""),
            Regex("""nextTriggerMs=\d+"""),
            Regex("""\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}(:\d{2})?(\.\d+)?(Z|[+-]\d{2}:?\d{2})?"""),
        )

        /** Collapse accounting/clock noise so counter drift alone reads as frozen. */
        private fun scrubVolatile(text: String): String =
            volatileNoise.fold(text) { acc, pattern -> acc.replace(pattern, "\u0001") }
    }
}

// ─── Loop-side wiring (mechanical split from ChatViewModelAgentLoopExt) ──────
//
// [T-android-loop-stall-guard] The runAgentLoopBody call sites for the guard
// live here as ChatViewModel extensions so the loop file stays under its size
// ceiling: the record-and-decide step ([enforceStallGuard]), the one-shot
// USER-note rider on request assembly ([applyStallNudge]), and the shared
// live-interruption UI teardown ([dropStreamingEntry], [drainLiveAssistantUi],
// [markLiveInterruptionAndArmResume]) that the stall break, the in-loop
// compact stop, the stream-drop exit and finalizeAtTurnLimit all perform.

/**
 * What [ChatViewModel.runAgentLoopBody] must do after [enforceStallGuard]
 * records a dispatched turn. Mirrors [ChatViewModelStallGuard.Decision]
 * field-for-field; the break's UI teardown stays in the loop itself because
 * it is bound to loop-local state (assistantId, accumulatedText,
 * allToolBlocks, loopResumeEpoch).
 */
internal sealed class StallLoopOutcome {
    /** No rule fired — proceed to the next turn unchanged. */
    internal object Continue : StallLoopOutcome()

    /**
     * Rule 1/3 fired: [message] rides the NEXT request assembly as a one-shot
     * USER note (the loop arms it by assigning its nudge slot — see
     * [applyStallNudge]).
     */
    internal data class Nudge(val message: String) : StallLoopOutcome()

    /**
     * Rule 2 fired: the loop must break out of the run. [report] is the final
     * user-facing text appended to the assistant bubble; [repeatStreak] and
     * [toolsSummary] are break diagnostics (the rule-2 log is emitted inside
     * [enforceStallGuard]).
     */
    internal data class BreakWithReport(
        val report: String,
        val repeatStreak: Int,
        val toolsSummary: String,
    ) : StallLoopOutcome()
}

/**
 * Record THIS dispatched turn after its results are in history and the goal
 * ledger settled, then decide what the loop does next. Extracted verbatim
 * from runAgentLoopBody (mechanical split, zero behavior change): the arm
 * log (rule 1/3) and the break log (rule 2) fire here so all stall
 * diagnostics live in this file — same tags, same messages, same point in
 * the sequence as before the split.
 */
internal fun ChatViewModel.enforceStallGuard(
    guard: ChatViewModelStallGuard,
    turn: Int,
    toolCalls: List<Triple<String, String, JSONObject>>,
    turnText: String,
    resultParts: List<AgentContentPart>,
): StallLoopOutcome {
    val resultContents = resultParts.mapNotNull { part ->
        (part as? AgentContentPart.ToolResult)?.content
    }
    val decision = guard.recordTurn(
        toolCalls = toolCalls.map { (_, name, args) ->
            ChatViewModelStallGuard.dispatchedToolCall(name, args)
        },
        assistantTextLength = turnText.length,
        resultSha = ChatViewModelStallGuard.resultShaOf(resultContents),
        lastToolOutput = resultContents.lastOrNull(),
    )
    if (decision.nudge != null) {
        AppLogger.warning(
            ChatViewModel.TAG_STREAM,
            "[StallGuard] arming one-shot nudge for next request (${guard.snapshot()})",
        )
    }
    return when {
        decision.stalled -> {
            AppLogger.warning(
                ChatViewModel.TAG_STREAM,
                "[StallGuard] frozen repeat streak=${decision.repeatStreak} — breaking run at turn=$turn " +
                    "tools=${toolCalls.joinToString { it.second }}",
            )
            StallLoopOutcome.BreakWithReport(
                report = decision.breakReport.orEmpty(),
                repeatStreak = decision.repeatStreak,
                toolsSummary = toolCalls.joinToString { it.second },
            )
        }
        decision.nudge != null -> StallLoopOutcome.Nudge(decision.nudge)
        else -> StallLoopOutcome.Continue
    }
}

/**
 * One-shot USER-note rider on request assembly (mechanical split): [note] ==
 * null passes [history] through untouched; otherwise a USER message carrying
 * [note] (as content + one [AgentContentPart.Text]) is appended. An assembly
 * artifact only — the note is never persisted into agentHistory. Used for
 * both one-shot notes riding the next request: the [T-stall-resume]
 * continuation note after a mid-stream stall and the rule 1/3 guard nudge.
 */
internal fun ChatViewModel.applyStallNudge(
    history: List<LLMMessage>,
    note: String?,
): List<LLMMessage> {
    if (note == null) return history
    return history + LLMMessage(
        role = LLMMessage.Role.USER,
        content = note,
        contentParts = listOf(AgentContentPart.Text(note)),
    )
}

// ─── Shared live-interruption UI teardown ────────────────────────────────────

/**
 * Drop the streaming side-channel entry for [id] and cancel its trailing
 * flush, if any. The loop's interruption exits run on Dispatchers.IO while
 * these writes must be the last Main-thread word of the turn — see
 * [drainLiveAssistantUi] for the orphan hazard this closes.
 */
internal fun ChatViewModel.dropStreamingEntry(id: String) {
    clearStreamFlushState(id)
    if (_streamingById.value.containsKey(id)) {
        _streamingById.value = _streamingById.value - id
    }
}

/**
 * Finalization tail shared by the agent loop's LIVE interruption exits
 * (stall break, in-loop compact stop, turn-limit finalize): publish the
 * final bubble text with streaming off, then drop the streaming side-channel
 * entry. MUST run on Dispatchers.Main (callers hop).
 *
 * Orphan hazard ([T-android-thinking-indicator-linger] /
 * [T-android-stream-flush-review]): [updateAssistantMessage] drains
 * `_streamingById[assistantId]`, but the loop ran on Dispatchers.IO — a late
 * streaming delta can re-add the side-channel entry AFTER the drain, and
 * since the loop has exited no further isStreaming=false write will ever
 * clear it. mergeStreamingOverlay (ChatScreen) forces isStreaming=true on
 * any message with a side-channel entry, so that orphan keeps the "thinking"
 * row alive forever. [dropStreamingEntry] defensively cancels the trailing
 * flush too (it could otherwise re-add the orphan after we drop it) and
 * removes the entry as the last Main-thread write of this turn.
 */
internal fun ChatViewModel.drainLiveAssistantUi(
    assistantId: String,
    text: String,
    blocks: List<AssistantBlock>,
) {
    updateAssistantMessage(
        assistantId, text, false, blocks,
        isAwaitingModelResponse = false,
    )
    dropStreamingEntry(assistantId)
}

/**
 * [T-android-group-pause-badge-restamp] A LIVE interruption just happened:
 * this is a real entry into the paused state, so the badge's 24h freshness
 * stamp must be refreshed — [markLiveInterruption] cancels any unconsumed
 * re-detection mark left by a prior load so it cannot suppress the re-stamp
 * here — and canResume is armed (epoch-gated) so the user can pick up where
 * the interruption left them.
 */
internal fun ChatViewModel.markLiveInterruptionAndArmResume(fromResumeEpoch: Long) {
    markLiveInterruption()
    raiseCanResume(fromResumeEpoch)
}
