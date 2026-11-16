package com.openminis.app.mcp.oauth

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * [T-mcp-oauth-refresh-guard] The three refresh invariants from pi
 * `ai/src/auth/resolve.ts` (`refreshStoredOAuthCredential`, lines 119-191),
 * packaged as a reusable guard:
 *
 *  1. **Single flight with a lock-internal re-check.** The refresh runs under
 *     one [Mutex] per credential, and [stillNeeded] is evaluated AGAIN after
 *     the lock is held — concurrent callers (or a caller that waited while
 *     another refresh finished) skip the network round-trip entirely, so a
 *     refresh token is never spent twice. Refresh-token reuse is exactly what
 *     IdPs answer with `invalid_grant`.
 *  2. **Once started, the refresh is not cancellable.** Network refresh AND
 *     persistence run inside [NonCancellable]: a cancelled caller must never
 *     abort a refresh the server already answered, because the provider may
 *     have rotated the refresh token — the only valid credential would be
 *     lost. (pi: "the refresh and its persistence ignore `signal` and are
 *     bounded only by a timeout".)
 *  3. **Persist before releasing the lock.** [refresh] must persist the new
 *     tokens before it returns; the guard releases the lock only afterwards
 *     (the `finally` runs after the non-cancellable block completes), so every
 *     waiter observes the fresh credential, never a half-applied one.
 *
 * Additionally the WAIT for the lock is bounded by [lockWaitTimeoutMs] (pi's
 * refresh machinery bounds the wait by the caller's signal with a 15s budget);
 * on timeout the caller gives up cleanly and falls back to whatever token is
 * stored — the request then goes out with the old token and a 401 decides.
 * The refresh itself is bounded by [refreshTimeoutMs] for suspend-based
 * refreshes; a blocking refresh implementation must bound its own I/O (the
 * MCP bridge sets a matching per-call OkHttp deadline).
 */
class OAuthRefreshGuard(
    private val lockWaitTimeoutMs: Long = DEFAULT_LOCK_WAIT_TIMEOUT_MS,
    private val refreshTimeoutMs: Long = DEFAULT_REFRESH_TIMEOUT_MS,
) {

    private val mutex = Mutex()

    /**
     * Refresh the credential only if [stillNeeded] still says so under the
     * lock. [stillNeeded] MUST re-read the current stored credential (not a
     * stale snapshot taken before the wait) — pi compares the stored access
     * token under the lock and skips when it changed.
     *
     * [refresh] performs the network round-trip AND persists the result, all
     * before returning; it runs inside [NonCancellable] and is skipped/failed
     * safe: the return value is whatever [refresh] produced (null on failure
     * or skip), so callers fall back to re-reading the store — pi: "Tokens
     * that changed meanwhile (another process refreshed them, or the user
     * signed in) are used without refreshing."
     *
     * Null return means: no refresh was performed by THIS call (not needed
     * any more, lock wait timed out, refresh failed or was cancelled).
     */
    suspend fun <T : Any> refreshOnce(
        stillNeeded: () -> Boolean,
        refresh: suspend () -> T?,
    ): T? {
        var locked = false
        try {
            // Cancellation applies ONLY to the wait for the lock. The flag is
            // set right after lock() resumes — there is no suspension point
            // in between, so a lost race between timeout and acquisition can
            // never leak the permit (finally releases it).
            val acquired = withTimeoutOrNull(lockWaitTimeoutMs) {
                mutex.lock()
                locked = true
                true
            } ?: return null

            // Second validity check under the lock: another caller (or
            // process, via the persisted store) may have refreshed meanwhile.
            if (!stillNeeded()) return null

            // Once started: uncancellable + persist-before-release.
            return withContext(NonCancellable) {
                withTimeoutOrNull(refreshTimeoutMs) { refresh() }
            }
        } finally {
            if (locked) mutex.unlock()
        }
    }

    companion object {
        /**
         * Bound on the WAIT for the refresh lock (pi resolve.ts: the refresh
         * machinery races the lock wait against a 15s budget).
         */
        const val DEFAULT_LOCK_WAIT_TIMEOUT_MS = 15_000L

        /**
         * Bound on the refresh round-trip itself (pi resolve.ts
         * `DEFAULT_OAUTH_REFRESH_TIMEOUT_MS = 15_000`).
         */
        const val DEFAULT_REFRESH_TIMEOUT_MS = 15_000L

        /**
         * One guard per credential key (server id / provider instance id —
         * mirrors OAuthManager's per-instance mutex registry). Managers are
         * constructed per call site in this app, so the guard must not live
         * on the manager object.
         */
        private val shared = ConcurrentHashMap<String, OAuthRefreshGuard>()

        fun forKey(key: String): OAuthRefreshGuard = shared.getOrPut(key) { OAuthRefreshGuard() }
    }
}
