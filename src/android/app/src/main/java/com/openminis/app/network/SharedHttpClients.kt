package com.openminis.app.network

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Shared default [OkHttpClient] so catalog / models fetches reuse one
 * connection pool and dispatcher instead of constructing a fresh client
 * (and thread pool) per `*ModelsApi`.
 *
 * Callers that need custom timeouts or interceptors should still build
 * their own client — this is the default for small, idempotent GETs.
 *
 * ## [T-models-fetch-timeouts] why these are explicit
 *
 * The bare `OkHttpClient()` this replaced left `callTimeout` at its default of
 * **0 = no ceiling**. With `retryOnConnectionFailure` on, OkHttp may then spend
 * a full connect timeout per resolved address *and* a full read timeout per
 * attempt, with nothing bounding the sum. On a dual-stack network that is not
 * academic: measured against a Cloudflare-fronted relay from China Unicom, the
 * IPv6 path took `connect 1.57s + TLS 4.12s = ttfb 4.48s` where IPv4 took
 * `connect 0.58s + TLS 0.81s = ttfb 1.54s` — same host, same request, ~3x
 * apart. An unbounded call on the slow family is exactly the kind of stall that
 * reads to the user as "the app hung on Refresh".
 *
 * A hard `callTimeout` also gives
 * [com.openminis.app.provider.ModelListFetchRetry] a predictable per-attempt
 * cost, so its own wall-clock budget is the thing that decides how many attempts
 * happen instead of an accidental interaction between timeouts.
 *
 * `retryOnConnectionFailure` stays on (and is spelled out because it is
 * load-bearing): it is what lets a connect failure on one address family fall
 * through to the next rather than surfacing as an immediate error.
 *
 * ## [GH#258][GH#287] why this is generation-keyed, not `by lazy`
 *
 * The CA import/delete path changes [AppTrustStore.generation]. A `by lazy`
 * client froze the first `sslSocketFactory` for the whole process, so a CA
 * imported after the catalog was first fetched never took effect (the only
 * recovery was a restart). The cache below is keyed by that generation and is
 * rebuilt on the next access; callers must read [default] at CALL time (a
 * `val client = SharedHttpClients.default` field would capture one generation),
 * which is why the `*ModelsApi` objects expose it as a getter.
 */
object SharedHttpClients {
    /** Per-attempt hard ceiling. Bounds connect + TLS + redirects + read together. */
    private const val CALL_TIMEOUT_SEC = 15L

    /** Long enough for the slow dual-stack path above; short enough to fail over. */
    private const val CONNECT_TIMEOUT_SEC = 8L

    /** Catalog bodies are small (hundreds of bytes to a few hundred KB). */
    private const val READ_TIMEOUT_SEC = 15L

    private val lock = Any()

    /** Generation the cached client was built for; -1 = never built. */
    @Volatile
    private var builtGeneration: Int = -1

    @Volatile
    private var built: OkHttpClient? = null

    /**
     * The shared client for the CURRENT trust generation. Rebuilt when a CA is
     * imported or removed; otherwise the same instance (and pool) is returned.
     */
    val default: OkHttpClient
        get() {
            val generation = AppTrustStore.generation
            built?.let { if (builtGeneration == generation) return it }
            synchronized(lock) {
                built?.let { if (builtGeneration == generation) return it }
                val client = OkHttpClient.Builder()
                    .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
                    .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
                    .writeTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
                    .callTimeout(CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(true)
                    // [GH#258][GH#287] Provider catalogs can live behind the user's
                    // private CA; add the app trust store when one is imported.
                    .applyAppTrustStore()
                    .build()
                built = client
                builtGeneration = generation
                return client
            }
        }
}
