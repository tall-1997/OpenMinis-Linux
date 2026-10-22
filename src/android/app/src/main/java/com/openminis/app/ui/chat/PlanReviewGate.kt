package com.openminis.app.ui.chat

import com.openminis.app.tools.ExitPlanModeTool
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Registry of parked plan reviews: one entry per session that has an
 * exit_plan_mode call waiting on the user. The review card (and any future
 * notification / voice surface) reads [pending] and funnels every decision
 * back through [resolve] so the side effects in ChatViewModelPlanExt cannot
 * diverge between surfaces.
 *
 * Session-keyed, not single-slot: a background chat can be parked while the
 * user reviews another one, and each card only shows its own session's entry.
 */
object PlanReviewGate {

    data class PendingReview(
        val sessionId: String,
        val submission: ExitPlanModeTool.Submission,
    )

    private class Entry(
        val review: PendingReview,
        val deferred: CompletableDeferred<ExitPlanModeTool.Decision>,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    /** Live snapshot for the UI; keyed by session id. */
    val pending = MutableStateFlow<Map<String, PendingReview>>(emptyMap())

    private fun publish() {
        pending.value = entries.values.associate { it.review.sessionId to it.review }
    }

    fun register(
        sessionId: String,
        submission: ExitPlanModeTool.Submission,
        deferred: CompletableDeferred<ExitPlanModeTool.Decision>,
    ) {
        entries[sessionId] = Entry(PendingReview(sessionId, submission), deferred)
        publish()
    }

    /** Called from the tool's finally block — parked call ended, card must go. */
    fun unregister(sessionId: String) {
        entries.remove(sessionId)
        publish()
    }

    /**
     * Delivers a user decision to the parked call. False when nothing is
     * parked (stale card tap) or the deferred is already completed — callers
     * treat that as a no-op instead of running decision side effects twice.
     */
    fun resolve(sessionId: String, decision: ExitPlanModeTool.Decision): Boolean {
        val entry = entries[sessionId] ?: return false
        return entry.deferred.complete(decision)
    }
}
