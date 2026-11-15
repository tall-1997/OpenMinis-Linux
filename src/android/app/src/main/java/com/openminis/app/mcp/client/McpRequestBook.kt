package com.openminis.app.mcp.client

import com.openminis.app.mcp.client.McpJsonRpc.McpError
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/**
 * [T-mcp-progress-refresh] One MCP progress notification — pi's
 * `ProgressNotification` shape (`progress`, optional `total`/`message`).
 */
class McpProgress(
    val progress: Double,
    val total: Double?,
    val message: String?,
    val raw: JSONObject,
)

/**
 * Per-call request options — pi client.ts `McpRequestOptions` adapted to the
 * blocking Kotlin client:
 *  - [timeoutMs] overrides the client's default deadline for this one request
 *    (pi `options.timeoutMs ?? requestTimeoutMs ?? 30s`);
 *  - [onProgress] receives `notifications/progress` for this request, and each
 *    notification RE-ARMS the deadline (pi client.ts:536-547) so a chatty long
 *    call never times out while an idle one still does;
 *  - [onAbortHandle] is called back with the abort trigger as soon as the
 *    request is queued; invoking the trigger settles the request with an
 *    `ABORTED` error and — `initialize` excepted — sends the server a
 *    `notifications/cancelled` for it (pi `cancelPending`, client.ts:561-575).
 */
data class McpRequestOptions(
    val timeoutMs: Long? = null,
    val onProgress: ((McpProgress) -> Unit)? = null,
    val onAbortHandle: ((abort: () -> Unit) -> Unit)? = null,
    val abortReason: String? = null,
)

/**
 * [T-mcp-request-book] In-flight request bookkeeping for the stdio client —
 * the Kotlin port of pi client.ts's `pending`/`progressRequests` maps and
 * `armTimeout`/`cancelPending` machinery:
 *
 *  - every request gets a deadline ([DEFAULT_REQUEST_TIMEOUT_MS] unless
 *    overridden); a progress notification for its progress token re-arms it;
 *  - a deadline miss settles the request with a [McpError.TIMEOUT];
 *  - an abort settles it with `ABORTED` (the caller decides whether to tell
 *    the server, based on [Pending.cancellable]);
 *  - reply delivery is FIRST-ONE-WINS: a late duplicate for an already
 *    settled id is dropped (protocol violation), and a reply after timeout or
 *    abort is dropped too — a settled request never changes its outcome.
 *
 * Pure JVM: the clock and the timer scheduler are injected, so deadline
 * refresh semantics are testable without real sleeps.
 */
