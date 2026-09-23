package com.openminis.app.data.repository

import android.content.Context

/** Persisted model-refresh marks. Success clears the mark; failures stay until the next success. */
object ProviderRefreshMarks {
    private const val PREFS = "provider_refresh_marks"

    fun load(context: Context): Map<String, ModelRefreshResult> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.all.mapNotNull { (id, raw) ->
            val name = raw as? String ?: return@mapNotNull null
            val result = runCatching { ModelRefreshResult.valueOf(name) }.getOrNull() ?: return@mapNotNull null
            if (result == ModelRefreshResult.SUCCESS_API) null else id to result
        }.toMap()
    }

    fun get(context: Context, instanceId: String): ModelRefreshResult? = load(context)[instanceId]

    fun record(context: Context, instanceId: String, result: ModelRefreshResult) {
        val edit = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (result == ModelRefreshResult.SUCCESS_API) edit.remove(instanceId) else edit.putString(instanceId, result.name)
        edit.apply()
    }

    fun recordAll(context: Context, results: List<Pair<String, ModelRefreshResult>>) {
        val edit = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        for ((id, result) in results) {
            if (result == ModelRefreshResult.SUCCESS_API) edit.remove(id) else edit.putString(id, result.name)
        }
        edit.apply()
    }
}
