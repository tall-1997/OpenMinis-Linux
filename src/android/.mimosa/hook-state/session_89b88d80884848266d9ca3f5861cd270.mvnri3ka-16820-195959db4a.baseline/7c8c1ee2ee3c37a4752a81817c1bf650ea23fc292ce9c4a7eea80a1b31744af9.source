package com.openminis.app.provider

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.random.Random

/**
 * [T-models-fetch-transient-auth] Bounded retry for the catalog (`/models`) GET.
 *
 * ## The invariant
 *
 * **One sample of an idempotent GET is not proof about a credential.**
 *
 * Every `*ModelsApi` used to treat the first non-2xx as final: invalidate the
 * on-disk model cache on 401/403, return an empty list, and let the caller's
 * `models.isNotEmpty()` gate decide. For an *existing* provider that gate
 * preserves the seeded catalog, so the damage is invisible. For a **newly
 * added** provider "whatever we had" is nothing — the model picker stays empty
 * for a credential that actually works, and Manual Refresh re-runs this same
 * code, so it cannot help either.
 *
 * This is a property of the whole class of LLM gateways, not of one vendor.
 * Any front door that terminates *your* auth and then re-authenticates against
 * a pool of upstream channels — one-api/new-api, LiteLLM, OpenRouter, Helicone,
 * Kong/APISIX with an AI plugin, a self-hosted nginx proxying to several
 * upstreams — can reject a request because of the **upstream** credential it
 * happened to pick, while your own key is fine. Load, a disabled channel, an
 * exhausted upstream quota and a rotating token pool all surface that way, and
 * most of them report it as 401/403 because that is the status the upstream
 * returned. Ordinary transient causes (a 5xx from the origin, a reset
 * connection, a cold CDN edge) land on the same code path.
 *
 * Matching on error-body wording would only ever cover the gateways someone
 * happened to test against, so the fix is structural instead: retry a bounded
 * number of times and let **only the last attempt** drive cache invalidation
 * and the user-visible error.
 *
 * Observed in the wild while this was written, for calibration only — a public
 * relay answered 8 consecutive calls with HTTP 401 `Invalid token (request id:
 * …)` and then served the *same* key successfully ~2 minutes later, while a
 * genuinely wrong key answered a differently-worded 401. Neither wording is
 * relied on anywhere below.
 *
 * Cost when the key really is wrong: one extra round trip (~0.7 s). Worth it
 * against permanently reporting a working key as invalid.
 *
 * ## What is deliberately not retried
 *
 * 400 / 404 / 422 and the rest of the 4xx range mean the URL or the parameters
 * are wrong. Repeating a byte-identical request cannot change the answer, and
 * spending the budget on them turns a configuration typo into a 25-second hang.
 *
 * ## Scope
 *
 * This is for the small, idempotent, user-initiated catalog GET only. The chat
 * path keeps its existing classification: a 401 there is
 * [com.openminis.app.data.model.LLMError.InvalidApiKey], which is fallbackable
 * but not retryable, and that stays correct — retrying a rejected generation
 * would hammer a relay that is already refusing us.
 */
object ModelListFetchRetry {
    /** Hard cap on attempts, including the first. */
    const val MAX_ATTEMPTS = 3

    /** 401/403 get exactly one retry — enough to ride out a channel blip. */
    const val AUTH_ATTEMPTS = 2

    /**
     * Wall-clock budget for the whole loop. Once it is spent the last response
     * is handed back untouched so the caller's existing `!isSuccessful`
     * handling runs unchanged; a slow-but-answering gateway can therefore not
     * turn one manual Refresh into a multi-minute hang.
     */
    const val TOTAL_BUDGET_MILLIS = 25_000L

    private const val DELAY_AFTER_1ST_MILLIS = 500L
    private const val DELAY_AFTER_2ND_MILLIS = 1_500L
    private const val AUTH_DELAY_MILLIS = 700L

    /** Fraction of the base delay added or subtracted as jitter, to de-sync parallel refreshes. */
    private const val JITTER_FRACTION = 0.2

    enum class Verdict { SUCCESS, RETRY, FATAL }

