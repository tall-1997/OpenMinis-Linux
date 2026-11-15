package com.openminis.app.provider

/**
 * Per-provider model-list refresh generation guard — Kotlin port of pi's
 * `Models.refresh` scheme (packages/ai/src/models.ts:506-641:
 * `supersedeProviderRefresh` / `beginProviderRefresh` /
 * `publishProviderModels`).
 *
 * Root cause addressed (PI-PORT-ANALYSIS-2026-11-15 §3, "model list refresh
 * generation"): several concurrent entry points can refresh the SAME provider
 * instance — a manual refresh, the toolbar Sync sweep
 * (`refreshAllModelsForce`), stale-while-revalidate on picker open
 * (`triggerBackgroundRefreshIfStale`), the daily sweep
 * (`refreshAllModelsIfNeeded`), credential-change recovery. Nothing ordered
 * them: the SLOWEST of two overlapping refreshes published LAST and its stale
 * catalog overwrote the fresh one in the persisted config and the StateFlow
 * the picker renders.
 *
 * Mechanism, kept in spirit identical to pi with one deliberate difference:
 * pi aborts the superseded refresh's AbortController AND re-checks the
 * generation inside a per-provider publication chain. Killing an in-flight
 * Kotlin coroutine is unnecessary here (the caller still awaits its result)
 * and risky mid-suspend, so only the pi half that matters for correctness is
 * ported: supersede via generation bump, and re-check at publish time.
 *
 *  - [begin] bumps the per-provider generation counter and returns the value
 *    the refresh captured. Starting a refresh supersedes every previous one.
 *  - [publishIfCurrent] runs the publication lambda (cache write + UI
 *    notification) only while the captured generation is still the live one.
 *    The check and the publication are one atomic step under the guard lock
 *    (pi achieves the same by re-checking inside its per-provider publication
 *    chain), so a supersede can never interleave between the check and a
 *    non-suspending publish: a stale refresh's fetch result is dropped with
 *    nothing written and nothing announced.
 *
 * Pure JVM (no Android imports) so the guard contract is unit-testable; the
 * wiring lives in `ModelCatalogCache.refreshModels`, whose publish sites are
 * the only callers.
 */
class ModelRefreshGeneration {

    private val lock = Any()

    /** providerId -> generation currently in charge. */
    private val generations = HashMap<String, Int>()

    /**
     * Start a new refresh for [providerId]: increments and returns the
     * generation this run captured. Every generation returned earlier for the
     * same id is superseded from this call on (pi `supersedeProviderRefresh`).
     */
    fun begin(providerId: String): Int = synchronized(lock) {
        val next = (generations[providerId] ?: 0) + 1
        generations[providerId] = next
        next
    }

    /** Whether [generation] is still the live generation for [providerId]. */
    fun isCurrent(providerId: String, generation: Int): Boolean = synchronized(lock) {
        generations[providerId] == generation
    }

    /**
     * Run [publish] only while [generation] is still current; returns whether
     * it published. The currentness check and [publish] are one atomic step,
     * so a stale refresh can never write or notify after a newer refresh has
     * begun (pi `publishProviderModels` re-check before persist + update).
     * Never call this with a suspending lambda: [publish] runs under the
     * guard lock and must complete synchronously.
     */
    fun publishIfCurrent(providerId: String, generation: Int, publish: () -> Unit): Boolean =
        synchronized(lock) {
            if (generations[providerId] != generation) {
                return@synchronized false
            }
            publish()
            true
        }
}
