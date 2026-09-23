package com.openminis.app.provider

import okhttp3.CacheControl
import okhttp3.Request

/**
 * Keeps a force-refresh of one provider instance from reusing a result that
 * was cached for another instance on the same API address.
 *
 * Disk caches used to key only on base URL (+ credential). Two providers that
 * share an address therefore skipped the network. Callers pass [cacheScope]
 * (the instance id). [bustUrl] also adds a unique query on force refresh so a
 * URL-keyed intermediary cannot collapse parallel refreshes.
 */
object ModelListFetchIsolation {
    fun cacheKey(base: String, cacheScope: String): String =
        if (cacheScope.isEmpty()) base else "$base|$cacheScope"

    fun bustUrl(url: String, forceRefresh: Boolean, cacheScope: String): String {
        if (!forceRefresh) return url
        val token = (cacheScope.ifEmpty { "force" }) + "-" + System.nanoTime()
        val sep = if ('?' in url) "&" else "?"
        return url + sep + "minis_nocache=" + java.net.URLEncoder.encode(token, "UTF-8")
    }

    fun Request.Builder.noStoreIf(forceRefresh: Boolean): Request.Builder = apply {
        if (forceRefresh) {
            cacheControl(CacheControl.FORCE_NETWORK)
            header("Cache-Control", "no-cache, no-store")
            header("Pragma", "no-cache")
        }
    }
}
