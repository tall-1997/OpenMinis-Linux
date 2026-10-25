package com.openminis.app.data.repository

import com.openminis.app.provider.BuiltinFreeLane
import com.openminis.app.provider.BuiltinFreeLanes
import kotlinx.coroutines.launch

/**
 * [ProviderRepository.restoreBuiltInFreeLane] plus the catalogue refetch the
 * recovery channel promises.
 *
 * An extension rather than a member because ProviderRepository sits at its
 * architecture ceiling, and because all three recovery entries — the providers
 * list footer, the add-provider preset row, and the model list footer — must not
 * each reinvent the pair. A caller that restores without refetching leaves an
 * instance with zero model rows on screen, which reads exactly like a failed
 * restore even though the config write succeeded.
 *
 * The config write and the credential write are synchronous and complete when
 * this returns; only the network fetch is deferred, onto the repository's own IO
 * scope, so no caller needs a coroutine scope of its own. A false return means
 * there was nothing to restore (the instance and its rows were already there),
 * and no refresh is fired — the caller's own list already reflects reality.
 */
internal fun ProviderRepository.restoreBuiltInFreeLaneAndRefresh(builtin: BuiltinFreeLane) {
    if (!restoreBuiltInFreeLane(builtin)) return
    writeScope.launch {
        instance(builtin.instanceId)?.let { refreshModels(it) }
    }
}

/**
 * The built-in lanes this device has no instance for — what the recovery rows in
 * the providers list and the add-provider presets are built from.
 *
 * An extension because [ProviderRepository] sits at its architecture ceiling and
 * this needs nothing private: recognition goes through [FreeLaneBootstrap.belongsTo],
 * so a user-built provider at the same host with a real key counts as "present"
 * and suppresses the row, exactly as it suppresses the seed.
 */
internal fun ProviderRepository.missingBuiltInFreeLanes(): List<BuiltinFreeLane> {
    val snapshot = config.value
    return BuiltinFreeLanes.ALL.filter { builtin ->
        snapshot.instances.none { FreeLaneBootstrap.belongsTo(builtin, it, loadApiKey(it.id)) }
    }
}
