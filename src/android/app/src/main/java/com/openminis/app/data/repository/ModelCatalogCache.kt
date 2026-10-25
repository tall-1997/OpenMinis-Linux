package com.openminis.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.model.VoiceProviderTemplate
import com.openminis.app.data.model.hasVoiceModality
import com.openminis.app.provider.ModelsDevApi
import com.openminis.app.provider.anthropic.AnthropicModelsApi
import com.openminis.app.provider.gemini.GeminiModelsApi
import com.openminis.app.provider.openai.OpenAIModelsApi
import com.openminis.app.provider.openrouter.OpenRouterModelsApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Model-catalog slice of [ProviderRepository], split out of it verbatim: the
 * per-instance models.dev/vendor catalog cache bookkeeping (TTL staleness,
 * `lastFetchAt` prefs marks, invalidation) and the whole catalog refresh
 * pipeline (provider /models fetch, OAuth token rotation, hard-clear,
 * models.dev fallback, background + daily sweeps). [ProviderRepository]
 * constructs this cache, holds it privately, and keeps every previously-public
 * member reachable as a one-line delegation — external callers are unchanged.
 *
 * Shared state is explicit: the in-memory config, its lock, the persistence
 * worker and the secret store stay on [ProviderRepository] (or its
 * [ProviderConfigStore]) and are reached through the [repo] constructor
 * reference; the `lastFetchAt` / daily-refresh SharedPreferences handle is
 * passed in as its own constructor parameter. No static mutable state.
 */
