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

    /** A winding-down run must not settle its replacement, even in the same chat. */
    fun closeOpen(context: Context, runId: String): Boolean {
        val closed = GenerationRunStore.finish(context, runId, ok = false)
        if (closed) AppLogger.info(TAG, "[T-generation-run] closed unfinished run $runId")
        return closed
    }
}
