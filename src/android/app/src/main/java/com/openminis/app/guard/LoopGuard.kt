package com.openminis.app.guard

/**
 * What one finished guarded call actually did, as reported by the host to
 * [ActionGuard.afterCall]. Exactly one outcome per call:
 *
 *  - [None] - the call failed (`ok == false`). Only the failure ladder sees it; the no-effect
 *    and re-read streaks are left untouched.
 *  - [NoEffect] - the call ran but nothing on the screen moved (e.g. a tap that landed on
 *    nothing, an input that was swallowed). Feeds the [Signal.NoEffect] ladder.
 *  - [Effective] - an action that visibly changed the screen. Breaks both the no-effect and
 *    the re-read streak.
 *  - [ReadUnchanged] - a screen read that returned the same screen as before. Feeds the
 *    [Signal.Reread] ladder.
 *  - [ReadChanged] - a screen read whose screen changed. Breaks the re-read streak.
 */
enum class Outcome { None, Effective, NoEffect, ReadUnchanged, ReadChanged }

/**
 * The four loop signals and their "remind, remind, ..., then stop" ladders. Thresholds are
 * aligned with DeepseekHarness-Android's LoopGuard:
 *
 * | Signal   | Reminders at | Stop at | Counted when                                                            |
 * |----------|--------------|---------|-------------------------------------------------------------------------|
 * | Repeat   | 3, 6         | 12      | same action type + same normalized target within the last 16 calls      |
 * | NoEffect | 3, 6, 9      | 12      | consecutive calls the caller reported as having no visible effect       |
 * | Reread   | 5, 8         | 12      | consecutive screen reads that returned an unchanged screen              |
 * | Failure  | 2, 4         | 8       | consecutive failed calls                                                |
 *
 * Reminders are attached to a result that HAS run; the stop rung ends the session. [Signal.Repeat]
 * is refused proactively - the would-be 12th identical call never executes. The other three
 * ladders are only observable after a call finished, so the rung-th call itself runs, gets the
 * stop text appended, and every later call of the session is refused.
 */
enum class Signal(val ladder: List<Int>) {
    /** Same action type + same normalized target arguments, counted within the most recent [LoopGuard.COUNT_WINDOW] calls. */
    Repeat(listOf(3, 6, 12)),

    /** Consecutive calls the caller marked as having no visible effect. */
    NoEffect(listOf(3, 6, 9, 12)),

    /** Consecutive screen reads that returned an unchanged screen. */
    Reread(listOf(5, 8, 12)),

    /** Consecutive failed calls. */
    Failure(listOf(2, 4, 8)),
    ;

    /** The last rung: reaching it stops the session. */
    val stopAt: Int get() = ladder.last()
}

/**
 * Loop detection for ONE session; [ActionGuard] owns one instance per session key.
 *
 * State, all in-memory (process death resets it - accepted semantics):
 *  - a sliding window of recent target signatures, bounded at [STORAGE_LIMIT] entries (oldest
 *    dropped first), with [Signal.Repeat] counted only within the last [COUNT_WINDOW] entries;
 *  - streak counters for [Signal.NoEffect], [Signal.Reread] and [Signal.Failure];
 *  - a per-signal "already warned at rung X" memory so each rung fires once per streak;
 *  - [stopped] - set once any ladder reaches its stop rung; the facade then refuses every
 *    further call of that session until the user speaks again (facade [ActionGuard.onUserTurn]
 *    calls [reset]).
 *
 * Streaks clear when: an [Outcome.Effective] action breaks the no-effect and re-read streaks,
 * an [Outcome.ReadChanged] read breaks the re-read streak, a successful call breaks the failure
 * streak, or [reset] is called (the user spoke / a new turn started).
 */
class LoopGuard {

    private val recent = ArrayDeque<String>()
    private var noEffects = 0
    private var rereads = 0
    private var failures = 0
    private val warned = HashMap<Signal, Int>()

    /**
     * [T-loop-guard-stop-policy] Whether reaching a stop rung may actually end
     * the session.
     *
     * WHY this is a policy and not a constant: a legitimate high-frequency
     * poll (waiting on a build, a deploy, a long-running job) trips the Repeat
     * ladder while it is doing exactly the right thing, and the old behaviour
     * — refuse the 12th identical call, then refuse everything but reads —
     * left the user with no way forward except typing again. The reference
     * harness keeps its reminder advisory for the same reason.
     *
     * Default stays [STOP_ENABLED_DEFAULT] (true) because an unattended mobile
     * session genuinely needs a hard bound; the setting is what lets a caller
     * opt out per session instead of patching the class.
     */
    @Volatile
    var stopEnabled: Boolean = STOP_ENABLED_DEFAULT

    /** The signal that stopped this session, once any ladder reached its stop rung; null while still running. */
    @Volatile
    var stopped: Signal? = null
        private set

