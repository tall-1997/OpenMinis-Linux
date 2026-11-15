package com.openminis.app.agent.lifecycle

import java.util.Queue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * [T-ext-ctx-guard] pi coding-agent stale-extension-context semantics
 * (extensions/loader.ts:164-244 `createExtensionRuntime`) ported as a reusable
 * primitive. pi's problem: an extension captures the session-bound `ctx`, the
 * session is then replaced (newSession / fork / switch / reload), and the
 * stale capture either crashes on a dead reference or silently mutates the
 * wrong session. pi's fix, mirrored here:
 *
 *  1. A long-lived object captures a [GuardedContext] instead of a raw
 *     session/hook reference and hands the guard around.
 *  2. When the owning session is replaced, reloaded or deleted, the owner
 *     calls [GuardedContext.invalidate] ONCE. Every subscription the context
 *     tracked (listener callbacks etc.) is unsubscribed automatically and the
 *     tracked lambda references are dropped, so a stale listener can never
 *     fire again and nothing keeps the dead session alive.
 *  3. Any later action through the stale context throws an
 *     [IllegalStateException] whose message says exactly what happened and
 *     what to do instead — the pi "self-explanatory stale ctx error" — rather
 *     than a crash deep inside a dead object graph.
 *
 * Concurrency hardening over the JS original (single-threaded there):
 * [GuardedContext.invalidate] is atomic ([AtomicReference.compareAndSet],
 * first message wins, repeat calls are no-ops) and tracked unsubscribe
 * handles are themselves idempotent, so manual-unsubscribe-then-invalidate
 * and concurrent invalidation never double-call an unsubscribe.
 * [GuardedContext.trackUnsubscribe] additionally re-checks invalidation
 * AFTER enqueueing, so a registration that races the one-shot drain runs its
 * unsubscribe immediately instead of leaking into the dead queue (review
 * P2-3).
 *
 * Error isolation of deliveries THROUGH a dispatcher that fans out to
 * handlers stays the dispatcher's job (LifecycleHooks.fire already runs each
 * listener under its own try/catch, pinned by LifecycleHooksTest); the guard
 * owns invalidation and unsubscription only.
 *
 * Pure JVM, no Android imports — unit-testable directly.
 */
object ExtensionContextGuard {

    /**
     * [T-ext-ctx-guard] The self-explanatory failure message, pi-style: state
     * what happened and name the recovery. Thrown as [IllegalStateException] —
     * the caller holding a stale capture is the bug; the session it points at
     * is gone.
     */
    const val DEFAULT_STALE_MESSAGE: String =
        "context invalidated — capture a fresh reference from the current session"

    /** Fresh guard for one extension/session scope. */
    fun create(): GuardedContext = GuardedContext()

    class GuardedContext internal constructor() {

        /** pi loader.ts `state.staleMessage`: null = active, else the throw message. */
        private val staleMessage = AtomicReference<String?>(null)

        /**
         * Tracked unsubscribe handles (pi loader.ts `eventBusUnsubscribers`).
         * Drained by [invalidate]; a handle tracked AFTER the drain runs its
         * unsubscribe immediately — the context is already dead, so letting
         * the lambda linger in the queue until the (one-shot) drain would
         * leak it forever (adversarial review P2-8).
         */
        private val unsubscriptions: Queue<() -> Unit> = ConcurrentLinkedQueue()

        /** True once [invalidate] has run. */
        val isInvalidated: Boolean get() = staleMessage.get() != null

        /**
         * pi loader.ts `assertActive`: a no-op while active; throws the
         * self-explanatory [IllegalStateException] once invalidated.
         */
        fun checkValid() {
            staleMessage.get()?.let { throw IllegalStateException(it) }
        }

        /**
         * Guarded action sugar: [checkValid] then run — the shape every
         * "do X through the (possibly stale) context" call site wants.
         */
        fun <T> use(block: () -> T): T {
            checkValid()
            return block()
        }

        /**
         * Invalidate the context once: the FIRST message wins (pi loader.ts
         * `invalidate(message)`), every tracked unsubscribe runs, and the
         * tracked lambda references are dropped. Later calls are no-ops.
         * [message] overrides [DEFAULT_STALE_MESSAGE] for context-specific
         * wording (e.g. naming the replaced session); callers that have
         * nothing to add should pass null.
         */
        fun invalidate(message: String? = null) {
            if (!staleMessage.compareAndSet(null, message ?: DEFAULT_STALE_MESSAGE)) return
            while (true) {
                val unsubscribe = unsubscriptions.poll() ?: break
                unsubscribe()
            }
        }

        /**
         * pi loader.ts `trackEventBusSubscription`: register an unsubscribe
         * for automatic invocation on [invalidate]. The returned handle is
         * idempotent — manual unsubscribe, then invalidate (or vice versa),
         * runs the underlying unsubscribe exactly once — and removing itself
         * from the pending set drops the lambda reference instead of letting
         * it linger until the next invalidation. CAS-guarded so two racing
         * callers (manual + invalidation) cannot double-run the unsubscribe.
         *
         * Concurrency note (review P2-3): the pre-registration
         * [isInvalidated] check below is advisory only — [invalidate]'s drain
         * may run between that check and the `unsubscriptions.add`. The
         * RE-READ after the add closes that window: a registration that
         * landed in the drained queue runs its unsubscribe immediately
         * (exactly-once, via the handle's CAS) instead of lingering forever
         * behind a one-shot drain that will never come back around.
         */
        fun trackUnsubscribe(unsubscribe: () -> Unit): () -> Unit {
            // Fast path: registration after invalidation unsubscribes
            // immediately — the context can never fire again, so queueing the
            // handle would only pin the lambda (adversarial review P2-8).
            if (isInvalidated) {
                unsubscribe()
                return {}
            }
            val handle = object : () -> Unit {
                val armed = AtomicBoolean(true)
                override fun invoke() {
                    if (!armed.compareAndSet(true, false)) return
                    unsubscriptions.remove(this)
                    unsubscribe()
                }
            }
            unsubscriptions.add(handle)
            // Race-closure re-read (review P2-3): invalidate() may have
            // drained the queue between the fast-path check and the add above
            // — this handle would otherwise sit in the queue forever. Run it
            // NOW. Exactly-once is guaranteed by the handle's CAS: if the
            // concurrent drain already invoked it, this call is a no-op; if
            // this call wins, the drained queue never sees an armed handle.
            if (isInvalidated) {
                handle()
                return {} // already unsubscribed; nothing left to track
            }
            return handle
        }
    }
}
