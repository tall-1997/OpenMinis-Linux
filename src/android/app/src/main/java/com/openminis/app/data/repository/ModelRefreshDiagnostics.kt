package com.openminis.app.data.repository

import java.util.concurrent.ConcurrentHashMap

/**
 * [T-models-refresh-failure-reason] Why the last model-list fetch for one
 * provider instance produced nothing.
 *
 * ## The problem
 *
 * `refreshModels` reported a bare `ModelRefreshResult.FAILURE` for a 404, a
 * 401, a socket reset and a 2xx body that is not a model list alike. The
 * provider screen could therefore only render one flat word, and because a
 * failed refresh deliberately keeps the previous catalogue (that part is
 * right — clearing it would destroy working configuration), the old models
 * stayed on screen underneath. "The address points at the wrong port" and
 * "the server is briefly down" were indistinguishable.
 *
 * Measured case that motivated this: a provider saved as
 * `http://127.0.0.1:8080/v1` while the server listened on `:8000`. Every
 * refresh answered `404 Not Found`, the screen said only "Refresh failed", and
 * five models from an earlier successful sync stayed listed — so the list
 * looked live while nothing on that port could ever serve it.
 *
 * ## Why a side channel instead of a richer return type
 *
 * Every `*ModelsApi.fetchModels` returns `List<LLMModel>`, where an empty list
 * is the documented "learned nothing — keep what we had" signal. Threading an
 * outcome type through would touch every provider branch, every caller and
 * every test. The reason travels beside the value instead: fetchers record it
 * here, `refreshModels` drains it, and [ProviderRefreshMarks] persists it so
 * the line survives a restart.
 *
 * Scoped by `instance.id` — the same value already handed to every fetcher as
 * `cacheScope` — so two providers refreshing concurrently cannot overwrite
 * each other's reason. A blank scope is ignored rather than shared: folding
 * unrelated anonymous callers into one slot is worse than no detail.
 *
 * Not persisted here on purpose. This is the hand-off between the fetch and the
 * repository; [ProviderRefreshMarks] owns durability.
 */
data class ModelRefreshFailure(
    val kind: Kind,
    val httpStatus: Int? = null,
) {
    enum class Kind {
        /** A non-2xx answer arrived; [httpStatus] carries its code. */
        HTTP_ERROR,

        /** The socket failed before any answer did (DNS, refused, reset, TLS). */
        TRANSPORT,

        /** 2xx, but the body is not JSON, has no `data` array, or is empty. */
        PAYLOAD,
    }
}

/** Process-wide hand-off for [ModelRefreshFailure]. See its KDoc for the why. */
object ModelRefreshDiagnostics {
    private val last = ConcurrentHashMap<String, ModelRefreshFailure>()

    /** A non-2xx answer is the highest-value fact, so it also stops retrying. */
    fun recordHttp(scope: String, status: Int) {
        if (scope.isEmpty()) return
        last[scope] = ModelRefreshFailure(ModelRefreshFailure.Kind.HTTP_ERROR, status)
    }

    /** The request never got an answer. [cause] is only used for the log line. */
    fun recordTransport(scope: String) {
        if (scope.isEmpty()) return
        last[scope] = ModelRefreshFailure(ModelRefreshFailure.Kind.TRANSPORT)
    }

    /** An answer arrived but did not contain a usable model list. */
    fun recordPayload(scope: String) {
        if (scope.isEmpty()) return
        last[scope] = ModelRefreshFailure(ModelRefreshFailure.Kind.PAYLOAD)
    }

    /**
     * Read once and forget: the caller is about to persist this next to the
     * result, and a second reader must not resurrect a reason from a previous
     * attempt.
     */
    fun take(scope: String): ModelRefreshFailure? =
        if (scope.isEmpty()) null else last.remove(scope)

    /** Successful refresh — the previous reason is no longer true. */
    fun clear(scope: String) {
        if (scope.isEmpty()) return
        last.remove(scope)
    }

    /** Test seam: drop every recorded reason. */
    internal fun resetForTests() {
        last.clear()
    }
}
