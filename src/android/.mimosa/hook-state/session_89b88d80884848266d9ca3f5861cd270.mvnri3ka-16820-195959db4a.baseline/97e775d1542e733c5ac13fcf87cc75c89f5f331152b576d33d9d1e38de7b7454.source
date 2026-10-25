package com.openminis.app.provider

import java.util.concurrent.ConcurrentHashMap

/**
 * [T-llm-error-401-model-scope] Remembers that a credential was **accepted** by
 * a host, so a later 401 can be attributed to the right thing.
 *
 * ## Why this exists
 *
 * A gateway that terminates your auth and then calls an upstream pool answers
 * 401 for failures that are *its* upstream's, not yours. Measured on a public
 * relay with a single working credential:
 *
 * | model | 3 consecutive calls |
 * |---|---|
 * | `deepseek-v4.1-flash` | 200, 200, 200 |
 * | `deepseek-v4-flash-0731` | 401, 401, 401 |
 * | `deepseek-v4-pro-0813` | 401, 401, 401 |
 * | `glm-5.3` | 401, 401, 401 |
 * | `kimi-k3` | 401, 401, 401 |
 *
 * Deterministic per model, same key, same minute — four of five upstream
 * channels were broken. `GET /v1/models` with that key returned 200 throughout,
 * which is proof the credential is fine. Yet every one of those 401s was mapped
 * to a bare `LLMError.InvalidApiKey`, so the UI said **"Invalid API key"** and
 * the hint told the user to check or regenerate a key that was never the
 * problem. The actionable step was "pick a different model", and nothing in the
 * app could say so.
 *
 * ## Why not match on the error body
 *
 * The bodies did differ (`Invalid token (request id: …)` for the broken
 * channels vs `Unauthorized` for a genuinely wrong key), but that is one
 * vendor's wording. Every other gateway in this class phrases it differently,
 * and a fix keyed on phrasing only ever covers the ones someone happened to
 * test. "This credential was accepted by this host N seconds ago" is evidence
 * the app already has, from any vendor.
 *
 * ## Scope and freshness
 *
 * Keyed on host + credential fingerprint, deliberately **without** the model:
 * acceptance is a property of the credential, and the whole point is that the
 * model is what varied. Entries expire after [TTL_MILLIS] so a key revoked
 * mid-session still reports honestly as an invalid key rather than being
 * permanently excused by a catalog fetch from hours ago.
 */
object CredentialAcceptance {
    /** How long an observed acceptance stays credible. */
    const val TTL_MILLIS = 10 * 60 * 1000L

    private const val MAX_ENTRIES = 512

    private val acceptedAt = ConcurrentHashMap<String, Long>()

    /**
     * Record that [credentialKey] was accepted. Call on any authenticated 2xx
     * from the host — in practice the catalog (`/models`) fetch, which is the
     * one authenticated call the app makes before the user starts chatting.
     */
    fun note(credentialKey: String?, nowMillis: Long = System.currentTimeMillis()) {
        if (credentialKey.isNullOrBlank()) return
        if (acceptedAt.size >= MAX_ENTRIES) {
            // Drop what has aged out first; only if that is not enough, start
            // over. Entries are one string plus one long, and a wrong "not
            // accepted" answer merely restores the old message, so a blunt
            // bound is the right trade here.
            val expired = acceptedAt.entries.filter { nowMillis - it.value > TTL_MILLIS }
            if (expired.isNotEmpty()) {
                expired.forEach { acceptedAt.remove(it.key, it.value) }
            } else {
                acceptedAt.clear()
            }
        }
        acceptedAt[credentialKey] = nowMillis
    }

    /**
     * True when [credentialKey] was accepted within [TTL_MILLIS]. A blank key
     * (no credential configured) is never "accepted": an unauthenticated local
     * server that 401s has told us nothing about a credential.
     */
    fun isAccepted(credentialKey: String?, nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (credentialKey.isNullOrBlank()) return false
        val at = acceptedAt[credentialKey] ?: return false
        if (nowMillis - at > TTL_MILLIS) {
            acceptedAt.remove(credentialKey, at)
            return false
        }
        return true
    }

    /** Drops a credential's acceptance, e.g. when the key is deleted or rotated. */
    fun forget(credentialKey: String?) {
        if (credentialKey.isNullOrBlank()) return
        acceptedAt.remove(credentialKey)
    }

    /** Test isolation hook. */
    fun clear() {
        acceptedAt.clear()
    }
}
