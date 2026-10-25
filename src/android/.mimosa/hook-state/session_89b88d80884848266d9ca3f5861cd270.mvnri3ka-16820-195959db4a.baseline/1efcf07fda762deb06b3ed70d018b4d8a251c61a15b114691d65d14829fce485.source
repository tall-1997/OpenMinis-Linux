package com.openminis.app.provider

import android.content.Context
import com.openminis.app.logging.AppLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One cached answer from a lane's live catalogue.
 *
 * [isOfflineSeed] marks the hardcoded fallback used on a first install with no
 * network; [isStale] means the snapshot is real but older than
 * [FreeLaneCatalogCache.TTL_MS], so the caller should refresh in the background
 * while still showing it.
 *
 * [probedAtMs] is the backoff ledger [FreeLaneDirectory] reads: an id that was
 * probed and produced no verdict is not worth another request on every refresh,
 * especially on a lane sitting inside a multi-hour quota window.
 */
internal data class FreeLaneCatalogSnapshot(
    val ids: List<String>,
    val learned: FreeLaneLearned,
    val fetchedAtMs: Long,
    val probedAtMs: Map<String, Long> = emptyMap(),
    val isOfflineSeed: Boolean = false,
    val isStale: Boolean = false,
)

/**
 * Keeps every free lane populated when the network is not.
 *
 * The cache key is the endpoint, so lanes never read each other's snapshot;
 * the offline seed comes from the lane, because "the two ids a first install
 * can fall back to" is a property of an upstream and not of the cache.
 *
 * Two rules do the work:
 * - **An empty payload is a failure.** [save] rejects it, so a truncated or
 *   blocked `/models` response can never overwrite a good cache with nothing.
 *   This is the first of two offline guards; the second lives at the call site,
 *   because `replaceEntries` prunes non-custom rows and must not be handed an
 *   empty catalogue.
 * - **The seed retires permanently.** [loadOrSeed] returns the lane's offline
 *   seed only while no real fetch has ever been stored. After that the live
 *   catalogue wins even when it is stale, because a stale full list is closer to
 *   the truth than a frozen two-model one.
 *
 * A null context keeps everything in memory, which is what makes the contract
 * testable on the JVM.
 */
internal class FreeLaneCatalogCache(private val context: Context?) {

    private val memory = mutableMapOf<String, FreeLaneCatalogSnapshot>()

    /** Rejects an empty catalogue; returns whether the snapshot was stored. */
    fun save(key: String, snapshot: FreeLaneCatalogSnapshot): Boolean {
        if (snapshot.ids.isEmpty()) {
            AppLogger.warning(TAG, "refusing to cache an empty catalogue for $key")
            return false
        }
        memory[key] = snapshot
        persist(key, snapshot)
        return true
    }

    fun load(key: String): FreeLaneCatalogSnapshot? {
        memory[key]?.let { return it }
        val fromDisk = readDisk(key)
        if (fromDisk != null) memory[key] = fromDisk
        return fromDisk
    }

    /** [load], or the lane's offline seed when this device has never fetched successfully. */
    fun loadOrSeed(
        lane: FreeLane,
        key: String,
        nowMs: Long = System.currentTimeMillis(),
    ): FreeLaneCatalogSnapshot {
        val cached = load(key)
        if (cached != null) {
            val stale = nowMs - cached.fetchedAtMs > TTL_MS
            return if (stale) cached.copy(isStale = true) else cached
        }
        AppLogger.info(TAG, "no cached catalogue for $key, falling back to the ${lane.id} offline seed")
        return FreeLaneCatalogSnapshot(
            ids = lane.offlineSeedIds,
            learned = FreeLaneLearned(),
            fetchedAtMs = 0L,
            isOfflineSeed = true,
        )
    }

    // -- disk ----------------------------------------------------------------

    private fun file(): File? = context?.let { File(it.filesDir, FILE_NAME) }