    /**
     * Pure decision for one attempt.
     *
     * @param httpCode response status, or null when no response was produced
     * @param transportFailure true when the call threw before a response
     * @param attempt 1-based attempt counter
     */
    fun classify(httpCode: Int?, transportFailure: Boolean, attempt: Int): Verdict {
        if (transportFailure || httpCode == null) {
            return if (attempt < MAX_ATTEMPTS) Verdict.RETRY else Verdict.FATAL
        }
        if (httpCode in 200..299) return Verdict.SUCCESS
        val cap = when {
            httpCode == 429 || httpCode in 500..599 -> MAX_ATTEMPTS
            httpCode == 401 || httpCode == 403 -> AUTH_ATTEMPTS
            // 400/404/422/… : a repeat cannot produce a different answer.
            else -> 1
        }
        return if (attempt < cap) Verdict.RETRY else Verdict.FATAL
    }

    /**
     * Pure backoff for the gap that follows [attempt] (1-based). Jitter is
     * applied by [execute], not here, so this stays exactly assertable.
     */
    fun delayMillis(attempt: Int, httpCode: Int?): Long = when {
        httpCode == 401 || httpCode == 403 -> AUTH_DELAY_MILLIS
        attempt <= 1 -> DELAY_AFTER_1ST_MILLIS
        else -> DELAY_AFTER_2ND_MILLIS
    }

    /**
     * Execute [request] under the policy above and return the final response.
     *
     * The caller owns the returned response (reads the body, closes it).
     * Intermediate responses are closed here so a retry never leaks a pooled
     * connection.
     *
     * A transport failure on the final attempt rethrows, which is what calling
     * `execute()` directly used to do — callers that already catch `Exception`
     * keep working and callers that do not still see the same exception type.
     *
     * @param tag log tag, so a device log shows which vendor catalog is flapping
     */
    suspend fun execute(
        client: OkHttpClient,
        request: Request,
        tag: String,
        totalBudgetMillis: Long = TOTAL_BUDGET_MILLIS,
        /**
         * [T-llm-error-401-model-scope] Credential scope for
         * [CredentialAcceptance]. A 2xx here proves the credential is valid at
         * this host, which is what lets a later 401 on a *chat* call be
         * attributed to the model's upstream channel instead of to the key.
         * Null skips the recording.
         */
        credentialKey: String? = null,
    ): Response {
        val startedAt = System.nanoTime()
        var attempt = 0
        var lastError: IOException? = null

        while (true) {
            attempt++
            val response: Response? = try {
                client.newCall(request).execute()
            } catch (e: IOException) {
                // OkHttp surfaces coroutine cancellation as IOException("Canceled").
                // Retrying then would fight the user's Stop button, so re-check
                // liveness before treating this as a transport failure.
                currentCoroutineContext().ensureActive()
                lastError = e
                android.util.Log.w(tag, "models fetch attempt $attempt/$MAX_ATTEMPTS transport failure: ${e.message}")
                null
            }

            val code = response?.code
            when (classify(code, response == null, attempt)) {
                Verdict.SUCCESS -> {
                    if (attempt > 1) {
                        android.util.Log.i(tag, "models fetch recovered on attempt $attempt (HTTP $code)")
                    }
                    CredentialAcceptance.note(credentialKey)
                    return response!!
                }

                Verdict.FATAL -> {
                    if (response != null) {
                        android.util.Log.w(tag, "models fetch gave up after $attempt attempt(s): HTTP $code")
                        return response
                    }
                    throw lastError ?: IOException("models fetch failed after $attempt attempt(s)")
                }

                Verdict.RETRY -> {
                    val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L
                    val remainingMillis = totalBudgetMillis - elapsedMillis
                    if (remainingMillis <= 0L) {
                        // Out of time. Hand back the last response rather than
                        // throwing: the caller's !isSuccessful branch (cache
                        // invalidation, empty list, PRESERVED) is the behaviour
                        // that predates this retry loop, and it reports the real
                        // status code instead of a synthetic timeout.
                        android.util.Log.w(
                            tag,
                            "models fetch budget ${totalBudgetMillis}ms spent after $attempt attempt(s); returning last HTTP $code",
                        )
                        if (response != null) return response
                        throw lastError ?: IOException(
                            "models fetch exceeded ${totalBudgetMillis}ms after $attempt attempt(s)",
                        )
                    }

                    android.util.Log.w(
                        tag,
                        "models fetch attempt $attempt/$MAX_ATTEMPTS got ${code?.let { "HTTP $it" } ?: "no response"}; retrying",
                    )
                    // Close only after the budget check above, which may return it.
                    response?.close()

                    val base = delayMillis(attempt, code)
                    val jitter = (base * JITTER_FRACTION * (Random.nextDouble() * 2.0 - 1.0)).toLong()
                    delay(minOf(base + jitter, remainingMillis).coerceAtLeast(0L))
                }
            }
        }
    }
}