class McpRequestBook(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    /** Returns a disarm handle for the scheduled task. Null: use the shared daemon timer. */
    scheduleOverride: ((delayMs: Long, task: Runnable) -> (() -> Unit))? = null,
) {
    companion object {
        /** pi client.ts `DEFAULT_REQUEST_TIMEOUT_MS`. */
        const val DEFAULT_REQUEST_TIMEOUT_MS = 30_000L

        /** pi client.ts timeout reason string. */
        const val TIMEOUT_REASON = "Request timed out"
    }

    /** What a waiting caller wakes up to: the server's reply, or a settled failure. */
    sealed class Outcome {
        class Reply(val value: JSONObject) : Outcome()

        class Failed(val error: McpError) : Outcome()
    }

    class Pending internal constructor(
        val id: String,
        val method: String,
        val timeoutMs: Long,
        /** pi `entry.cancellable`: the spec forbids cancelling `initialize`. */
        val cancellable: Boolean,
        val onProgress: ((McpProgress) -> Unit)?,
        val progressToken: String?,
        val replies: LinkedBlockingQueue<Outcome>,
    ) {
        internal var settled = false
        internal var aborted = false
        internal var armGen = 0
        internal var deadlineMs = 0L
        internal var disarm: (() -> Unit)? = null
    }

    private val lock = Any()
    private val pending = LinkedHashMap<String, Pending>()
    /** progress token (string form of the request id) → request id. */
    private val tokenIndex = HashMap<String, String>()

    private val timerExecutor: ScheduledThreadPoolExecutor? =
        if (scheduleOverride == null) {
            ScheduledThreadPoolExecutor(
                1,
                ThreadFactory { r -> Thread(r, "mcp-request-timer").apply { isDaemon = true } },
            )
        } else {
            null
        }

    private val schedule: (Long, Runnable) -> (() -> Unit) =
        scheduleOverride ?: { delay, task ->
            val future = timerExecutor!!.schedule(task, delay, TimeUnit.MILLISECONDS)
            val cancel: () -> Unit = { future.cancel(false) }
            cancel
        }

    // -- registration ----------------------------------------------------------

    /**
     * Registers a request and arms its deadline (pi sets `entry` + `armTimeout`
     * before the transport send, so a wedged stdin write is bounded too).
     */
    fun add(
        id: String,
        method: String,
        timeoutMs: Long,
        cancellable: Boolean,
        onProgress: ((McpProgress) -> Unit)? = null,
        progressToken: String? = null,
        replies: LinkedBlockingQueue<Outcome> = LinkedBlockingQueue(),
    ): Pending = synchronized(lock) {
        val entry = Pending(id, method, timeoutMs, cancellable, onProgress, progressToken, replies)
        pending[id] = entry
        if (progressToken != null) tokenIndex[progressToken] = id
        armLocked(entry)
        entry
    }

    fun get(id: String): Pending? = synchronized(lock) { pending[id] }

    /**
     * Removes the entry WITHOUT settling it: a reply landing after removal is
     * dropped by [deliver] (unknown id). The caller's `finally` owns removal,
     * mirroring pi's `removePending` on settle plus the caller's cleanup.
     */
    fun remove(id: String) {
        synchronized(lock) {
            val entry = pending.remove(id) ?: return
            // Only drop the token mapping when it still points at THIS id:
            // a token reused by a later registration must survive the earlier
            // request's exit (adversarial review P2-5).
            val token = entry.progressToken
            if (token != null && tokenIndex[token] == id) tokenIndex.remove(token)
            entry.disarm?.invoke()
            entry.disarm = null
        }
    }

    /** Re-arms the deadline of a registered request (pi `armTimeout`). */
    fun armTimeout(id: String): Boolean = synchronized(lock) {
        val entry = pending[id] ?: return false
        armLocked(entry)
        true
    }

    // -- delivery --------------------------------------------------------------

    /**
     * Delivers a reply for [id]. First one wins: `false` when the request is
     * unknown (removed/timed out) or already settled — a second reply for the
     * same id is a protocol violation and must not overwrite the outcome.
     */
    fun deliver(id: String, reply: JSONObject): Boolean = synchronized(lock) {
        val entry = pending[id] ?: return false
        if (entry.settled) return false
        settleLocked(entry, Outcome.Reply(reply))
        true
    }

    /**
     * A `notifications/progress` for [token] (string form of the progress
     * token): re-arms that request's deadline (pi client.ts:536-541) and
     * returns the entry so the caller can run `onProgress` — null when the
     * token is unknown (stale/foreign notification).
     */
    fun progress(token: String): Pending? = synchronized(lock) {
        val id = tokenIndex[token] ?: return null
        val entry = pending[id] ?: return null
        // A settled entry lingers in the map until the caller's finally
        // remove(); pi removes it from pending at settle time
        // (client.ts:577-582), so late progress notifications never re-arm or
        // callback into an already-failed request. Gate here for the same
        // observable behavior without changing the remove-ownership contract.
        if (entry.settled) return null
        armLocked(entry)
        entry
    }

    /**
     * Aborts [id]. Mirrors pi `cancelPending` (client.ts:561-575): when the
     * request was still unsettled it is failed with `ABORTED` and the caller
     * should send the server `notifications/cancelled`; when the request had
     * ALREADY completed, a cancelled notice would be stale — pi removed the
     * abort listener on settle, so its late aborts are silent no-ops.
     */
    sealed class AbortResult {
        /** The request was settled with ABORTED — send the cancelled notice. */
        class Settled(val entry: Pending) : AbortResult()

        /** The request had already completed; do not notify the server. */
        class AlreadySettled(val entry: Pending) : AbortResult()

        /** Unknown id — the request is gone (removed/closed). */
        object Unknown : AbortResult()
    }

    fun abort(id: String, reason: String?): AbortResult = synchronized(lock) {
        val entry = pending[id] ?: return AbortResult.Unknown
        entry.aborted = true
        if (entry.settled) return AbortResult.AlreadySettled(entry)
        settleLocked(
            entry,
            Outcome.Failed(McpError(McpError.ABORTED, reason?.takeIf { it.isNotBlank() } ?: "Aborted")),
        )
        AbortResult.Settled(entry)
    }

    /**
     * Force-expires [id] now (belt-and-braces for the poll-timeout path when a
     * scheduled timer was lost). False when unknown or already settled.
     */
    fun timeoutNow(id: String): Boolean = synchronized(lock) {
        val entry = pending[id] ?: return false
        if (entry.settled) return false
        settleLocked(entry, timeoutFailure(entry))
        true
    }

    /**
     * Fails every unsettled request — the reader thread is the only reply
     * writer, so once it exits no pending call can ever be answered.
     */
    fun failAll(reason: String) {
        synchronized(lock) {
            for (entry in pending.values) {
                if (!entry.settled) {
                    settleLocked(entry, Outcome.Failed(McpError(McpError.CONNECTION_ERROR, reason)))
                }
            }
        }
    }

    /** True while any JSON-RPC request is in flight (the stdio-cap busy flag). */
    val isBusy: Boolean get() = synchronized(lock) { pending.isNotEmpty() }

    /** Registered (not necessarily armed) entries — test/introspection aid. */
    val size: Int get() = synchronized(lock) { pending.size }

    /** Stops the shared timer thread (no-op when a scheduler was injected). */
    fun dispose() {
        timerExecutor?.shutdownNow()
    }

    // -- internals ---------------------------------------------------------------

    private fun armLocked(entry: Pending) {
        entry.disarm?.invoke()
        entry.disarm = null
        entry.armGen += 1
        entry.deadlineMs = nowMs() + entry.timeoutMs
        // pi: a non-finite/non-positive timeout never arms a timer.
        if (entry.timeoutMs <= 0) return
        val id = entry.id
        val gen = entry.armGen
        entry.disarm = schedule(entry.timeoutMs) { onTimerFired(id, gen) }
    }

    private fun onTimerFired(id: String, gen: Int) {
        synchronized(lock) {
            val entry = pending[id] ?: return
            // A newer arm (progress refresh / re-arm) superseded this firing.
            if (entry.armGen != gen || entry.settled) return
            // Defensive clock check — normally unreachable because each arm
            // cancels the previous timer.
            if (nowMs() < entry.deadlineMs) return
            settleLocked(entry, timeoutFailure(entry))
        }
    }

    private fun timeoutFailure(entry: Pending): Outcome.Failed = Outcome.Failed(
        McpError(
            McpError.TIMEOUT,
            "no reply to '${entry.method}' within ${entry.timeoutMs / 1000}s",
        ),
    )

    private fun settleLocked(entry: Pending, outcome: Outcome) {
        entry.settled = true
        entry.disarm?.invoke()
        entry.disarm = null
        entry.replies.offer(outcome)
    }
}
