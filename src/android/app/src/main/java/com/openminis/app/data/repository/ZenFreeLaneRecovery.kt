package com.openminis.app.data.repository

import kotlinx.coroutines.launch

/**
 * [ProviderRepository.restoreBuiltInZenProvider] plus the catalogue refetch the
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
internal fun ProviderRepository.restoreBuiltInZenProviderAndRefresh() {
    if (!restoreBuiltInZenProvider()) return
    writeScope.launch {
        instance(ZenFreeLaneBootstrap.INSTANCE_ID)?.let { refreshModels(it) }
    }
}
