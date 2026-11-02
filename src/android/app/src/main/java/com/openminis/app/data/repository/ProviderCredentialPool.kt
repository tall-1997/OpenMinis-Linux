package com.openminis.app.data.repository

import android.content.Context
import com.openminis.app.util.EncryptedPrefsFactory
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.ProviderInstance
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * [T-credential-pool-x] Multi-account credential pool: per-provider-instance
 * storage of N ≥ 2 API keys with process-scoped round-robin rotation and
 * per-index cooldown (rate-limit / invalid-key).
 *
 * Storage: encrypted prefs "provider_secrets", key "credpool_<instanceId>"
 * as a JSON array of plaintext keys (the same AES-GCM-encrypted shared pref
 * that already stores apikey_<id>).
 *
 * Why process-scoped cursor + cooldown (not persisted): a rotation index is
 * meaningless after restart — the load is spread over the *process*
 * lifespan, not across app installs — and a 30 min key cooldown that
 * survived a crash would make the entire pool unreachable the moment the
 * user restarted the app after a transient 429 storm.  The cursor also
 * resets when the stored list changes.
 *
 * Why a ConcurrentHashMap for cooldown: pruning happens lazily on pick
 * (stale entries are skipped and reaped when index≥ size).  No scheduled
 * cleanup thread needed for a map of < pool-size * instance-count entries.
 *
 * Thread safety: cursors are AtomicInteger; cooldownUntil is a
 * ConcurrentHashMap.  All public methods are safe to call from any thread.
 *
 * @see poolPickKey  Pure rotation (testable in isolation).
 * @see ProviderConfigStore  Policy (migration, legacy sync, quota groups).
 */
internal class ProviderCredentialPool(private val context: Context) {

    companion object {
        private const val POOL_PREFIX = "credpool_"

        /** Default cooldown when a rate-limit error carries no Retry-After. */
        const val DEFAULT_COOLDOWN_MS = 30_000L

        /** Cooldown for an invalid/revoked key (can't be budget, skip it). */
        const val INVALID_KEY_COOLDOWN_MS = 30 * 60_000L

        /** Cap on rate-limit cooldown (10 min). */
        const val RATE_LIMIT_MAX_COOLDOWN_MS = 10 * 60_000L

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    }

    // ── storage ─────────────────────────────────────────────────────────
    private val encryptedPrefs by lazy {
        EncryptedPrefsFactory.safeCreate(context, "provider_secrets")
    }

    private fun prefsKey(instanceId: String) = "$POOL_PREFIX$instanceId"

