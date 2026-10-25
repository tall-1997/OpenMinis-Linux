package com.openminis.app.provider

import okhttp3.CacheControl
import okhttp3.Request

/**
 * Keeps a force-refresh of one provider instance from reusing a result that
 * was cached for another instance on the same API address.
 *
 * Disk caches used to key only on base URL (+ credential). Two providers that
 * share an address therefore skipped the network. Callers pass [cacheScope]
 * (the instance id), which [cacheKey] folds into the on-disk key.
 *
 * ## There is deliberately no URL cache-buster here
 *
 * [T-models-fetch-no-query-buster] This object used to also expose
 * `bustUrl(url, forceRefresh, cacheScope)`, which appended
 * `?minis_nocache=<scope>-<nanoTime>` on force refresh "so a URL-keyed
 * intermediary cannot collapse parallel refreshes". It is gone because the
 * cost was total and the benefit was speculative.
 *
 * Cost, measured: a gateway that routes strictly on path answers **404 with an
 * empty body to any query string at all** on `/v1/models` — not just to this
 * parameter. `?foo=bar` behaves identically, while the same request with
 * `Cache-Control: no-cache, no-store` and `Pragma: no-cache` returns 200. Only
 * force refresh appended the parameter, so adding a provider worked and
 * pressing Refresh afterwards could never work; because Refresh also runs with
 * `clearFirst`, the picker was emptied first and then could not be repopulated.
 *
 * Benefit, re-examined: two parallel force refreshes that an intermediary
 * collapses are two *identical* requests — same URL, same credential — so the
 * collapsed answer is the correct answer for both, and their on-disk caches
 * were already separate via [cacheKey]. For two instances sharing an address
 * with *different* credentials, an intermediary that ignores `Authorization`
 * is already leaking one tenant's catalog to the other; a query parameter is
 * not a defence against that, and [cacheKey] includes the credential so the
 * local cache is correct either way.
 *
 * Cache defeat is therefore header-only, via [noStoreIf].
 */
object ModelListFetchIsolation {
    fun cacheKey(base: String, cacheScope: String): String =
        if (cacheScope.isEmpty()) base else "$base|$cacheScope"

    /**
     * Defeat intermediary and local caches with request headers only — never by
     * mutating the URL. See the object KDoc: some gateways 404 any query string.
     */
    fun Request.Builder.noStoreIf(forceRefresh: Boolean): Request.Builder = apply {
        if (forceRefresh) {
            cacheControl(CacheControl.FORCE_NETWORK)
            header("Cache-Control", "no-cache, no-store")
            header("Pragma", "no-cache")
        }
    }
}
