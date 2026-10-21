package com.openminis.app.data.repository

import android.content.Context

/**
 * Prefs file owned by [ProviderRepository] for provider configuration. Top-level
 * so the (size-frozen) repository can name it without a line-wrapping detour;
 * read-only from here — the success timestamp lives with the instance.
 */
internal const val PROVIDER_CONFIG_PREFS = "provider_config"

/**
 * Persisted model-refresh marks.
 *
 * Two independent facts per provider instance, both keyed by its id:
 *
 * 1. **The last result** ([ModelRefreshResult]) — success clears it, so a mark
 *    being present already means "the most recent attempt did not complete".
 * 2. **Why it failed** ([ModelRefreshFailure]) — the HTTP status, or the class
 *    of transport/payload error. Persisted rather than kept in memory because
 *    the provider screen re-reads it after process death and must not fall back
 *    to the anonymous "Refresh failed" line while the cause is known.
 *
 * [lastSuccessfulFetchAt] reads the timestamp [ProviderRepository] writes on
 * success, so the UI can say how old the list on screen actually is. Sharing
 * the key constants here is deliberate: two definitions of
 * `modelsLastFetchAt_<id>` would drift and silently report "never synced".
 */
object ProviderRefreshMarks {
    private const val PREFS = "provider_refresh_marks"

    private const val FAILURE_PREFIX = "failure_"

    internal fun lastFetchKey(instanceId: String) = "modelsLastFetchAt_$instanceId"

    private fun failureKey(instanceId: String) = "$FAILURE_PREFIX$instanceId"

    fun load(context: Context): Map<String, ModelRefreshResult> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.all.mapNotNull { (id, raw) ->
            // `failure_*` lives in the same file; it is not a result.
            if (id.startsWith(FAILURE_PREFIX)) return@mapNotNull null
            val name = raw as? String ?: return@mapNotNull null
            val result = runCatching { ModelRefreshResult.valueOf(name) }.getOrNull() ?: return@mapNotNull null
            if (result == ModelRefreshResult.SUCCESS_API) null else id to result
        }.toMap()
    }

    fun get(context: Context, instanceId: String): ModelRefreshResult? = load(context)[instanceId]

    fun record(context: Context, instanceId: String, result: ModelRefreshResult) {
        record(context, instanceId, result, ModelRefreshDiagnostics.take(instanceId))
    }

    /**
     * [T-models-refresh-failure-reason] Record the result together with the
     * reason the fetch produced nothing. Passing [failure] as null on a failure
     * result leaves the previous reason in place only when it belongs to the
     * same failure — in practice callers pass what
     * [ModelRefreshDiagnostics.take] returned, which is null when the fetcher
     * never reached a verdict.
     */
    fun record(
        context: Context,
        instanceId: String,
        result: ModelRefreshResult,
        failure: ModelRefreshFailure?,
    ) {
        val edit = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (result == ModelRefreshResult.SUCCESS_API) {
            edit.remove(instanceId)
            edit.remove(failureKey(instanceId))
        } else {
            edit.putString(instanceId, result.name)
            if (failure == null) edit.remove(failureKey(instanceId)) else edit.putString(failureKey(instanceId), encode(failure))
        }
        edit.apply()
    }

    /**
     * Drain the process-wide sink into the persisted mark. No-op when the
     * fetcher recorded nothing, which keeps the call site a single line inside
     * the size-frozen repository.
     */
    fun captureFailure(context: Context, instanceId: String) {
        val failure = ModelRefreshDiagnostics.take(instanceId) ?: return
        val result = get(context, instanceId) ?: ModelRefreshResult.FAILURE
        record(context, instanceId, result, failure)
    }

    /** The persisted reason, or null when the last failure carried no detail. */
    fun failureOf(context: Context, instanceId: String): ModelRefreshFailure? {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(failureKey(instanceId), null) ?: return null
        return decode(raw)
    }

    /**
     * Timestamp of the last SUCCESSFUL model fetch, or 0 when this instance has
     * never completed one. Written by [ProviderRepository.markInstanceFetched].
     */
    fun lastSuccessfulFetchAt(context: Context, instanceId: String): Long =
        context.applicationContext.getSharedPreferences(PROVIDER_CONFIG_PREFS, Context.MODE_PRIVATE)
            .getLong(lastFetchKey(instanceId), 0L)

    fun recordAll(context: Context, results: List<Pair<String, ModelRefreshResult>>) {
        val edit = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        for ((id, result) in results) {
            if (result == ModelRefreshResult.SUCCESS_API) {
                edit.remove(id)
                edit.remove(failureKey(id))
            } else {
                edit.putString(id, result.name)
                // A bulk pass has no per-instance reason; drop the stale one
                // rather than pair it with a result it may not describe.
                edit.remove(failureKey(id))
            }
        }
        edit.apply()
    }

    private fun encode(failure: ModelRefreshFailure): String = encodeFailure(failure)

    private fun decode(raw: String): ModelRefreshFailure? = decodeFailure(raw)
}

/**
 * `"KIND"` or `"KIND:status"`. A single token keeps the prefs file readable
 * with `adb shell cat` and keeps the whole failure in one keystroke of state.
 * Internal (not private) so the round-trip is testable without a Context.
 */
internal fun encodeFailure(failure: ModelRefreshFailure): String =
    failure.httpStatus?.let { "${failure.kind.name}:$it" } ?: failure.kind.name

/** Unknown kinds decode to null rather than throwing — a stale write from a
 *  future build must not crash the provider screen. */
internal fun decodeFailure(raw: String): ModelRefreshFailure? {
    val separator = raw.lastIndexOf(':')
    val kindName = if (separator < 0) raw else raw.substring(0, separator)
    val kind = runCatching { ModelRefreshFailure.Kind.valueOf(kindName) }.getOrNull() ?: return null
    val status = if (separator < 0) null else raw.substring(separator + 1).toIntOrNull()
    return ModelRefreshFailure(kind, status)
}