    /** Snapshot of the persisted pool list for [instanceId] (ordered). */
    fun keys(instanceId: String): List<String> {
        val raw = encryptedPrefs.getString(prefsKey(instanceId), null) ?: return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(String.serializer()), raw) }
            .getOrDefault(emptyList())
    }

    /**
     * Full replacement write, including pruning stale runtime state.
     * [keys] may be empty (clears the pool).  Size < 2 is legal here;
     * policy callers ([ProviderConfigStore]) enforce the ≥2 invariant.
     */
    fun setKeys(instanceId: String, keys: List<String>) {
        if (keys.isEmpty()) {
            encryptedPrefs.edit().remove(prefsKey(instanceId)).apply()
            pruneFor(instanceId)
            return
        }
        val raw = json.encodeToString(ListSerializer(String.serializer()), keys)
        encryptedPrefs.edit().putString(prefsKey(instanceId), raw).apply()
        // The stored list changed — reset the cursor so rotation restarts
        // from position 0 with the fresh set.
        cursors.remove(instanceId)
        // Prune cooldown entries whose index no longer exists.
        val valid = keys.indices.toSet()
        cooldownUntil.keys.removeIf { key ->
            val (id, idx) = parseCooldownKey(key) ?: return@removeIf true
            id == instanceId && idx !in valid
        }
    }

    // ── rotation ────────────────────────────────────────────────────────

    /** Runtime rotation cursor: instanceId → next index to pick. */
    private val cursors = ConcurrentHashMap<String, AtomicInteger>()

    /** Runtime cooldown map: "instanceId#index" → cooled-until epoch ms. */
    private val cooldownUntil = ConcurrentHashMap<String, Long>()

    /** Last picked (instanceId, index) for error-path attribution. */
    private val lastPickInfo = ConcurrentHashMap<String, Int>()

    /**
     * Round-robin pick the next live key for [instanceId].
     * Returns the key and its zero-based index, or null when no key is
     * eligible (pool empty or every key cooled; an empty pool is NOT
     * cooled-back).
     *
     * The cursor is advanced to the NEXT position after return, not before
     * — so a single-request client advances once; a fallback chain of N
     * attempts also advances N times (one per crate).
     *
     * If every key is cooled down the cursor position is served anyway:
     * a degraded answer beats no answer.
     */
    fun pick(instanceId: String, nowMs: Long = System.currentTimeMillis()): Pair<String, Int>? {
        val current = keys(instanceId)
        if (current.isEmpty()) {
            lastPickInfo.remove(instanceId)
            return null
        }
        // Callers that only have ONE key still use this path — the
        // single-request-pick version: the pool could be size 1 transiently
        // (e.g. user removed the only extra) and still valid for rotation.
        val (key, idx) = poolPickKey(
            keys = current,
            cursor = cursors.getOrPut(instanceId) { AtomicInteger(0) },
            cooldownUntilMs = { cooldownUntil[cooldownKey(instanceId, it)] ?: 0L },
        ) ?: run {
            // All keys cooled — serve whichever the cursor points at.
            val fallbackIndex = (cursors[instanceId] ?: AtomicInteger(0)).get()
            val actualIdx = fallbackIndex.coerceIn(current.indices)
            current[actualIdx] to actualIdx
        }
        lastPickInfo[instanceId] = idx
        return key to idx
    }

    /** Return the index last picked for [instanceId], or null. */
    fun lastPick(instanceId: String): Int? = lastPickInfo[instanceId]

    /**
     * Park key [index] (0-based into the stored list) until [untilMs].
     * Any earlier cooldown for the same spot is extended.
     */
    fun markCooled(instanceId: String, index: Int, untilMs: Long) {
        val key = cooldownKey(instanceId, index)
        cooldownUntil[key] = maxOf(cooldownUntil[key] ?: 0L, untilMs)
    }

    // ── test support ────────────────────────────────────────────────────
    fun resetForTest() {
        cursors.clear()
        cooldownUntil.clear()
        lastPickInfo.clear()
    }

    // ── internals ───────────────────────────────────────────────────────
    private fun cooldownKey(instanceId: String, index: Int) = "$instanceId#$index"

    private fun parseCooldownKey(raw: String): Pair<String, Int>? {
        val split = raw.split("#", limit = 2)
        val idx = split.getOrNull(1)?.toIntOrNull() ?: return null
        return split[0] to idx
    }

    private fun pruneFor(instanceId: String) {
        cursors.remove(instanceId)
        lastPickInfo.remove(instanceId)
        cooldownUntil.keys.removeIf { parseCooldownKey(it)?.first == instanceId }
    }
}

// ── Store-mutation invariants (pure, test-pinned) ───────────────────────

/**
 * [T-credential-pool-x] Pool list after adding [candidate] to a pool.
 * When the pool is empty the FIRST slot is seeded from the legacy single
 * key [primary] (the only stored credential so far); adding a duplicate
 * changes nothing.
 */
internal fun poolAfterAdd(current: List<String>, primary: String?, candidate: String): List<String> {
    if (candidate in current) return current
    if (current.isNotEmpty()) return current + candidate
    val seed = primary?.takeIf { it.isNotBlank() && it != candidate }
    return if (seed != null) listOf(seed, candidate) else listOf(candidate)
}

