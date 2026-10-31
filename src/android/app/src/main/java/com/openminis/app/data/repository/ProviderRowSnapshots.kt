package com.openminis.app.data.repository

import com.openminis.app.auth.OAuthManager
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderRowSnapshot
import com.openminis.app.data.model.ProviderRowSnapshotCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Row snapshots — [T-provider-row-snapshot-cache] computed once per
 * config/credential revision and cached in-process, so re-entering the
 * provider list renders the first frame from the cache instead of flashing
 * "加载中" on every row while the IO reads land.
 */
private val rowSnapshotCache = ProviderRowSnapshotCache()

fun ProviderRepository.cachedRowSnapshots(
    instances: List<ProviderInstance>,
    configRevision: Long,
    credentialRevision: Long,
): Map<String, ProviderRowSnapshot>? = rowSnapshotCache.cached(
    ProviderRowSnapshotCache.keyOf(configRevision, credentialRevision, instances.map { it.id }),
)

/**
 * One bounded IO snapshot for the whole page, keyed and cached by the
 * revisions it was computed from. OAuth state can drift without any
 * revision event, so the caller still recomputes in the background — the
 * cache only removes the first-frame flash.
 */
suspend fun ProviderRepository.computeRowSnapshots(
    instances: List<ProviderInstance>,
    credentialRevision: Long,
): Map<String, ProviderRowSnapshot> = withContext(Dispatchers.IO) {
    val snapshots = instances.associate { instance ->
        val apiKey = loadApiKey(instance.id)
        val configured = if (instance.credentialType == ProviderCredential.oauth) {
            val mgr = OAuthManager.forInstance(context, instance)
            mgr?.isAuthenticated() == true
        } else {
            !apiKey.isNullOrBlank() || instance.allowsEmptyAPIKey
        }
        instance.id to ProviderRowSnapshot(
            loaded = true,
            modelCount = visibleEntries(instance.id).size,
            apiKey = apiKey,
            isConfigured = configured,
            needsReauth = OAuthManager.needsReauth(context, instance.id),
        )
    }
    rowSnapshotCache.store(
        ProviderRowSnapshotCache.keyOf(
            config.value.revision,
            credentialRevision,
            instances.map { it.id },
        ),
        snapshots,
    )
    snapshots
}