    /**
     * Adopt the pre-lane file once. It holds Zen snapshots under Zen endpoint
     * keys, which stay valid because the key was always the endpoint and not the
     * lane; renaming without migrating would strand a working offline fallback
     * on every device that already has one.
     */
    private fun migrateLegacyFile(target: File) {
        if (target.isFile) return
        val legacy = File(target.parentFile, LEGACY_FILE_NAME)
        if (!legacy.isFile) return
        val moved = legacy.renameTo(target)
        AppLogger.info(TAG, if (moved) "adopted $LEGACY_FILE_NAME as $FILE_NAME" else "could not adopt $LEGACY_FILE_NAME")
    }

    private fun readDisk(key: String): FreeLaneCatalogSnapshot? {
        val target = file() ?: return null
        migrateLegacyFile(target)
        if (!target.isFile) return null
        return runCatching {
            val entry = JSONObject(target.readText()).optJSONObject(key) ?: return@runCatching null
            val ids = entry.optJSONArray(KEY_IDS)?.toStringList().orEmpty()
            if (ids.isEmpty()) return@runCatching null
            val learnedJson = entry.optJSONObject(KEY_LEARNED)
            FreeLaneCatalogSnapshot(
                ids = ids,
                learned = FreeLaneLearned(
                    free = learnedJson?.optJSONArray(KEY_FREE)?.toStringSet().orEmpty(),
                    paid = learnedJson?.optJSONArray(KEY_PAID)?.toStringSet().orEmpty(),
                ),
                fetchedAtMs = entry.optLong(KEY_FETCHED_AT),
                probedAtMs = entry.optJSONObject(KEY_PROBED_AT)?.let { json ->
                    buildMap { json.keys().forEach { k -> put(k, json.optLong(k)) } }
                }.orEmpty(),
            )
        }.getOrElse {
            AppLogger.warning(TAG, "unreadable catalogue cache: ${it.message}")
            null
        }
    }

    private fun persist(key: String, snapshot: FreeLaneCatalogSnapshot) {
        val target = file() ?: return
        runCatching {
            migrateLegacyFile(target)
            val root = if (target.isFile) JSONObject(target.readText()) else JSONObject()
            val learned = JSONObject()
                .put(KEY_FREE, JSONArray(snapshot.learned.free.toList()))
                .put(KEY_PAID, JSONArray(snapshot.learned.paid.toList()))
            val probed = JSONObject()
            snapshot.probedAtMs.forEach { (id, at) -> probed.put(id, at) }
            root.put(
                key,
                JSONObject()
                    .put(KEY_IDS, JSONArray(snapshot.ids))
                    .put(KEY_LEARNED, learned)
                    .put(KEY_FETCHED_AT, snapshot.fetchedAtMs)
                    .put(KEY_PROBED_AT, probed),
            )
            // Atomic: a crash mid-write must not leave a truncated catalogue
            // behind, because the next read would fall back to the seed.
            val tmp = File(target.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(target)) {
                target.writeText(root.toString())
                tmp.delete()
            }
        }.onFailure { AppLogger.warning(TAG, "could not persist catalogue cache: ${it.message}") }
    }

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).mapNotNull { optString(it).takeIf(String::isNotEmpty) }

    private fun JSONArray.toStringSet(): Set<String> = toStringList().toSet()

    internal companion object {
        private const val TAG = "FreeLaneCatalogCache"
        private const val FILE_NAME = "free_lane_catalog.json"
        private const val LEGACY_FILE_NAME = "zen_catalog.json"
        private const val KEY_IDS = "ids"
        private const val KEY_LEARNED = "learned"
        private const val KEY_FREE = "free"
        private const val KEY_PAID = "paid"
        private const val KEY_FETCHED_AT = "fetchedAtMs"
        private const val KEY_PROBED_AT = "probedAtMs"

        /** A real catalogue is shown for this long before it counts as stale. */
        const val TTL_MS = 6L * 60 * 60 * 1000
    }
}
