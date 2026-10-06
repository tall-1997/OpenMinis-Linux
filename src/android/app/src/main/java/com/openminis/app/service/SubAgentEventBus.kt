package com.openminis.app.service

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter

/**
 * [T-event-bus] Sub-agent lifecycle event bus.
 *
 * Why this exists next to [SubAgentActivityTracker]: the tracker is the UI
 * roster — a live snapshot that DROPS a member the moment it finishes, so
 * anything that was not looking at that exact instant never learns the run
 * existed. The bus is the durable programmatic channel: every lifecycle
 * transition is published here, late subscribers catch up from a bounded
 * per-session ring ([recentFor]), and components (status overlays,
 * foreground-service notifications, session diagnostics) subscribe with
 * [eventsFor] instead of polling roster state.
 *
 * Emission is funnelled through [SubAgentActivityTracker] — every spawn
 * path (main chat, group chat, wolfpack, dispatch) already goes through the
 * tracker's start/updateProgress/beginTool/finishTool/finish/stop methods,
 * so publishing there covers all call sites with zero per-caller wiring.
 * The single exception is retry scheduling, published explicitly from the
 * retry loop in ChatViewModelSubAgentExt (the tracker only sees it as an
 * opaque step string).
 *
 * Bounds: the SharedFlow never suspends publishers (tryEmit + DROP_OLDEST);
 * rings hold [RING_PER_SESSION] events per session and at most
 * [MAX_SESSIONS] sessions (LRU) — a long-running app cannot grow this
 * without limit.
 */
sealed class SubAgentEvent {
    abstract val runId: String
    abstract val parentSessionId: String
    abstract val atMs: Long

    data class Spawned(
        override val runId: String,
        override val parentSessionId: String,
        val parentToolId: String,
        val title: String,
        val role: String?,
        val kind: String,
        val index: Int,
        val total: Int,
        val model: String,
        val turnCap: Int,
        override val atMs: Long,
    ) : SubAgentEvent()

    data class TurnStarted(
        override val runId: String,
        override val parentSessionId: String,
        val turn: Int,
        val cap: Int,
        override val atMs: Long,
    ) : SubAgentEvent()

    data class ToolInvoked(
        override val runId: String,
        override val parentSessionId: String,
        val toolName: String,
        val argsPreview: String,
        override val atMs: Long,
    ) : SubAgentEvent()

    data class ToolFinished(
        override val runId: String,
        override val parentSessionId: String,
        val toolName: String,
        val success: Boolean,
        val durationMs: Long,
        override val atMs: Long,
    ) : SubAgentEvent()

    data class RetryScheduled(
        override val runId: String,
        override val parentSessionId: String,
        val attempt: Int,
        val maxAttempts: Int,
        val waitMs: Long,
        val reason: String,
        override val atMs: Long,
    ) : SubAgentEvent()

    data class Completed(
        override val runId: String,
        override val parentSessionId: String,
        val success: Boolean,
        val turnsUsed: Int,
        val toolCalls: Int,
        override val atMs: Long,
    ) : SubAgentEvent()

    data class Stopped(
        override val runId: String,
        override val parentSessionId: String,
        override val atMs: Long,
    ) : SubAgentEvent()
}

object SubAgentEventBus {

    private const val RING_PER_SESSION = 300
    private const val MAX_SESSIONS = 16

    private val _events = MutableSharedFlow<SubAgentEvent>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    /** Hot stream of every lifecycle event, all sessions. */
    val events: Flow<SubAgentEvent> = _events.asSharedFlow()

    /** Events for one parent session (the "parent awareness" surface). */
    fun eventsFor(parentSessionId: String): Flow<SubAgentEvent> =
        _events.filter { it.parentSessionId == parentSessionId }

    private val lock = Any()

    /** sessionId → bounded ring; LinkedHashMap access-order = LRU across sessions. */
    private val rings = object : LinkedHashMap<String, ArrayDeque<SubAgentEvent>>(
        MAX_SESSIONS, 0.75f, true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArrayDeque<SubAgentEvent>>): Boolean =
            size > MAX_SESSIONS
    }

    fun publish(event: SubAgentEvent) {
        synchronized(lock) {
            val ring = rings.getOrPut(event.parentSessionId) { ArrayDeque(RING_PER_SESSION) }
            ring.addLast(event)
            while (ring.size > RING_PER_SESSION) ring.removeFirst()
        }
        _events.tryEmit(event)
    }

    /**
     * Catch-up view for late subscribers: the most recent events of one
     * parent session, oldest first. Empty when the ring rolled over or the
     * session was cleared — consumers must treat this as best-effort.
     */
    fun recentFor(parentSessionId: String, limit: Int = 100): List<SubAgentEvent> {
        synchronized(lock) {
            val ring = rings[parentSessionId] ?: return emptyList()
            return ring.takeLast(limit).toList()
        }
    }

    /** Per-run summary built from the ring — for results/diagnostics, no UI coupling. */
    fun summaryFor(runId: String): RunSummary? {
        synchronized(lock) {
            var spawned: SubAgentEvent.Spawned? = null
            var completed: SubAgentEvent.Completed? = null
            var stopped: SubAgentEvent.Stopped? = null
            var retries = 0
            var toolCalls = 0
            var maxTurn = 0
            var session: String? = null
            for (r in rings.values) {
                for (e in r) {
                    if (e.runId != runId) continue
                    session = e.parentSessionId
                    when (e) {
                        is SubAgentEvent.Spawned -> spawned = e
                        is SubAgentEvent.Completed -> completed = e
                        is SubAgentEvent.Stopped -> stopped = e
                        is SubAgentEvent.RetryScheduled -> retries++
                        is SubAgentEvent.ToolInvoked -> toolCalls++
                        is SubAgentEvent.TurnStarted -> if (e.turn > maxTurn) maxTurn = e.turn
                        else -> Unit
                    }
                }
            }
            val s = session ?: return null
            return RunSummary(
                runId = runId,
                parentSessionId = s,
                title = spawned?.title.orEmpty(),
                kind = spawned?.kind.orEmpty(),
                // success is only known from a Completed event: a run that is
                // still going, was stopped, or failed without one reads false.
                success = completed?.success ?: false,
                finished = completed != null || stopped != null,
                stoppedByUser = stopped != null,
                turns = completed?.turnsUsed ?: maxTurn,
                toolCalls = completed?.toolCalls ?: toolCalls,
                retries = retries,
            )
        }
    }

    data class RunSummary(
        val runId: String,
        val parentSessionId: String,
        val title: String,
        val kind: String,
        val success: Boolean,
        val finished: Boolean,
        val stoppedByUser: Boolean,
        val turns: Int,
        val toolCalls: Int,
        val retries: Int,
    )

    /** Drop one session's ring (session deleted). The hot flow is untouched. */
    fun clearSession(parentSessionId: String) {
        synchronized(lock) { rings.remove(parentSessionId) }
    }

    /** Test hygiene: wipe every ring. */
    fun clearAll() {
        synchronized(lock) { rings.clear() }
    }
}
