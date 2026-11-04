package com.openminis.app.agent

import android.content.Context
import com.openminis.app.logging.AppLogger

/**
 * [T-generation-run] The only place the generation ledger is written from.
 *
 * The ledger answers one question at launch time: "did the previous process die
 * in the middle of a model run?" It can only answer that honestly if every run
 * that *ended* — for any reason — gets a verdict. Three ways a run can end:
 *
 *  - it completed → [succeeded]
 *  - it stopped cleanly but not successfully (turn cap) → [failed]
 *  - it threw → the exception is thrown straight out of the loop body, so
 *    neither of the above runs. That is what [closeOpen] exists for.
 *
 * [closeOpen] is the safety net, called from the wrapper that every run passes
 * through. It only ever touches rows still marked RUNNING, so on the two clean
 * paths it is a no-op by construction rather than by bookkeeping.
 *
 * Deliberately non-suspending: it runs from a `finally` that may already be in a
 * cancelled coroutine, where any suspension point would throw before the write.
 */
internal object GenerationRunLifecycle {

    private const val TAG = "GenerationRun"

    /** Opens a ledger row and returns its id. */
    fun start(
        context: Context,
        sessionId: String,
        modelId: String,
        turnAnchor: String = "",
    ): String = GenerationRunStore.start(context, sessionId, modelId, turnAnchor)

    /** The run completed normally. */
    fun succeeded(context: Context, runId: String) {
        GenerationRunStore.finish(context, runId, ok = true)
    }

    /** The run ended without completing (turn cap, abandoned loop). */
    fun failed(context: Context, runId: String) {
        GenerationRunStore.finish(context, runId, ok = false)
    }

    /**
     * Closes any run for [sessionIds] that is still RUNNING, as FAILED.
     *
     * Called from the run wrapper's `finally`, which is the one point every exit
     * — return, throw, cancellation — passes through. Returns how many rows were
     * closed, which is 0 on every path that already recorded its own verdict.
     *
     * The session set is captured *before* the run on purpose: a mid-run session
     * switch must not redirect the write to a chat the ledger never described.
     */
    fun closeOpen(context: Context, sessionIds: Collection<String>): Int {
        if (sessionIds.isEmpty()) return 0
        val wanted = sessionIds.toSet()
        var closed = 0
        for (run in GenerationRunStore.running(context)) {
            if (run.sessionId !in wanted) continue
            GenerationRunStore.finish(context, run.runId, ok = false)
            closed += 1
            AppLogger.warning(
                TAG,
                "[T-generation-run] run ${run.runId} escaped without a verdict " +
                    "(session=${run.sessionId}, model=${run.modelId}) — recorded FAILED so the " +
                    "launch scan keeps ABANDONED for genuine process death",
            )
        }
        return closed
    }
}
