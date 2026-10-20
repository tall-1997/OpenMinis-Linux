package com.openminis.app.browser

import android.content.ComponentCallbacks2
import android.util.Log
import java.util.Date
import java.util.WeakHashMap

/**
 * [T-android-browser-global-tab-cap] Process-wide gate that caps the total
 * number of live browser tabs across ALL [BrowserTabPool] instances.
 *
 * ## Why
 * Before this registry, each [BrowserTabPool] enforced only its own local
 * `MAX_TABS` ceiling and a WeakHashMap-based cross-pool count. The WeakHashMap
 * approach had two failure modes:
 *
 * 1. **GC lag**: a closed chat's pool could sit in the WeakHashMap until the
 *    next GC, so its tabs were still counted against the global cap — new
 *    chats were denied tabs they should have been able to open.
 * 2. **No arbitration**: when the global cap was hit, the only recourse was
 *    evicting the oldest idle tab from a *different* pool. There was no way
 *    to say "this new chat's navigate is more important than that idle
 *    background tab" — it was purely LRU, and a busy tab in another pool
 *    could block a new chat indefinitely.
 *
 * The registry fixes both: it holds pools weakly (so collected pools stop
 * being counted immediately) and arbitrates requests with a three-tier
 * policy (idle sibling → in-use sibling → deny).
 *
 * ## Thread safety
 * All methods must be called on the main thread (the same thread that owns
 * the WebViews). The registry does not synchronize internally because the
 * browser layer is single-threaded by contract.
 *
 * ## Memory pressure
 * [evictAllIdle] is called from `Application.onTrimMemory` to reclaim native
 * WebView memory under system pressure. Each pool's idle tabs are destroyed;
 * in-use tabs are left alone.
 */
object BrowserTabPoolRegistry {

    private const val TAG = "BrowserTabPoolRegistry"

    /**
     * Process-wide cap on live tabs. Matches the old `MAX_GLOBAL_TABS` in
     * [BrowserTabPool] — the value is centralized here so the registry and
     * the pools agree on a single source of truth.
     */
    const val GLOBAL_TAB_CAP = 6

    /**
     * Pools currently registered. Weak keys: a pool that is garbage-collected
     * (its chat closed and all references dropped) automatically stops being
     * counted. We store `Unit` as the value because we only care about the
     * keys.
     */
    private val pools = WeakHashMap<BrowserTabPoolRegistry.RegistryPool, Unit>()

    /**
     * Interface implemented by [BrowserTabPool] so the registry can inspect
     * and reclaim tabs without a hard dependency on the pool class (keeps
     * the registry testable in isolation).
     */
    interface RegistryPool {
        /** Snapshot of this pool's tabs: id, in-use flag, last activity time. */
        fun registrySnapshot(): List<TabInfo>
        /** Evict an idle tab by id (no-op if the tab is in-use or gone). */
        fun evictTabForRegistry(id: Int)
        /** Force-evict a tab by id even if in-use (for memory pressure). */
        fun preemptTabForRegistry(id: Int)
        /** Evict ALL idle tabs in this pool. Returns the number evicted. */
        fun evictAllIdleForMemoryPressure(): Int
    }

    /**
     * Minimal tab info the registry needs to make eviction decisions.
     */
    data class TabInfo(
        val id: Int,
        val inUse: Boolean,
        val lastActivityDate: Date,
    )

    /**
     * Register a pool. Called from [BrowserTabPool]'s init block. Idempotent.
     */
    fun register(pool: RegistryPool) {
        pools[pool] = Unit
        Log.d(TAG, "Pool registered; ${pools.size} pool(s) active")
    }

    /**
     * Unregister a pool. Called from [BrowserTabPool.dispose]. Idempotent.
     */
    fun unregister(pool: RegistryPool) {
        pools.remove(pool)
        Log.d(TAG, "Pool unregistered; ${pools.size} pool(s) active")
    }

    /**
     * Total live tabs across all registered pools. Pools that have been GC'd
     * are not counted (WeakHashMap drops them automatically).
     */
    fun totalTabs(): Int =
        pools.keys.sumOf { it.registrySnapshot().size }

    /**
     * Request a global tab slot for [requestingPool].
     *
     * Returns true if a slot is available (either the cap is not hit, or an
     * idle tab in another pool was successfully evicted to make room).
     * Returns false if the cap is hit and no idle tab can be evicted — the
     * caller should then fail the tab creation and report back to the model.
     *
     * The requesting pool's own tabs are NOT candidates for eviction (it
     * would be pointless to evict your own tab to make room for yourself).
     */
    fun requestSlot(requestingPool: RegistryPool): Boolean {
        val current = totalTabs()
        if (current < GLOBAL_TAB_CAP) return true

        // Cap is hit. Try to evict an idle tab from a sibling pool.
        val candidate = pools.keys
            .filter { it !== requestingPool }
            .flatMap { pool ->
                pool.registrySnapshot().filter { !it.inUse }.map { pool to it }
            }
            .minByOrNull { it.second.lastActivityDate.time }

        if (candidate != null) {
            Log.i(TAG, "Evicting idle tab ${candidate.second.id} from sibling pool to free a global slot")
            candidate.first.evictTabForRegistry(candidate.second.id)
            return true
        }

        // No idle tab anywhere. As a last resort, preempt the oldest in-use
        // tab in a sibling pool — but only if the requesting pool has zero
        // tabs (a brand-new chat that otherwise can't open ANY tab).
        if (pools[requestingPool] != null && requestingPool.registrySnapshot().isEmpty()) {
            val inUseCandidate = pools.keys
                .filter { it !== requestingPool }
                .flatMap { pool ->
                    pool.registrySnapshot().filter { it.inUse }.map { pool to it }
                }
                .minByOrNull { it.second.lastActivityDate.time }
            if (inUseCandidate != null) {
                Log.w(TAG, "Preempting in-use tab ${inUseCandidate.second.id} from sibling pool (last resort for new chat)")
                inUseCandidate.first.preemptTabForRegistry(inUseCandidate.second.id)
                return true
            }
        }

        Log.w(TAG, "Denied global tab slot: $current tabs live, cap $GLOBAL_TAB_CAP, no evictable candidate")
        return false
    }

    /**
     * Evict ALL idle tabs across ALL pools. Called from
     * `Application.onTrimMemory` under memory pressure. Returns the total
     * number of tabs evicted.
     */
    fun evictAllIdle(): Int {
        var total = 0
        for (pool in pools.keys) {
            total += pool.evictAllIdleForMemoryPressure()
        }
        if (total > 0) {
            Log.i(TAG, "Memory pressure: evicted $total idle tab(s) across ${pools.size} pool(s)")
        }
        return total
    }

    /**
     * Memory-pressure hook called from [Application.onTrimMemory].
     * Evicts idle browser tabs when the system signals moderate-to-critical
     * memory pressure — WebViews hold native memory that ART GC cannot reclaim.
     */
    fun onTrimMemory(level: Int) {
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            runCatching { evictAllIdle() }
                .onFailure { Log.w(TAG, "evictAllIdle failed: ${it.message}") }
        }
    }
}
