package com.openminis.app.data.model

/**
 * Per-row snapshot for the provider list screen: what each row needs to
 * render without blocking on its own IO reads. Computed once per
 * config/credential revision (see [ProviderRowSnapshotCache]) and consumed
 * by the UI.
 */
data class ProviderRowSnapshot(
    val loaded: Boolean = false,
    val modelCount: Int = 0,
    val apiKey: String? = null,
    val isConfigured: Boolean = false,
    val needsReauth: Boolean = false,
)

/**
 * Process-level cache keyed by config revision, credential revision and the
 * instance-id set. Any real change (save/delete key, instance add/remove/
 * rename) changes the key and invalidates the cache.
 */
class ProviderRowSnapshotCache {
    private var cached: Pair<String, Map<String, ProviderRowSnapshot>>? = null

    fun cached(key: String): Map<String, ProviderRowSnapshot>? {
        val hit = cached ?: return null
        return if (hit.first == key) hit.second else null
    }

    fun store(key: String, snapshots: Map<String, ProviderRowSnapshot>) {
        cached = key to snapshots
    }

    companion object {
        fun keyOf(
            configRevision: Long,
            credentialRevision: Long,
            instanceIds: List<String>,
        ): String = "$configRevision|$credentialRevision|" + instanceIds.joinToString(",")
    }
}
