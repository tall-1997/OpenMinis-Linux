package com.openminis.app.provider

import android.content.Context
import android.content.SharedPreferences
import com.openminis.app.logging.AppLogger
import org.json.JSONObject

/**
 * Why a free-lane request failed.
 *
 * Only three of these say something about the MODEL. The rest are about us,
 * our network, or our rate budget, and the retired build treated several of
 * them as death evidence — which is how a working free lane ends up looking
 * empty on a device that never did anything wrong.
 *
 * The vocabulary is shared by every lane in [FreeLanes]. A lane maps its own
 * error dialect onto these values; nothing here knows which upstream said it.
 */
internal enum class FreeLaneEvidence {
    /**
     * Upstream retired the model. Zen: HTTP 410. Kilo: HTTP 404 whose
     * `error.metadata.raw` reports the provider function as not found for the
     * account — the listing still advertises the id, so this is the only
     * signal that it is gone.
     */
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
     * [FreeLaneLearned.withPaid] then drops the id out of the free set — hiding
     * a model on evidence that is not permanent.
     */
    MODEL_ERROR_UNSPECIFIED,

    /**
     * A structured error whose type or code has no mapping in the lane that
     * received it. Upstream invents these: Zen's `FreeUsageLimitError` showed
     * up on a live request in 2026-10 riding a status the transport map would
     * have read as "needs a credential". A word nobody has interpreted is not a
     * statement about the model, so it must not be laundered into a catalogue
     * verdict by whatever status it happened to travel with.
     */
    UNSPECIFIED_ERROR_TYPE,

    /** Region refusal. Reachable through the user's own proxy, so not evidence. */
    REGION,

    /** The endpoint does not speak this request dialect. Our bug, not the model's. */
    PROTOCOL_UNSUPPORTED,

    /**
     * The lane refused the request shape, or the id is not on its free pool.
     * Zen: `FreeTierError` after the gate was satisfied. Kilo:
     * `PAID_MODEL_AUTH_REQUIRED`, which is also the shape a mistyped id
     * returns — so it can only ever mean "hide this from the free lane",
     * never "this model is gone".
     */
    FREE_TIER,

    /**
     * A non-empty credential reached a lane that must be called with none.
     * Kilo answers `INVALID_TOKEN` to any token it cannot validate, including
     * the `public` placeholder another lane needs. That is our wiring bug, and
     * recording it as a dead model would turn our mistake into a model that
     * silently disappears for a week.
     */
    CREDENTIAL_REJECTED,

    /** HTTP 429. */
    RATE_LIMITED,

    /** HTTP 5xx. */
    SERVER,

    /** DNS, TLS, timeout, cancellation. */
    NETWORK,
    ;

    /**
     * True only when the upstream blamed the model itself. The `when` is
     * exhaustive on purpose: adding a value forces whoever adds it to decide
     * whether it may hide a model, which is the decision that went missing
     * twice already.
     *
     * [P2-convergence] This is the free-lane permanence vocabulary; the
     * relay/429 body-marker one lives in [HttpRetryAfter.classifyCapacityBody]
     * and the catalog-fetch one in [ModelListFetchRetry.classify]. Deliberately
     * not folded together: a lane verdict is about the MODEL (per-lane,
     * evidence-scoped, TTL-healed), while the capacity classifier is about the
     * ACCOUNT/plan (body markers, no per-model scope). RATE_LIMITED stays
     * non-death even when a 429 body carries a strong capacity marker — a
     * relay quota is not proof the model is gone.
     */
    val marksDead: Boolean
        get() = when (this) {
            GONE, MODEL_UNAVAILABLE, MODEL_NOT_SUPPORTED -> true
            MODEL_ERROR_UNSPECIFIED, UNSPECIFIED_ERROR_TYPE, REGION,
            PROTOCOL_UNSUPPORTED, FREE_TIER, CREDENTIAL_REJECTED,
            RATE_LIMITED, SERVER, NETWORK -> false
        }
}

/**
 * Hides a free-lane model only when upstream said the model is gone.
 *
 * Evidence is scoped per lane. The store used to be a flat `id → timestamp`
 * map, which was fine while one lane existed and is a bug the moment a second
 * one does: the same id advertised by two upstreams would be hidden on both
 * because one of them retired it, and nothing on disk said who recorded what.
 * The key is now `"<lane>:<id>"`, so the identity being recorded is as wide as
 * the fact being recorded.
 *
 * Legacy flat entries migrate to [FreeLanes.ZEN_ID] on read, because Zen is the
 * only lane that ever wrote them. The rule is "the text before the first colon
 * is a lane id we know" rather than "the key contains a colon", because model
 * ids legitimately contain colons (`nvidia/nemotron-3.5-lightning:free`). An
 * unrecognised prefix is therefore treated as a legacy Zen id — the fail-safe
 * direction, since it can never hide a model on a lane that did not report it.
 *
 * Persistence otherwise keeps the old shape: a 7-day TTL that self-heals, and
 * [recordSuccess] as the other half of the evidence — a probe that gets a clean
 * answer cancels prior death instead of waiting out the TTL. [recordSuccess] is
 * wired to probe verdicts and nothing else: live traffic cannot tell a real
 * answer from a 200-shaped refusal without consuming the stream, and clearing
 * evidence on a refusal would be worse than the wait.
 *
 * Without [initialize] this runs purely in memory, which keeps it testable on
 * the JVM and means a process that never wired a context still behaves sanely.
 */
