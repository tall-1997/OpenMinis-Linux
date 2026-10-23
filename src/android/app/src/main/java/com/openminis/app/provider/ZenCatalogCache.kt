package com.openminis.app.provider

import android.content.Context
import com.openminis.app.logging.AppLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One cached answer from the live catalogue.
 *
 * [isOfflineSeed] marks the hardcoded fallback used on a first install with no
 * network; [isStale] means the snapshot is real but older than [ZenCatalogCache.TTL_MS],
 * so the caller should refresh in the background while still showing it.
 */
internal data class ZenCatalogSnapshot(
    val ids: List<String>,
    val learned: ZenLearnedFree,
    val fetchedAtMs: Long,
    val isOfflineSeed: Boolean = false,
    val isStale: Boolean = false,
)

/**
 * Keeps the free lane populated when the network is not.
 *
 * Two rules do the work:
 * - **An empty payload is a failure.** [save] rejects it, so a truncated or
 *   blocked `/models` response can never overwrite a good cache with nothing.
 *   This is the first of two offline guards; the second lives at the call site,
 *   because `replaceEntries` prunes non-custom rows and must not be handed an
 *   empty catalogue.
 * - **The seed retires permanently.** [loadOrSeed] returns the two-id offline
 *   seed only while no real fetch has ever been stored. After that the live
 *   catalogue wins even when it is stale, because a stale 85-model list is
 *   closer to the truth than a frozen two-model one.
 *
 * A null context keeps everything in memory, which is what makes the contract
 * testable on the JVM.
 */
internal class ZenCatalogCache(private val context: Context?) {

    private val memory = mutableMapOf<String, ZenCatalogSnapshot>()

    /** Rejects an empty catalogue; returns whether the snapshot was stored. */
    fun save(key: String, snapshot: ZenCatalogSnapshot): Boolean {
        if (snapshot.ids.isEmpty()) {
            AppLogger.warning(TAG, "refusing to cache an empty catalogue for $key")
            return false
        }
        memory[key] = snapshot
        persist(key, snapshot)
        return true
    }

    fun load(key: String): ZenCatalogSnapshot? {
        memory[key]?.let { return it }
        val fromDisk = readDisk(key)
        if (fromDisk != null) memory[key] = fromDisk
        return fromDisk
    }

    /** [load], or the offline seed when this device has never fetched successfully. */
    fun loadOrSeed(key: String, nowMs: Long = System.currentTimeMillis()): ZenCatalogSnapshot {
        val cached = load(key)
        if (cached != null) {
            val stale = nowMs - cached.fetchedAtMs > TTL_MS
            return if (stale) cached.copy(isStale = true) else cached
        }
        AppLogger.info(TAG, "no cached catalogue for $key, falling back to the offline seed")
        return ZenCatalogSnapshot(
            ids = OFFLINE_SEED_IDS,
            learned = ZenLearnedFree(),
            fetchedAtMs = 0L,
            isOfflineSeed = true,
        )
    }

    // -- disk ----------------------------------------------------------------

    private fun file(): File? = context?.let { File(it.filesDir, FILE_NAME) }

    private fun readDisk(key: String): ZenCatalogSnapshot? {
        val target = file() ?: return null
        if (!target.isFile) return null
        return runCatching {
            val entry = JSONObject(target.readText()).optJSONObject(key) ?: return@runCatching null
            val ids = entry.optJSONArray(KEY_IDS)?.toStringList().orEmpty()
            if (ids.isEmpty()) return@runCatching null
            val learnedJson = entry.optJSONObject(KEY_LEARNED)
            ZenCatalogSnapshot(
                ids = ids,
                learned = ZenLearnedFree(
                    free = learnedJson?.optJSONArray(KEY_FREE)?.toStringSet().orEmpty(),
                    paid = learnedJson?.optJSONArray(KEY_PAID)?.toStringSet().orEmpty(),
                ),
                fetchedAtMs = entry.optLong(KEY_FETCHED_AT),
            )
        }.getOrElse {
            AppLogger.warning(TAG, "unreadable catalogue cache: ${it.message}")
            null
        }
    }

    private fun persist(key: String, snapshot: ZenCatalogSnapshot) {
        val target = file() ?: return
        runCatching {
            val root = if (target.isFile) JSONObject(target.readText()) else JSONObject()
            val learned = JSONObject()
                .put(KEY_FREE, JSONArray(snapshot.learned.free.toList()))
                .put(KEY_PAID, JSONArray(snapshot.learned.paid.toList()))
            root.put(
                key,
                JSONObject()
                    .put(KEY_IDS, JSONArray(snapshot.ids))
                    .put(KEY_LEARNED, learned)
                    .put(KEY_FETCHED_AT, snapshot.fetchedAtMs),
            )
            // Atomic: a crash mid-write must not leave a truncated catalogue
            // behind, because the next read would fall back to the seed.
            val tmp = File(target.parentFile, "${FILE_NAME}.tmp")
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
        private const val TAG = "ZenCatalogCache"
        private const val FILE_NAME = "zen_catalog.json"
        private const val KEY_IDS = "ids"
        private const val KEY_LEARNED = "learned"
        private const val KEY_FREE = "free"
        private const val KEY_PAID = "paid"
        private const val KEY_FETCHED_AT = "fetchedAtMs"

        /** A real catalogue is shown for this long before it counts as stale. */
        const val TTL_MS = 6L * 60 * 60 * 1000

        /**
         * The ONLY hardcoded ids in the whole feature, and they exist purely so
         * a first install with no network still offers something. Both satisfy
         * the naming rule in [ZenFreeLaneCatalog]; the seed is retired for good
         * by the first successful fetch.
         */
        val OFFLINE_SEED_IDS = listOf("deepseek-v4.1-flash-free", "big-pickle")
    }
}