internal class ModelCatalogCache(
    private val repo: ProviderRepository,
    private val context: Context,
    private val prefs: SharedPreferences,
) {
    private companion object {
        /**
         * Per-instance model-cache TTL. Matches iOS's daily calendar-day
         * refresh window (24h rolling here — simpler than calendar-day math
         * and the behavioral difference at midnight is negligible). Used by
         * [triggerBackgroundRefreshIfStale] to decide whether a UI-triggered
         * stale-while-revalidate refresh should fire.
         */
        private const val MODEL_CACHE_TTL_MS = 24 * 60 * 60 * 1000L

        /** Per-instance `lastFetchAt` pref key. */
        private fun lastFetchKey(instanceId: String) = "modelsLastFetchAt_$instanceId"
    }

    /** Record the time of a successful model fetch for [instanceId]. */
    internal fun markInstanceFetched(instanceId: String) {
        prefs.edit().putLong(lastFetchKey(instanceId), System.currentTimeMillis()).apply()
    }

    /** Whether the cached model list for [instanceId] is older than the TTL. */
    private fun isInstanceStale(instanceId: String): Boolean {
        val last = prefs.getLong(lastFetchKey(instanceId), 0L)
        return last == 0L || (System.currentTimeMillis() - last) > MODEL_CACHE_TTL_MS
    }

    /**
     * Clear the stored `lastFetchAt` for [instanceId] so the next
     * [triggerBackgroundRefreshIfStale] / [refreshAllModelsIfNeeded] call
     * refreshes it immediately. Called when instance config changes in ways
     * that could alter the set of available models (e.g. base URL rewrite,
     * credential swap). Mirrors iOS's implicit invalidation on
     * `updateInstance` / `removeInstance`.
     */
    fun invalidateModelCache(instanceId: String) {
        prefs.edit().remove(lastFetchKey(instanceId)).apply()
        // [T-key-cache-invalidate] 模型刷新后旧 ProviderFactory 的 memo
        // 缓存必须作废 —— 否则已构造的 provider 带着旧 key/旧配置继续
        // 存活，表现为“刷新了模型但发送用的还是旧 key”。
        // [T-provider-factory-race] 只清本实例：invalidateAll 会连坐清掉
        // 无关实例、反复重建整组 OkHttpClient。
        com.openminis.app.provider.ProviderFactory.invalidateInstance(instanceId)
    }

    /**
     * Drop fetched (non-custom, non voice-seed) entries so a manual refresh
     * cannot keep models the provider no longer returns. Voice template seeds
     * stay: vendors such as MiMo do not list ASR/TTS on /v1/models.
     */
    private fun clearFetchedModelEntries(instanceId: String): Set<String> {
        synchronized(repo.configLock) {
            repo.ensureConfigLoaded()
            val config = repo.workingCopy()
            val instance = config.instances.firstOrNull { it.id == instanceId } ?: return emptySet()
            val voiceIds = VoiceProviderTemplate.template(instance.customBaseURL)
                ?.mockModels
                ?.filter { it.hasVoiceModality }
                ?.map { it.id }
                ?.toSet()
                ?: emptySet()
            val removed = config.modelEntries.filter {
                it.providerInstanceId == instanceId && !it.isCustom && it.baseModel.id !in voiceIds
            }
            if (removed.isEmpty()) return emptySet()
            val removedIds = removed.map { it.id }.toSet()
            config.modelEntries.removeAll { it.id in removedIds }
            repo.scrubConfigEntryRefs(config, removedIds)
            repo.saveConfig(config)
            return removedIds
        }
    }

    suspend fun refreshModels(
        instance: ProviderInstance,
        forceRefresh: Boolean = false,
        clearFirst: Boolean = false,
    ): ModelRefreshResult {
        val liveForce = forceRefresh || clearFirst
        // [T-refresh-no-destructive-clear] `clearFirst` used to wipe this
        // instance's model entries BEFORE the network call. A hard refresh does
        // have to clear first — that is what stops `replaceEntries` from
        // carrying forward uuid / overrides / isHidden, so the catalog comes
        // back pristine — but doing it up front means any failed fetch leaves
        // the picker empty. And because `clearFirst` implies `liveForce`, the
        // models.dev fallback below is skipped as well, so pressing Refresh
        // against a flaky or strict gateway *destroyed* the list it was meant to
        // reload, with no way back short of re-adding the provider.
        //
        // Deferred to the moment a replacement is actually in hand, and run at
        // most once. On success the observable result is byte-identical to the
        // old order: entries are gone before `replaceEntries` runs, so nothing
        // is carried forward and `pruned` is empty exactly as before.
        var hardCleared = false
        suspend fun hardClearIfNeeded() {
            if (!clearFirst || hardCleared) return
            hardCleared = true
            val clearedIds = clearFetchedModelEntries(instance.id)
            if (clearedIds.isNotEmpty()) repo.scrubExternalEntryRefs(clearedIds)
        }
        var lastFetchError: ModelRefreshResult? = null
        // [T-android-refresh-models-empty-key] usableApiKey, NOT loadApiKey.
        //
        // A self-hosted OpenAI/Anthropic-compatible endpoint (ollama, LM
        // Studio, LiteLLM, an unauthenticated internal gateway) legitimately
        // has no key, so nothing is stored and `loadApiKey` returns null. Step
        // 1 below is gated on `apiKey != null`, so refresh skipped the provider
        // call entirely and fell through to the models.dev lookup — which knows
        // nothing about a private host — leaving the user with a failure whose
        // message described the fallback, not the server they were pointing at.
        //
        // `usableApiKey` substitutes "" exactly where an empty key is a valid
        // configuration (`ProviderInstance.allowsEmptyAPIKey`: apiKey mode +
        // custom base URL + OpenAI/Anthropic family). It still returns null for
        // an official endpoint with no key, and for an OAuth instance with no
        // token — both of which must keep short-circuiting — so the "provider
        // types that genuinely require a key" case is unchanged.
        //
        // This also makes refresh agree with the send path: ChatViewModel and
        // VisionGroupResolver already resolve credentials through
        // `usableApiKey`, so a keyless local server could be chatted with but
        // not refreshed. That inconsistency was the bug.
        var apiKey = repo.usableApiKey(instance)

        // For OAuth providers, try to refresh the token before using it (mirrors iOS validAccessToken)
        if (instance.credentialType == com.openminis.app.data.model.ProviderCredential.oauth && apiKey != null) {
            try {
                val manager = com.openminis.app.auth.OAuthManager.forInstance(context, instance)
                val freshToken = manager?.validAccessToken()
                if (freshToken != null && freshToken != apiKey) {
                    repo.saveApiKey(instance.id, freshToken)
                    apiKey = freshToken
                    android.util.Log.i("ProviderRepo", "refreshModels: OAuth token refreshed")
                }
            } catch (e: Exception) {
                android.util.Log.w("ProviderRepo", "OAuth token refresh failed: ${e.message}")
            }
        }

        android.util.Log.i("ProviderRepo", "refreshModels: id=${instance.id} type=${instance.providerType} credential=${instance.credentialType} hasKey=${apiKey != null} keyLen=${apiKey?.length ?: 0} baseURL=${instance.effectiveBaseURL} forceRefresh=$forceRefresh")

        // OpenAI Codex OAuth: use static model list (OAuth tokens can't call /v1/models)
        if (instance.providerType == ProviderType.openAI
            && instance.credentialType == ProviderCredential.oauth
        ) {
            val models = OpenAIModelsApi.fetchModelsOAuth()
            if (models.isNotEmpty()) {
                hardClearIfNeeded()
                repo.replaceEntries(instance.id, models)
                return ModelRefreshResult.SUCCESS_API
            }
        }

        // Step 1: Try provider API (requires API key)
        val customBase = instance.customBaseURL
        val isThirdParty = customBase != null
            && !customBase.lowercase().let {
                it.contains("api.openai.com") || it.contains("chatgpt.com") || it.contains("openrouter.ai")
            }

        if (apiKey != null) {
            val baseURL = instance.effectiveBaseURL
            val models = try {
                when (instance.providerType) {
                    ProviderType.anthropic -> AnthropicModelsApi.fetchModels(
                        apiKey, baseURL,
                        isOAuth = instance.credentialType == com.openminis.app.data.model.ProviderCredential.oauth,
                        context = context,
                        forceRefresh = liveForce,
                        // [T-provider-custom-user-agent] models-list UA override.
                        customUserAgent = instance.customUserAgent,
                        cacheScope = instance.id,
                    )
                    ProviderType.gemini -> GeminiModelsApi.fetchModels(
                        apiKey,
                        isOAuth = instance.credentialType == com.openminis.app.data.model.ProviderCredential.oauth,
                        context = context,
                        forceRefresh = liveForce,
                        cacheScope = instance.id,
                    )
                    // [T-provider-custom-user-agent] models-list UA override.
                    // [T-android-provider-type-parity] openAIResponses lists
                    // models from the same /v1/models endpoint — only the
                    // completion endpoint differs.
                    ProviderType.openAI, ProviderType.openAIResponses ->
                        OpenAIModelsApi.fetchModels(apiKey, baseURL, context = context, forceRefresh = liveForce, customUserAgent = instance.customUserAgent, cacheScope = instance.id)
                    ProviderType.openRouter -> OpenRouterModelsApi.fetchModels(apiKey, context = context, forceRefresh = liveForce, cacheScope = instance.id)
                    // [T-provider-dynamic-catalog-reconcile] xAI: fetch the live
                    // catalog, fall back to the built-in list.
                    //
                    // This used to return the static list unconditionally, with
                    // a comment saying a dynamic endpoint was hypothetical ("if
                    // xAI later exposes a dynamic /v1/models endpoint this is
                    // the place"). It exists and is OpenAI-compatible: an
                    // unauthenticated GET https://api.x.ai/v1/models answers 401
                    // with `{"code":"invalid-argument","error":"Incorrect API
                    // key…"}` — an auth challenge, not a 404. So the built-in
                    // set was the ONLY set anyone could ever get, on both the
                    // OAuth and the API-key path, and any model shipped after a
                    // release (GH#265: grok-4.6) stayed invisible until the app
                    // itself was updated. Manual Refresh could not help because
                    // it runs this same code.
                    //
                    // `fetchModels` treats api.x.ai as a custom base, so on any
                    // failure it returns EMPTY rather than a wrong-vendor guess;
                    // the `models.isNotEmpty()` gate below then leaves the
                    // seeded catalog in place. That makes the live call additive
                    // — it can enrich the list but never empty it.
                    //
                    // The `?:` default is load-bearing, not cosmetic: an xAI
                    // instance added through the OAuth button carries no
                    // customBaseURL, so `effectiveBaseURL` is null — and
                    // `OpenAIModelsApi.buildURL(null)` resolves to
                    // api.openai.com/v1/models. Passing the null straight
                    // through would send an xAI token to OpenAI and, on the
                    // official-endpoint path, fall back to `LLMModel.allOpenAI`,
                    // i.e. show GPT models under a Grok provider. That is the
                    // same class of bug the addInstance seeding comment already
                    // warns about ("Refresh on Grok returns GPT-5.5").
                    ProviderType.xAI -> OpenAIModelsApi.fetchModels(
                        apiKey,
                        baseURL ?: "https://api.x.ai/v1",
                        context = context,
                        forceRefresh = liveForce,
                        customUserAgent = instance.customUserAgent,
                        cacheScope = instance.id,
                    ).ifEmpty { com.openminis.app.provider.xai.XAIModelsApi.fetchModelsOAuth() }
                    // [T-kimi-oauth] Kimi Code: unlike Codex OAuth, the Kimi
                    // OAuth token CAN call the models endpoint — real fetch
                    // from GET /coding/v1/models (OpenAI-compatible shape).
                    // The upstream lineup shifts across generations, so the
                    // live list replaces the minimal built-in fallback.
                    ProviderType.kimiCode -> OpenAIModelsApi.fetchModels(
                        apiKey,
                        baseURL ?: "${com.openminis.app.auth.KimiDeviceFlow.CODING_API_BASE}/v1",
                        context = context,
                        forceRefresh = liveForce,
                        customUserAgent = instance.customUserAgent,
                        cacheScope = instance.id,
                    )
                    // [T-android-provider-type-parity] No models endpoint to
                    // query for a type this build cannot drive; the instance
                    // keeps whatever entries the restore brought with it.
                    ProviderType.antigravity, ProviderType.unsupported -> emptyList()
                }
            } catch (e: Exception) {
                android.util.Log.e("ProviderRepo", "refreshModels fetch error: ${e.message}", e)
                // 细分错误原因，存到可变变量里，供后面的空列表分支返回。
                lastFetchError = when {
                    e is java.net.ConnectException || e is java.net.SocketTimeoutException ||
                        e is java.net.UnknownHostException || e is javax.net.ssl.SSLException ||
                        e.message?.contains("Unable to resolve host") == true ||
                        e.message?.contains("Connection refused") == true ||
                        e.message?.contains("connect timed out") == true ||
                        e.message?.contains("timeout") == true ||
                        e.message?.contains("Network is unreachable") == true ->
                        ModelRefreshResult.CONNECTION_FAILED
                    e is java.io.FileNotFoundException || e.message?.contains("404") == true ||
                        e.message?.contains("Not Found") == true ->
                        ModelRefreshResult.NOT_FOUND
                    else -> ModelRefreshResult.FAILURE
                }
                emptyList()
            }
            android.util.Log.i("ProviderRepo", "refreshModels: got ${models.size} models")

            // Step 2: If API returned results, use them
            if (models.isNotEmpty()) {
                hardClearIfNeeded()
                val pruned = repo.replaceEntries(instance.id, models, dropStaleRefs = clearFirst)
                if (pruned.isNotEmpty()) repo.scrubExternalEntryRefs(pruned)
                return ModelRefreshResult.SUCCESS_API
            }
        }

        // Explicit force refresh must not pretend a hostname-keyed models.dev
        // catalog is a per-credential refresh. Same API address used to make
        // every sibling provider look freshly synced.
        if (liveForce) {
            return if (apiKey == null) ModelRefreshResult.NO_KEY
            else lastFetchError ?: ModelRefreshResult.FAILURE
        }

        // Step 3: Fallback to models.dev by base URL.
        // We try this even for third-party hosts (DashScope/Bailian, etc.) — the
        // models.dev registry covers many "Anthropic-compatible" or "OpenAI-compatible"
        // gateways by hostname, and when there's no match `fetchModels` returns
        // empty so the existing list (vLLM/Ollama on a private host) is preserved.
        val fallbackBaseURL = modelsDevBaseURL(instance)
        val fallbackModels = ModelsDevApi.fetchModels(fallbackBaseURL)
        val curatedFallback = fallbackModels
        if (curatedFallback.isNotEmpty()) {
            android.util.Log.i("ProviderRepo", "models.dev fallback returned ${curatedFallback.size} models for ${instance.label}")
            repo.replaceEntries(instance.id, curatedFallback)
            return ModelRefreshResult.SUCCESS_API
        } else if (isThirdParty) {
            android.util.Log.i("ProviderRepo", "Third-party endpoint, no models.dev match — preserving existing models for ${instance.label}")
            return ModelRefreshResult.PRESERVED
        }
        return if (apiKey == null) ModelRefreshResult.NO_KEY else ModelRefreshResult.FAILURE
    }

    /**
     * Auto-refresh variant: skips instances where the user has added custom models,
     * so we never overwrite hand-edited entries. Mirrors iOS `autoRefreshModels(for:)`.
     */
    private suspend fun autoRefreshModels(instance: ProviderInstance) {
        val hasCustom = repo._config.value.modelEntries.any {
            it.providerInstanceId == instance.id && it.isCustom
        }
        if (hasCustom) {
            android.util.Log.i("ProviderRepo", "[ModelList] autoRefresh SKIP ${instance.label} — has custom models")
            return
        }
        refreshModels(instance)
    }

    /**
     * Parallel force-refresh of every enabled provider. Used by the provider-list
     * toolbar Sync button. models.dev fallback is still used inside [refreshModels].
     */
    suspend fun refreshAllModelsForce(): List<Pair<String, ModelRefreshResult>> {
        repo.awaitConfigLoaded()
        val enabled = repo._config.value.instances.filter { it.isEnabled }
        if (enabled.isEmpty()) return emptyList()
        return coroutineScope {
            enabled.map { inst ->
                async {
                    inst.id to runCatching {
                        refreshModels(inst, forceRefresh = true)
                    }.getOrElse { ModelRefreshResult.FAILURE }
                }
            }.awaitAll()
        }
    }

    /**
     * Stale-while-revalidate helper for UI code. Callers (e.g. the model
     * picker sheet) invoke this on open: the currently-persisted
     * `config.modelEntries` is returned immediately via the StateFlow
     * (no waiting), and if any instance's cache is older than
     * [MODEL_CACHE_TTL_MS] we kick a background refresh that updates the
     * StateFlow when the network fetch completes.
     *
     * Concurrent-call safe: per-instance `autoRefreshModels` is idempotent
     * and the global daily-refresh flag prevents cold-start double fetch.
     * This helper bypasses the daily flag because it runs per-instance — it's
     * meant for targeted "user is looking at this picker now" revalidation.
     */
    fun triggerBackgroundRefreshIfStale(scope: kotlinx.coroutines.CoroutineScope) {
        val stale = repo._config.value.instances.filter { it.isEnabled && isInstanceStale(it.id) }
        if (stale.isEmpty()) return
        android.util.Log.i("ProviderRepo", "[ModelList] SWR refresh — ${stale.size} stale instance(s)")
        for (instance in stale) {
            scope.launch { autoRefreshModels(instance) }
        }
    }

    /**
     * Refresh model lists for all enabled instances, at most once per calendar day.
     * Mirrors iOS `refreshAllModelsIfNeeded()` — called from Application.onCreate.
     * Refreshes run in parallel; failures are logged but don't block other instances.
     */
    fun refreshAllModelsIfNeeded(scope: kotlinx.coroutines.CoroutineScope) {
        val key = "lastModelsRefreshDate"
        val lastMs = prefs.getLong(key, 0L)
        val now = System.currentTimeMillis()
        if (lastMs > 0L && isSameCalendarDay(lastMs, now)) {
            android.util.Log.i("ProviderRepo", "[ModelList] refreshAllModelsIfNeeded SKIP — already refreshed today")
            return
        }

        // [T-android-startup-config-stall] Config now loads asynchronously, so
        // at cold start the config may still be the empty placeholder when
        // this fires from MinisApp.onCreate. Wait for the load before reading
        // the enabled-instance set, otherwise the daily refresh would no-op on
        // "no enabled instances" and skip this launch entirely. Runs on the
        // caller's (IO) scope — does not touch the main thread.
        scope.launch {
            repo.awaitConfigLoaded()
            val enabled = repo._config.value.instances.filter { it.isEnabled }
            if (enabled.isEmpty()) {
                android.util.Log.i("ProviderRepo", "[ModelList] refreshAllModelsIfNeeded SKIP — no enabled instances")
                return@launch
            }

            android.util.Log.i("ProviderRepo", "[ModelList] refreshAllModelsIfNeeded FIRE — ${enabled.size} instances")
            prefs.edit().putLong(key, now).apply()

            for (instance in enabled) {
                scope.launch { autoRefreshModels(instance) }
            }
        }
    }

    private fun isSameCalendarDay(aMs: Long, bMs: Long): Boolean {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = aMs
        val aYear = cal.get(java.util.Calendar.YEAR)
        val aDay = cal.get(java.util.Calendar.DAY_OF_YEAR)
        cal.timeInMillis = bMs
        return aYear == cal.get(java.util.Calendar.YEAR)
            && aDay == cal.get(java.util.Calendar.DAY_OF_YEAR)
    }

    /** Resolve the models.dev lookup base URL for an instance. */
    private fun modelsDevBaseURL(instance: ProviderInstance): String {
        instance.effectiveBaseURL?.let { return it }
        return when (instance.providerType) {
            ProviderType.anthropic -> "https://api.anthropic.com/v1"
            ProviderType.gemini -> "https://generativelanguage.googleapis.com"
            // [T-android-provider-type-parity] Responses API instances point at
            // the same OpenAI host; only the completion path differs.
            ProviderType.openAI, ProviderType.openAIResponses -> "https://api.openai.com/v1"
            ProviderType.openRouter -> "https://openrouter.ai/api/v1"
            ProviderType.xAI -> "https://api.x.ai/v1"
            ProviderType.kimiCode -> "${com.openminis.app.auth.KimiDeviceFlow.CODING_API_BASE}/v1"
            // No canonical host for a type this build cannot drive. Callers
            // reaching here have already exhausted effectiveBaseURL.
            ProviderType.antigravity, ProviderType.unsupported -> "https://api.openai.com/v1"
        }
    }
}
