package com.openminis.app.data

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Memory-first folded write buffer for one hot persistence path, a port of
 * pi's deferred commit (pi durable/src/session/session.ts:181-203 and the
 * Session kernel docs): a commit is visible to in-memory observers IMMEDIATELY
 * and its storage write waits, folded per key, for a deadline (pi's
 * `persistWithinMs`, default 1s), an explicit flush, or close. The crash-loss
 * window is therefore <= [deadlineMs] and stated explicitly instead of being
 * an accident of wherever a caller happened to put its DAO write.
 *
 * Semantics:
 *  - [submit] stores the value in memory at once — readers via [snapshot] see
 *    it without waiting for storage — and (re)arms the deadline if idle.
 *  - Folding: repeated submits to the same key overwrite in the pending map,
 *    so N writes produce ONE storage write of the LATEST value.
 *  - The deadline task drains every pending key in one pass (pi folds per
 *    document; our paths write a handful of keys, one drain serves all).
 *  - [flush]/[flushAll]/[close] give callers the pi commit triggers: a read or
 *    write that must see durable state, and shutdown.
 *  - Persist failures are NOT dropped silently and NOT retried in a hot loop:
 *    the failed value goes back into the buffer (latest per key survives) and
 *    [onError] reports it; the next explicit flush or a later [submit]
 *    deadline retries. pi treats storage failures as session-fatal; our
 *    equivalent contract is "report, keep, retry on the next trigger" — the
 *    buffer never spins a retry timer of its own.
 *
 * Threading: [pending] is only mutated under the buffer lock; the persist
 * callback always runs OUTSIDE the lock, on the buffer's scheduler thread for
 * deadline drains or on the caller's thread for explicit flushes. The
 * callback must not call back into [submit] from the scheduler thread while a
 * drain holds no lock — it does not: drains copy-then-clear under lock and
 * write outside it.
 *
 * Pure JVM, injectable deadline; tests use a short one instead of sleeping.
 */
class DeferredCommitBuffer<K : Any, V : Any>(
    val deadlineMs: Long = DEFAULT_DEADLINE_MS,
    private val onError: ((key: K, value: V, error: Exception) -> Unit)? = null,
    private val persist: (K, V) -> Unit,
) {
    private val lock = Any()
    private val pending = LinkedHashMap<K, V>()
    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "deferred-commit-buffer").apply { isDaemon = true }
        }
    private var scheduledFlush: ScheduledFuture<*>? = null

    @Volatile
    private var closed = false

    /**
     * Store [value] for [key]: visible to [snapshot] immediately, durable
     * within [deadlineMs] (or earlier via an explicit flush). Throws
     * [IllegalStateException] after [close].
     */
    fun submit(key: K, value: V) {
        synchronized(lock) {
            check(!closed) { "DeferredCommitBuffer is closed" }
            pending[key] = value
            if (scheduledFlush == null) {
                scheduledFlush = scheduler.schedule({
                    // Re-armable debounce: the deadline task clears the field
                    // FIRST so submits that arrive right after this drain arm
                    // a fresh timer instead of never being scheduled (P1-1,
                    // adversarial review — the previous code left the field
                    // set forever after the first drain).
                    synchronized(lock) { scheduledFlush = null }
                    flushAll()
                }, deadlineMs, TimeUnit.MILLISECONDS)
            }
        }
    }

    /** Latest in-memory value for [key]; null when not pending. Never touches storage. */
    fun snapshot(key: K): V? = synchronized(lock) { pending[key] }

    /** Number of keys currently waiting for a write. */
    fun pendingCount(): Int = synchronized(lock) { pending.size }

    /**
     * Force one key out to storage now (pi: the first commit that READS the
     * document also stores it — callers use this when a read needs durable
     * state). No-op when the key is not pending (always the case after
     * [close], whose final drain empties the buffer). Returns true when written.
     */
    fun flush(key: K): Boolean {
        val value = synchronized(lock) { pending.remove(key) } ?: return false
        writeAndKeepOnError(key, value)
        return true
    }

    /** Drain every pending key in one pass. Called by the deadline task, explicit flushes, and [close]. */
    fun flushAll() {
        val drained: Map<K, V> = synchronized(lock) {
            // An explicit drain supersedes the pending deadline task: cancel
            // it and clear the field so the next submit re-arms (the drained
            // deadline task clears its own field before calling in here).
            scheduledFlush?.cancel(false)
            scheduledFlush = null
            val copy = LinkedHashMap(pending)
            pending.clear()
            copy
        }
        for ((key, value) in drained) writeAndKeepOnError(key, value)
    }

    /** Flush everything, stop the scheduler, and reject further submits. Idempotent. */
    fun close() {
        val alreadyClosed = synchronized(lock) { closed }
        if (alreadyClosed) return
        flushAll()
        synchronized(lock) { closed = true }
        scheduler.shutdown()
        scheduler.awaitTermination(SHUTDOWN_GRACE_SEC, TimeUnit.SECONDS)
    }

    private fun writeAndKeepOnError(key: K, value: V) {
        try {
            persist(key, value)
        } catch (error: Exception) {
            synchronized(lock) {
                // Latest value for the key survives; a newer submit may have
                // replaced it meanwhile — putIfAbsent semantics would drop the
                // retry, so put() is deliberate.
                if (!closed) pending[key] = value
            }
            onError?.invoke(key, value, error)
        }
    }

    companion object {
        /** pi session.ts: DEFAULT_PERSIST_WITHIN_MS = 1_000. */
        const val DEFAULT_DEADLINE_MS = 1_000L

        private const val SHUTDOWN_GRACE_SEC = 5L
    }
}