/**
 * [T-credential-pool-x] Pool list after removing [index], plus the value
 * the primary (single-key) slot must be re-synced to, or null when it is
 * untouched.
 *
 * [PoolRemoval.keys] is empty when the pool collapses below the 2-key
 * minimum (the store then clears the pool slot).  The primary override is
 * non-null exactly when slot 0 disappeared and at least one key survived:
 * that survivor becomes the new single key.
 */
internal data class PoolRemoval(val keys: List<String>, val primaryOverride: String?)

internal fun poolAfterRemove(current: List<String>, index: Int): PoolRemoval {
    if (index !in current.indices) return PoolRemoval(current, null)
    val next = current.toMutableList().apply { removeAt(index) }
    val kept = if (next.size < 2) emptyList() else next
    val override = if (index == 0 && next.isNotEmpty()) next[0] else null
    return PoolRemoval(kept, override)
}

/**
 * [T-credential-pool-x] Pure round-robin walk: advance the [cursor] and
 * return the first key whose cooldown has expired.  Returns null when every
 * key is still cooled — the caller then falls back to the cursor position
 * unconditionally.
 *
 * [cooldownUntilMs] is a lookup function (for injecting mocked time).
 */
internal fun poolPickKey(
    keys: List<String>,
    cursor: AtomicInteger,
    cooldownUntilMs: (Int) -> Long,
    nowMs: Long = System.currentTimeMillis(),
): Pair<String, Int>? {
    if (keys.isEmpty()) return null
    val start = cursor.get() % keys.size
    // First pass: strictly skip cooled keys; on success the cursor lands on
    // the slot AFTER the served key so the next pick keeps rotating forward
    // even when cooled slots were skipped in between.
    for (i in keys.indices) {
        val idx = (start + i) % keys.size
        if (cooldownUntilMs(idx) <= nowMs) {
            cursor.set((idx + 1) % keys.size)
            return keys[idx] to idx
        }
    }
    return null
}

// ── Error-to-cooldown classification (pure, test-pinned) ────────────────

/**
 * [T-credential-pool-x] Map an [LLMError] to a credential cooldown
 * duration, or null when this error is not a signal to skip the key (e.g.
 * a transient hiccup).
 */
internal fun credentialCooldownFor(error: LLMError): Long? = when (error) {
    is LLMError.RateLimited -> {
        val raw = error.retryAfterSeconds?.takeIf { it > 0 }?.let { it * 1_000L }
        raw?.coerceAtMost(ProviderCredentialPool.RATE_LIMIT_MAX_COOLDOWN_MS)
            ?: ProviderCredentialPool.DEFAULT_COOLDOWN_MS
    }
    is LLMError.InvalidApiKey ->
        ProviderCredentialPool.INVALID_KEY_COOLDOWN_MS
    else -> null
}

// ── ProviderRepository surface (extension split to stay inside the file
//    ceiling — the class itself keeps the delegate-free core) ─────────────

/** The pooled keys for [instanceId] in order (empty when pool off). */
fun ProviderRepository.poolKeys(instanceId: String): List<String> =
    configStore.poolKeys(instanceId)

/** Add an extra account key; false when duplicate / below 2-key minimum. */
fun ProviderRepository.addPoolKey(instanceId: String, key: String): Boolean =
    configStore.addPoolKey(instanceId, key)

/** Remove pooled key [index]. */
fun ProviderRepository.removePoolKey(instanceId: String, index: Int): Boolean =
    configStore.removePoolKey(instanceId, index)

/**
 * [T-credential-pool-x] Request the credential the ROUTING layers should
 * use for [instance]: (key, index) from the rotation pool when active,
 * otherwise the legacy single key with index -1.  Null only when the
 * instance has no usable credential at all.
 */
fun ProviderRepository.requestCredential(instance: ProviderInstance): Pair<String, Int>? =
    configStore.requestCredential(instance)

/** Error-path cooldown for the key this instance last served. */
fun ProviderRepository.markCredentialCooled(instanceId: String, error: LLMError) =
    configStore.markCredentialCooled(instanceId, error)