    /**
     * Refusal decision BEFORE a call runs: the session is already stopped (returns that signal),
     * or this call would be the [Signal.Repeat] stop - the would-be 12th identical action, which
     * must never execute. Null means the call may run.
     */
    @Synchronized
    fun check(signature: String?): Signal? {
        stopped?.let { return it }
        if (!stopEnabled) return null
        if (signature != null && countInWindow(signature) + 1 >= Signal.Repeat.stopAt) {
            return Signal.Repeat.also { stopped = it }
        }
        return null
    }

    /**
     * Records a finished call. Returns the signal and the count that reached a NEW rung
     * (null = nothing to tell the model); reaching a stop rung also sets [stopped].
     */
    @Synchronized
    fun record(signature: String?, ok: Boolean, outcome: Outcome): List<Pair<Signal, Int>> {
        // [T-loop-guard-all-rungs] Every signal that crosses a NEW rung in this
        // call must be reported. Returning a single hit meant that when two
        // ladders reached a rung in the same call (the usual shape: a repeat that
        // also had no effect), the second one's `warned` was advanced but its
        // notice was dropped — and because `warned` had moved, that rung could
        // never fire again. The model then never heard about the second cause.
        val hits = ArrayList<Pair<Signal, Int>>(2)
        fun climb(signal: Signal, count: Int) {
            val rung = signal.ladder.lastOrNull { count >= it } ?: return
            if (rung <= (warned[signal] ?: 0)) return
            warned[signal] = rung
            // [T-loop-guard-stop-policy] A disabled stop turns the last rung
            // into one more reminder: the model is told, the session keeps
            // working. The audit line exists so a later “why did this never
            // stop” question has an answer.
            val isStop = rung == signal.stopAt && stopEnabled
            if (rung == signal.stopAt) {
                com.openminis.app.logging.AppLogger.info(
                    "LoopGuard",
                    "stop rung reached: signal=$signal count=$count enforced=$isStop",
                )
            }
            if (isStop) stopped = signal
            hits += signal to count
        }
        if (signature != null) {
            recent.addLast(signature)
            while (recent.size > STORAGE_LIMIT) recent.removeFirst()
            climb(Signal.Repeat, countInWindow(signature))
        }
        failures = if (ok) 0 else failures + 1
        if (failures == 0) warned.remove(Signal.Failure) else climb(Signal.Failure, failures)
        when (outcome) {
            Outcome.NoEffect -> climb(Signal.NoEffect, ++noEffects)
            Outcome.Effective -> {
                noEffects = 0
                rereads = 0
                warned.remove(Signal.NoEffect)
                warned.remove(Signal.Reread)
            }
            Outcome.ReadUnchanged -> climb(Signal.Reread, ++rereads)
            Outcome.ReadChanged -> {
                rereads = 0
                warned.remove(Signal.Reread)
            }
            Outcome.None -> {}
        }
        return hits
    }

    /** The user spoke (new turn or steering): every counter of this session starts again. */
    @Synchronized
    fun reset() {
        recent.clear()
        noEffects = 0
        rereads = 0
        failures = 0
        warned.clear()
        stopped = null
    }

    /** Visibility into the bounded signature store (tests / diagnostics). */
    @Synchronized
    internal fun storedSignatures(): List<String> = recent.toList()

    private fun countInWindow(signature: String): Int =
        recent.toList().takeLast(COUNT_WINDOW).count { it == signature }

    companion object {
        /** [Signal.Repeat] counts occurrences within the most recent 16 recorded calls. */
        const val COUNT_WINDOW = 16

        /**
         * [T-loop-guard-stop-policy] Default for [LoopGuard.stopEnabled].
         * True keeps the shipped hard bound; a caller that wants advisory-only
         * behaviour flips the instance flag.
         */
        const val STOP_ENABLED_DEFAULT = true

        /** Memory bound: at most the last 64 signatures are kept per session; older ones are dropped. */
        const val STORAGE_LIMIT = 64

        /**
         * Model-facing English text for reaching rung [count] of [signal]. Stop texts justify the
         * refusal and redirect the model to report; reminder texts ask for a different approach.
         */
        fun message(signal: Signal, count: Int, stop: Boolean): String {
            val what = when (signal) {
                Signal.Repeat -> "the same action on the same target has been repeated $count times within the recent window"
                Signal.NoEffect -> "$count actions in a row had no visible effect on the screen"
                Signal.Reread -> "the same unchanged screen has been read $count times in a row"
                Signal.Failure -> "$count calls in a row failed on this step"
            }
            return if (stop) {
                "[Loop guard] Refused: $what. Stop retrying and report the situation to the user instead of continuing."
            } else {
                "[Loop guard] Notice: $what. Try a clearly different approach, or report what is blocking you."
            }
        }
    }
}
