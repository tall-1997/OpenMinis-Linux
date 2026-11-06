package com.openminis.app.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * [FIX-R2-16, review §三 失败处理] Load-failure latch for the provider
 * configuration. While the config is unreadable (corrupt decode, DB-version
 * guard), [ProviderRepository]'s 12 mutation guards used to no-op in silence —
 * and [ProviderConfigStore.ensureConfigLoaded]'s real-failure path THROWS,
 * which used to reach the Compose click handlers uncaught (AddProviderScreen
 * save = crash). This guard converts the throw into a logged no-op (returns
 * `false`, so the guards abort as designed) and latches [failed], which the
 * provider list surfaces as a "changes are locked" banner; the state clears on
 * the first successful load, so the banner stays truthful instead of permanent.
 *
 * The store's OTHER `false` return — a benign main-thread refusal during the
 * cold-start window, which self-heals on the next access — must NOT latch the
 * failure state: it is indistinguishable from "not loaded YET", not "broken".
 *
 * Extracted from ProviderRepository to keep that file under its architecture
 * line ceiling; stateless besides the latch, so the delegation is lossless.
 */
internal class ProviderConfigLoadGuard {
    private val _failed = MutableStateFlow(false)

    /** True while the configuration cannot be loaded; clears on the first success. */
    val failed: StateFlow<Boolean> get() = _failed

    fun ensure(store: ProviderConfigStore): Boolean = try {
        store.ensureConfigLoaded().also { ok ->
            if (ok) _failed.value = false
        }
    } catch (e: IllegalStateException) {
        if (!_failed.value) {
            android.util.Log.w(
                "ProviderRepo",
                "[ProviderStore] config load failed: config-protected changes no-op until a load succeeds (${e.cause?.message ?: e.message})",
            )
        }
        _failed.value = true
        false
    }
}