internal object FreeLaneHealth {
    private const val TAG = "FreeLaneHealth"
    private const val PREFS_NAME = "free_lane_health"

    /** Pre-lane-scoping store. Read once for migration, never written again. */
    private const val LEGACY_PREFS_NAME = "zen_free_lane_health"
    private const val KEY_DEAD = "dead_ids"

    /** A retired model stays hidden for a week, then gets one more chance. */
    const val DEAD_TTL_MS = 7L * 24 * 60 * 60 * 1000

    /** Test seam: the clock every TTL decision reads. */
    internal var nowProvider: () -> Long = { System.currentTimeMillis() }

    private var prefs: SharedPreferences? = null
    private val memory = mutableMapOf<String, Long>()

    private fun key(lane: String, id: String) = "$lane:$id"

    fun initialize(context: Context?) {
        if (context == null) return
        val app = context.applicationContext
        prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val onDisk = readDisk(prefs)
        if (onDisk.isNotEmpty()) {
            onDisk.forEach { (k, at) -> memory.putIfAbsent(k, at) }
            return
        }
        // First run after the rename: adopt the flat Zen-only store, scoping
        // every entry to the lane that wrote it.
        val legacy = app.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        val migrated = readDisk(legacy).mapKeys { (k, _) -> scopeLegacyKey(k) }
        if (migrated.isNotEmpty()) {
            migrated.forEach { (k, at) -> memory.putIfAbsent(k, at) }
            writeDisk()
            AppLogger.info(TAG, "migrated ${migrated.size} legacy death entries into lane scope")
        }
    }

    /** See the class doc: an unknown prefix is a legacy Zen id, not a lane. */
    private fun scopeLegacyKey(stored: String): String {
        val prefix = stored.substringBefore(':', missingDelimiterValue = "")
        return if (prefix in FreeLanes.LANE_IDS) stored else key(FreeLanes.ZEN_ID, stored)
    }

    fun isDead(lane: String, id: String): Boolean {
        val k = key(lane, id)
        val recordedAt = memory[k] ?: return false
        if (nowProvider() - recordedAt >= DEAD_TTL_MS) {
            forget(k)
            AppLogger.info(TAG, "$lane/$id self-healed after the ${DEAD_TTL_MS / 86_400_000}d ttl")
            return false
        }
        return true
    }

    fun recordFailure(lane: String, id: String, evidence: FreeLaneEvidence) {
        if (!evidence.marksDead) {
            // Logged, never acted on: this is about us or the network.
            AppLogger.info(TAG, "$lane/$id failed with $evidence — not death evidence, catalogue unchanged")
            return
        }
        memory[key(lane, id)] = nowProvider()
        writeDisk()
        AppLogger.warning(TAG, "$lane/$id hidden on $evidence evidence")
    }

    /**
     * Positive evidence: the id answered on this lane. Cancels a death verdict
     * at once, so a model upstream switched back on does not stay hidden for
     * the rest of its TTL. No-op for an id that was never marked.
     */
    fun recordSuccess(lane: String, id: String) {
        if (memory.remove(key(lane, id)) != null) {
            writeDisk()
            AppLogger.info(TAG, "$lane/$id answered, clearing prior death evidence")
        }
    }

    private fun forget(k: String) {
        if (memory.remove(k) != null) writeDisk()
    }

    private fun readDisk(source: SharedPreferences?): Map<String, Long> {
        val raw = source?.getString(KEY_DEAD, null) ?: return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            buildMap { json.keys().forEach { k -> put(k, json.optLong(k)) } }
        }.getOrDefault(emptyMap())
    }

    private fun writeDisk() {
        val editor = prefs?.edit() ?: return
        val json = JSONObject()
        memory.forEach { (k, at) -> json.put(k, at) }
        editor.putString(KEY_DEAD, json.toString()).apply()
    }

    /** Test seams. */
    internal fun resetForTest() {
        memory.clear()
        prefs?.edit()?.remove(KEY_DEAD)?.apply()
    }

    internal fun deadIdsForTest(lane: String? = null): Set<String> =
        // Copy first: isDead() self-heals expired entries, which mutates the map.
        memory.keys.toList()
            .filter { lane == null || it.startsWith("$lane:") }
            .filter { isDead(it.substringBefore(':'), it.substringAfter(':')) }
            .toSet()
}
