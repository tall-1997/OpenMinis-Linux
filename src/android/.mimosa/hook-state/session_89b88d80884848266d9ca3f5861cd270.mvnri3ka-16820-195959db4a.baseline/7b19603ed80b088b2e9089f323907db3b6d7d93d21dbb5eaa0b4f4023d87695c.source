package com.openminis.app.provider

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * One in-flight HTTP call per rate-limit **bucket**:
 * `(relay host, credential fingerprint, model id)`.
 *
 * Relays typically shard the token bucket by model name, but the same model
 * name on **different keys** is a different bucket (another account / another
 * channel pool). Same key + same model still queues so title/compact/sub-agent
 * cannot stampede one window.
 *
 * The permit is held only for a single HTTP stream/request. The agent tool
 * loop runs *after* the stream completes, so nested spawn_agent calls cannot
 * deadlock waiting on the parent.
 *
 * Nested [withPermit] on the same coroutine and key is a no-op so
 * OpenAIProvider.sendMessageClamped → streamMessage cannot self-deadlock.
 */
object ProviderKeyGate {
    const val DEFAULT_PERMITS = 1

    /** [T-provider-keygate-bounded] Cap on live rate-limit buckets. */
    private const val MAX_GATES = 256

    private val gates = ConcurrentHashMap<String, Semaphore>()

    private class HeldKeys(val keys: Set<String>) : AbstractCoroutineContextElement(HeldKeys) {
        companion object Key : CoroutineContext.Key<HeldKeys>
    }

    fun key(host: String, secret: String?, modelId: String? = null): String {
        val h = hostOf(host)
        val fp = fingerprint(secret)
        val m = normalizeModel(modelId)
        return "$h|$fp|$m"
    }

    /**
     * [T-llm-error-401-model-scope] Credential scope of [key]: host +
     * fingerprint, **without** the model. Acceptance of a credential is a
     * property of the credential, so this is the scope
     * [CredentialAcceptance] records and queries.
     */
    fun credentialKey(host: String, secret: String?): String =
        "${hostOf(host)}|${fingerprint(secret)}"

    /**
     * Derive [credentialKey] from an already-built [key] by dropping its final
     * segment. Exact because neither [hostOf] (a lowercased DNS name) nor
     * [fingerprint] (lowercase hex, or `anon`) can contain `|`, and
     * [normalizeModel] now maps `|` to `/` — so the model is the only segment
     * that could ever hold one, and it is last.
     */
    fun credentialScopeOf(gateKey: String): String = gateKey.substringBeforeLast('|')

    fun normalizeModel(modelId: String?): String =
        modelId?.trim()?.lowercase()?.replace("|", "/").orEmpty()

    fun fingerprint(secret: String?): String {
        val s = secret?.trim().orEmpty()
        if (s.isEmpty()) return "anon"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { b -> "%02x".format(b.toInt() and 0xff) }
    }

    fun sameBucket(a: String?, b: String?): Boolean =
        !a.isNullOrBlank() && a == b

    fun hostOf(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return ""
        return try {
            val uri = java.net.URI(if ("://" in t) t else "https://$t")
            (uri.host ?: t).trimEnd('.').lowercase()
        } catch (_: Exception) {
            t.trimEnd('/').lowercase()
        }
    }

    /**
     * Run [block] with this bucket's single permit.
     *
     * Reentrancy: a nested call on the SAME coroutine and key passes through
     * (tracked via [HeldKeys]). CONSTRAINT: do not `launch` a child coroutine
     * inside [block] that acquires the same key — children do not inherit
     * [HeldKeys], so the child would wait on a permit the suspended parent
     * never releases, and acquisition has deliberately no timeout (a stream
     * can legitimately hold the permit for minutes).
     */
    suspend fun <T> withPermit(key: String, block: suspend () -> T): T {
        if (key.isBlank()) return block()
        val held = coroutineContext[HeldKeys]?.keys
        if (held != null && key in held) return block()
        // [T-provider-keygate-bounded] Bucket keys contain the credential
        // fingerprint, and every OAuth silent refresh mints a new token → a
        // new fingerprint → a fresh batch of host×model buckets. Without a
        // bound, the map grew monotonically for the life of the process.
        //
        // Eviction removes IDLE buckets only (full permits, no queue): a
        // blanket clear() while permits are held would hand a concurrent
        // same-bucket caller a fresh semaphore and bypass the serialization
        // this gate exists for. If every bucket is busy the map temporarily
        // exceeds the cap instead — that overshoot is bounded by the number
        // of concurrently in-flight requests, and the next call reclaims the
        // idle entries.
        if (gates.size >= MAX_GATES) {
            val iter = gates.entries.iterator()
            while (iter.hasNext() && gates.size > MAX_GATES / 2) {
                val e = iter.next()
                // Idle = every permit available. The kotlinx Semaphore exposes
                // no waiter count, but with permits=1 a released permit is
                // handed straight to a queued waiter, so full availability
                // means no holder and no queue.
                if (e.key != key && e.value.availablePermits >= DEFAULT_PERMITS) iter.remove()
            }
        }
        val sem = gates.getOrPut(key) { Semaphore(DEFAULT_PERMITS) }
        return sem.withPermit {
            withContext(HeldKeys((held ?: emptySet()) + key)) {
                block()
            }
        }
    }
}
