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
 */
object SharedHttpClients {
    /** Per-attempt hard ceiling. Bounds connect + TLS + redirects + read together. */
    private const val CALL_TIMEOUT_SEC = 15L

    /** Long enough for the slow dual-stack path above; short enough to fail over. */
    private const val CONNECT_TIMEOUT_SEC = 8L

    /** Catalog bodies are small (hundreds of bytes to a few hundred KB). */
    private const val READ_TIMEOUT_SEC = 15L

    val default: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
            .writeTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
