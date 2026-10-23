package com.openminis.app.provider

import android.content.Context
import android.content.SharedPreferences
import com.openminis.app.logging.AppLogger
import org.json.JSONObject

/**
 * Why a Zen request failed.
 *
 * Only three of these say something about the MODEL. The other six are about
 * us, our network, or our rate budget, and the retired build treated several of
 * them as death evidence — which is how a working free lane ends up looking
 * empty on a device that never did anything wrong.
 */
internal enum class ZenFailureEvidence {
    /** HTTP 410: upstream retired the model. */
    GONE,

    /** HTTP 400 `Model is unavailable.` — the model is switched off upstream. */
    MODEL_UNAVAILABLE,

    /** HTTP 401 ModelError naming the model as unsupported. */
    MODEL_NOT_SUPPORTED,

    /**
     * HTTP 401 ModelError that does *not* name the model as unsupported. This
     * says nothing about the model: the same status is what our own gate or
     * routing regression looks like. It must not reach the code-based
     * fallthrough that reads 401 as "needs a credential", because
     * [ZenLearnedFree.withPaid] then drops the id out of the free set — hiding
     * a model on evidence that is not permanent.
     */
    MODEL_ERROR_UNSPECIFIED,

    /** Region refusal. Reachable through the user's own proxy, so not evidence. */
    REGION,

    /** The endpoint does not speak this request dialect. Our bug, not the model's. */
    PROTOCOL_UNSUPPORTED,

    /** FreeTierError: our request shape missed the gate. Our bug, not the model's. */
    FREE_TIER,

    /** HTTP 429. */
    RATE_LIMITED,

    /** HTTP 5xx. */
    SERVER,

    /** DNS, TLS, timeout, cancellation. */
    NETWORK,
    ;

    /** True only when the upstream blamed the model itself. */
    val marksDead: Boolean
        get() = when (this) {
            GONE, MODEL_UNAVAILABLE, MODEL_NOT_SUPPORTED -> true
            MODEL_ERROR_UNSPECIFIED, REGION, PROTOCOL_UNSUPPORTED, FREE_TIER,
            RATE_LIMITED, SERVER, NETWORK -> false
        }
}

/**
 * Hides a free-lane model only when upstream said the model is gone.
 *
 * Persistence keeps the old shape — SharedPreferences `zen_free_lane_health`,
 * key `dead_ids`, a 7-day TTL that self-heals — because that part was sound.
 * What changed is the evidence set: [ZenFailureEvidence.marksDead] is the single
 * place that decides, and [recordSuccess] clears an id immediately so a model
 * that comes back does not stay hidden for a week.
 *
 * Without [initialize] this runs purely in memory, which keeps it testable on
 * the JVM and means a process that never wired a context still behaves sanely.
 */
internal object ZenFreeLaneHealth {
    private const val TAG = "ZenFreeLaneHealth"
    private const val PREFS_NAME = "zen_free_lane_health"
    private const val KEY_DEAD = "dead_ids"

    /** A retired model stays hidden for a week, then gets one more chance. */
    const val DEAD_TTL_MS = 7L * 24 * 60 * 60 * 1000

    /** Test seam: the clock every TTL decision reads. */
    internal var nowProvider: () -> Long = { System.currentTimeMillis() }

    private var prefs: SharedPreferences? = null
    private val memory = mutableMapOf<String, Long>()

    fun initialize(context: Context?) {
        if (context == null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        readDisk().forEach { (id, at) -> memory.putIfAbsent(id, at) }
    }

    fun isDead(id: String): Boolean {
        val recordedAt = memory[id] ?: return false
        if (nowProvider() - recordedAt >= DEAD_TTL_MS) {
            forget(id)
            AppLogger.info(TAG, "$id self-healed after the ${DEAD_TTL_MS / 86_400_000}d ttl")
            return false
        }
        return true
    }

    fun recordFailure(id: String, evidence: ZenFailureEvidence) {
        if (!evidence.marksDead) {
            // Logged, never acted on: this is about us or the network.
            AppLogger.info(TAG, "$id failed with $evidence — not death evidence, catalogue unchanged")
            return
        }
        memory[id] = nowProvider()
        writeDisk()
        AppLogger.warning(TAG, "$id hidden on $evidence evidence")
    }

    fun recordSuccess(id: String) {
        if (memory.remove(id) != null) {
            writeDisk()
            AppLogger.info(TAG, "$id answered, clearing prior death evidence")
        }
    }

    private fun forget(id: String) {
        if (memory.remove(id) != null) writeDisk()
    }

    private fun readDisk(): Map<String, Long> {
        val raw = prefs?.getString(KEY_DEAD, null) ?: return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            buildMap { json.keys().forEach { key -> put(key, json.optLong(key)) } }
        }.getOrDefault(emptyMap())
    }

    private fun writeDisk() {
        val editor = prefs?.edit() ?: return
        val json = JSONObject()
        memory.forEach { (id, at) -> json.put(id, at) }
        editor.putString(KEY_DEAD, json.toString()).apply()
    }

    /** Test seams. */
    internal fun resetForTest() {
        memory.clear()
        prefs?.edit()?.remove(KEY_DEAD)?.apply()
    }

    internal fun deadIdsForTest(): Set<String> =
        // Copy first: isDead() self-heals expired entries, which mutates the map.
        memory.keys.toList().filter { isDead(it) }.toSet()
}
