package com.openminis.app.data.repository

import android.content.Context

/**
 * Outcome of one model-list fetch, per provider instance.
 *
 * Lives beside [ProviderRefreshMarks] because that store is the only thing that
 * persists these: a success clears the mark, every other case stays visible
 * until the next success.
 */
enum class ModelRefreshResult {
    SUCCESS_API,
    /**
     * [port-upstream-1.16][FIX-261025-18] The vendor's own list was not usable
     * (error or 0 models), but the provider type's built-in catalog was
     * (re-)seeded — a success for the picker, with a distinct UI wording.
     */
    SUCCESS_BUILTIN,
    NO_KEY, PRESERVED, FAILURE,
    /** 404 from /v1/models */ NOT_FOUND,
    /** DNS / TLS / timeout */ CONNECTION_FAILED,
    /** 2xx body not valid JSON */ INVALID_RESPONSE,
}

/** Persisted model-refresh marks. Success clears the mark; failures stay until the next success. */
object ProviderRefreshMarks {
    private const val PREFS = "provider_refresh_marks"

    fun load(context: Context): Map<String, ModelRefreshResult> {
        val prefs = com.openminis.app.config.Prefs.file(PREFS)
        return prefs.snapshot().mapNotNull { (id, raw) ->
            val name = raw as? String ?: return@mapNotNull null
            val result = runCatching { ModelRefreshResult.valueOf(name) }.getOrNull() ?: return@mapNotNull null
            if (result == ModelRefreshResult.SUCCESS_API || result == ModelRefreshResult.SUCCESS_BUILTIN) null else id to result
        }.toMap()
    }

    fun get(context: Context, instanceId: String): ModelRefreshResult? = load(context)[instanceId]

    fun record(context: Context, instanceId: String, result: ModelRefreshResult) {
        val prefs = com.openminis.app.config.Prefs.file(PREFS)
        if (result == ModelRefreshResult.SUCCESS_API || result == ModelRefreshResult.SUCCESS_BUILTIN) prefs.remove(instanceId) else prefs.set(instanceId, result.name)
    }

    fun recordAll(context: Context, results: List<Pair<String, ModelRefreshResult>>) {
        val prefs = com.openminis.app.config.Prefs.file(PREFS)
        // 一次事务写完全部结果：刷新是批量完成的，中途失败会留下「一半新一半旧」的
        // 状态，UI 上表现为部分 provider 显示刷新成功、部分还是旧值
        prefs.write {
            for ((id, result) in results) {
                if (result == ModelRefreshResult.SUCCESS_API || result == ModelRefreshResult.SUCCESS_BUILTIN) remove(id) else putString(id, result.name)
            }
        }
    }
}
