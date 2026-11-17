package com.openminis.app.quota

import com.openminis.app.logging.AppLogger
import java.io.File

private const val TAG = "Quota"

/**
 * [T-quota-persist-mutex] The tmp write + rename must be one at a time:
 * the debounced persist job and the resigned-active IO path can both be
 * inside QuotaStore.flushPersist, and two writeText calls on the same
 * store.json.tmp interleave into a truncated file that the rename then
 * publishes as store.json. Serialized on the monitor this function compiles
 * to (never held with QuotaStore's state lock, so state mutations keep
 * running during the disk I/O). Every persist path funnels through here, so
 * the one monitor serializes them all. Moved verbatim from QuotaStore to
 * keep that file under the CI 800-line ratchet.
 */
@Synchronized
internal fun publishStore(file: QuotaStoreFile, target: File) {
    val tmp = File(target.parentFile, "store.json.tmp")
    tmp.writeText(QuotaStoreFile.encode(file))
    if (!tmp.renameTo(target)) {
        // [FIX-R2-15] Upstream's rescue deleted the target BEFORE the retry, so a
        // second rename failure lost the last good store with it. Move the target
        // aside to .broken, retry, delete the copy on success, restore it on
        // failure; failures log concrete paths and state, not just a class name.
        val broken = File(target.parentFile, target.name + ".broken")
        broken.delete()
        val movedAside = target.renameTo(broken)
        val restored = tmp.renameTo(target)
        if (movedAside && !restored && !broken.renameTo(target)) {
            AppLogger.error(TAG, "[Quota] persist: previous store stuck at ${broken.absolutePath}")
        }
        if (!restored) {
            AppLogger.error(TAG, "[Quota] persist failed: rename ${tmp.absolutePath} -> ${target.absolutePath} (tmpExists=${tmp.exists()} movedAside=$movedAside targetExists=${target.exists()}); in-memory cache kept")
        } else if (movedAside) {
            broken.delete()
        }
    }
}
