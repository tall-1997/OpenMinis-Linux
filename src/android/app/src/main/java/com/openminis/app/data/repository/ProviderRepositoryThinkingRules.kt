package com.openminis.app.data.repository

import com.openminis.app.data.toRule
import com.openminis.app.provider.thinking.ThinkingRuleResolver
import kotlinx.coroutines.withContext

/**
 * [T-android-thinking-rules-phase2] Thinking-rules persistence helpers for
 * [ProviderRepository], extracted to keep the repository inside its
 * architecture-policy line ceiling. They are plain extensions so the public
 * members on the repository stay the only entry points callers need.
 *
 * [OpenMinis#306] The cache is filled INLINE before cold-start signals
 * config-loaded, and every fill failure is recorded on the resolver
 * ([ThinkingRuleResolver.markCustomRulesLoadFailed]) so the request path's
 * fallback to built-in defaults is visible instead of silent.
 */

internal suspend fun ProviderRepository.loadThinkingRulesSnapshot(
    instanceId: String,
): ProviderRepository.ThinkingRulesSnapshot = withContext(kotlinx.coroutines.Dispatchers.IO) {
    ensureConfigLoaded()
    val customRules = runCatching {
        providerDao.loadThinkingRules(instanceId).map { it.toRule() }
    }.getOrDefault(emptyList())
    val builtIns = builtInThinkingRulesForDisplay(instanceId)
    val sampleModelId = _config.value.modelEntries
        .firstOrNull { it.providerInstanceId == instanceId }?.model?.id
    val state = ThinkingRuleResolver.customRulesState
    ProviderRepository.ThinkingRulesSnapshot(
        customRules = customRules,
        builtIns = builtIns,
        sampleModelId = sampleModelId,
        // [OpenMinis#306] Only FAILED / NOT_LOADED is worth a banner;
        // LOADED with zero rules is a legitimate empty state.
        loadError = when (state) {
            ThinkingRuleResolver.CustomRuleCacheState.FAILED ->
                ThinkingRuleResolver.customRulesLoadError
                    ?: "Custom thinking rules could not be loaded; built-in defaults are in use."
            ThinkingRuleResolver.CustomRuleCacheState.NOT_LOADED ->
                "Custom thinking rules are still loading; built-in defaults are in use."
            ThinkingRuleResolver.CustomRuleCacheState.LOADED -> null
        },
        cacheReady = state == ThinkingRuleResolver.CustomRuleCacheState.LOADED,
    )
}

/**
 * [OpenMinis#306] Fill the custom-rule cache INLINE (suspend) instead of
 * queueing it on the persistence worker, so the cold-start config loader can
 * await it before signalling config-loaded. Failures keep the built-in
 * defaults and are surfaced through [ThinkingRuleResolver.markCustomRulesLoadFailed].
 */
internal suspend fun ProviderRepository.fillThinkingRulesCacheInline() {
    runCatching {
        val rows = withContext(kotlinx.coroutines.Dispatchers.IO) {
            providerDao.loadAllThinkingRules()
        }
        val byInstance = rows.groupBy { it.providerInstanceId }
            .mapValues { (_, rs) -> rs.sortedBy { it.sortOrder }.map { it.toRule() } }
        thinkingRulesCache = byInstance
        ThinkingRuleResolver.setAllCustomRules(byInstance)
        ThinkingRuleResolver.markCustomRulesLoaded()
    }.onFailure {
        android.util.Log.w("ProviderRepo", "[ThinkingRules] inline warm failed: ${it.message}")
        ThinkingRuleResolver.markCustomRulesLoadFailed(it.message ?: it.javaClass.simpleName)
    }
}

/**
 * Re-warm the rule cache after a mutation. Reloads every instance's rows rather
 * than just [instanceId] so a rename/reorder can never leave a stale entry
 * behind; the table is small (user-authored rules only). Failures stay visible
 * ([OpenMinis#306]): the saved rule is in Room but not in the request-path
 * cache.
 */
internal suspend fun ProviderRepository.republishThinkingRulesCache(instanceId: String) {
    runCatching {
        val rows = providerDao.loadAllThinkingRules()
            .groupBy { it.providerInstanceId }
            .mapValues { (_, rs) -> rs.sortedBy { it.sortOrder }.map { it.toRule() } }
        thinkingRulesCache = rows
        ThinkingRuleResolver.setAllCustomRules(rows)
        ThinkingRuleResolver.markCustomRulesLoaded()
    }.onFailure {
        android.util.Log.w(
            "ProviderRepo",
            "[ThinkingRules] republish after $instanceId failed: ${it.message}",
        )
        ThinkingRuleResolver.markCustomRulesLoadFailed(it.message ?: it.javaClass.simpleName)
    }
}
