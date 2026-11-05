package com.openminis.app.backup

import com.openminis.app.logging.AppLogger
import org.json.JSONObject
import java.io.File

/**
 * [Wave3 #10b] Sidecar manifest enabling the incremental export fast path.
 *
 * The `.minisbak` package format itself is unchanged (a full snapshot — iOS
 * compatibility, §5.4, forbids changing member shapes). The increment lives
 * ABOVE the format: this sidecar remembers, per logical path, the blob SHA-256
 * the last export stored it under plus the source file's size and mtime at
 * that moment. The next export consults it BEFORE reading + hashing a source
 * file: when size and mtime still match (rsync's cheap-change heuristic), the
 * file's content must hash to the same digest, so its blob is pulled straight
 * out of the previous package's zip instead of re-reading the possibly
 * multi-GB source tree. Only files whose size or mtime moved take the full
 * read+hash+copy path.
 *
 * Fidelity note: a same-size, same-mtime in-place modification would be
 * missed — the same trade `rsync --archive` makes, acceptable for a backup
 * whose alternative was re-reading every byte of a rootfs each run.
 *
 * Location: a sibling of the finished packages in [BackupExporter
 * .backupsDirectory]. `last-manifest.json` is never walked as user data
 * (isBackupArtifact already excludes the whole Backups tree) and never ships
 * inside a package.
 *
 * Written only after a package is successfully finalised, so a failed run
 * leaves the previous manifest intact and still valid for reuse.
 */
// [Wave3 #10b] Public (not internal) only because the public
// BackupBlobStore constructor references this type.
class BackupIncrementalManifest(private val file: File) {

    class Entry(val sha256: String, val size: Long, val mtime: Long)

    /** Package file name the entries were recorded against; null when absent/unreadable. */
    var packageName: String? = null
        private set

    private val previous = HashMap<String, Entry>()
    private val recorded = LinkedHashMap<String, Entry>()

    /** Fast-path statistics for the completion log. */
    var reusedFiles = 0
        private set
    var reusedBytes = 0L
        private set

    fun load() {
        previous.clear()
        packageName = null
        if (!file.isFile) return
        runCatching {
            val root = JSONObject(file.readText())
            packageName = root.optString("package").takeIf { it.isNotEmpty() }
            val files = root.optJSONObject("files") ?: return
            val keys = files.keys()
            while (keys.hasNext()) {
                val path = keys.next()
                val o = files.optJSONObject(path) ?: continue
                val sha = o.optString("sha256").takeIf { it.isNotEmpty() } ?: continue
                previous[path] = Entry(sha, o.optLong("size", -1L), o.optLong("mtime", -1L))
            }
        }.onFailure {
            AppLogger.warning(TAG, "last-manifest.json unreadable — exporting full: ${it.message}")
            previous.clear()
            packageName = null
        }
    }

    /** @return the cached entry when [path] looks unchanged since the last run. */
    fun lookupUnchanged(path: String, size: Long, mtime: Long): Entry? {
        if (mtime <= 0L || size < 0L) return null
        val e = previous[path] ?: return null
        return if (e.size == size && e.mtime == mtime) e else null
    }

    /** Record a blob stored THIS run; [recorded] replaces the whole map on save. */
    fun record(path: String, sha256: String, size: Long, mtime: Long) {
        recorded[path] = Entry(sha256, size, if (mtime > 0) mtime else 0L)
    }

    fun countReused(size: Long) {
        reusedFiles += 1
        reusedBytes += size
    }

    fun save(newPackageName: String) {
        runCatching {
            val files = JSONObject()
            for ((path, e) in recorded) {
                files.put(
                    path,
                    JSONObject()
                        .put("sha256", e.sha256)
                        .put("size", e.size)
                        .put("mtime", e.mtime),
                )
            }
            val root = JSONObject().put("package", newPackageName).put("files", files)
            file.parentFile?.mkdirs()
            file.writeText(root.toString())
            AppLogger.info(
                TAG,
                "[Backup] incremental manifest saved: ${recorded.size} blob(s), " +
                    "reused $reusedFiles file(s) / $reusedBytes bytes from the previous package",
            )
        }.onFailure {
            AppLogger.warning(TAG, "could not write last-manifest.json: ${it.message}")
        }
    }

    companion object {
        private const val TAG = "Backup"
        const val FILE_NAME = "last-manifest.json"
    }
}
