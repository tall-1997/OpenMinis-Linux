package com.openminis.app.backup

import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException

/**
 * [T-backup-undo-store] File-level undo points for a restore.
 *
 * `BackupImporter` overwrites chats, providers, skills, memory, shared files
 * and MCP servers with no way back. Re-importing an older package is NOT an
 * undo: the importer upserts by id and skips existing credentials, so records
 * the restore ADDED survive a re-import and the state never returns to what it
 * was. The only mechanism that truly reverses a restore is putting the bytes
 * back, which is what this store does.
 *
 * Deliberately free of Android types: the root is injected, so the whole
 * lifecycle (capture -> list -> restore -> prune) is exercised in plain JVM
 * tests against a temp directory.
 *
 * Snapshot semantics, which the restore path depends on:
 *  - a root that existed at capture time lives under `tree/<root>`
 *  - a root that was absent at capture time is recorded in [UndoPoint.paths]
 *    but has no `tree/<root>` entry, so restoring DELETES it (that is the only
 *    way records added by the restore disappear)
 *  - a root skipped because the snapshot budget ran out lands in
 *    [UndoPoint.skipped] and is never touched by restore — half a root is
 *    worse than none, because restoring it would destroy data the snapshot
 *    never held
 */
class BackupUndoStore(
    private val filesDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    @Serializable
    data class Manifest(
        val id: String,
        val createdAtMs: Long,
        val categories: List<String> = emptyList(),
        val paths: List<String> = emptyList(),
        val skipped: List<String> = emptyList(),
        val bytes: Long = 0L,
        val partial: Boolean = false,
    )

    data class UndoPoint(
        val id: String,
        val createdAtMs: Long,
        val categories: Set<BackupCategory>,
        val paths: List<String>,
        val skipped: List<String>,
        val bytes: Long,
        val partial: Boolean,
    )

    data class CaptureResult(val point: UndoPoint?, val error: String? = null)
    data class RestoreResult(val ok: Boolean, val bytes: Long = 0L, val error: String? = null)

    fun root(): File = File(filesDir, DIR_NAME)

    fun dirFor(id: String): File = File(root(), id)

    // -- capture ---------------------------------------------------------

    fun capture(
        categories: Set<BackupCategory>,
        maxBytes: Long = DEFAULT_MAX_BYTES,
    ): CaptureResult {
        val roots = BackupUndoPaths.pathsFor(categories)
        if (roots.isEmpty()) {
            return CaptureResult(null, "Nothing to snapshot for ${categories.map { it.key }}")
        }
        val now = clock()
        val id = uniqueId(now)
        val pointDir = dirFor(id)
        val tree = File(pointDir, TREE)
        return try {
            if (!tree.mkdirs() && !tree.isDirectory) {
                return CaptureResult(null, "Could not create $tree")
            }
            var bytes = 0L
            val captured = mutableListOf<String>()
            val skipped = mutableListOf<String>()
            for (rel in roots) {
                val src = File(filesDir, rel)
                if (!src.exists()) {
                    // Absent today: recorded so a restore deletes whatever a
                    // package puts there later.
                    captured += rel
                    continue
                }
                val size = sizeOf(src)
                if (bytes + size > maxBytes) {
                    // Whole-root skip. Copying part of it would make restore
                    // delete the rest, which is data loss, not an undo.
                    skipped += rel
                    continue
                }
                val dst = File(tree, rel)
                bytes += copyTree(src, dst)
                captured += rel
            }
            val manifest = Manifest(
                id = id,
                createdAtMs = now,
                categories = categories.map { it.key }.sorted(),
                paths = captured,
                skipped = skipped,
                bytes = bytes,
                partial = skipped.isNotEmpty(),
            )
            File(pointDir, MANIFEST).writer().use { it.write(BackupFormat.json.encodeToString(Manifest.serializer(), manifest)) }
            CaptureResult(manifest.toPoint())
        } catch (e: Exception) {
            // A failed snapshot must never block the restore it was protecting.
            runCatching { pointDir.deleteRecursively() }
            CaptureResult(null, e.message ?: "Snapshot failed")
        }
    }

    // -- list / delete / prune -------------------------------------------

    fun list(): List<UndoPoint> {
        val dir = root()
        if (!dir.isDirectory) return emptyList()
        val out = mutableListOf<UndoPoint>()
        for (child in dir.listFiles().orEmpty()) {
            if (!child.isDirectory) continue
            val manifest = readManifest(child)
            if (manifest == null) {
                // Half a snapshot cannot be restored from; keeping it would
                // only offer the user a button that fails.
                runCatching { child.deleteRecursively() }
                continue
            }
            out += manifest.toPoint()
        }
        return out.sortedByDescending { it.createdAtMs }
    }

    fun newest(): UndoPoint? = list().firstOrNull()

    fun delete(id: String): Boolean = runCatching { dirFor(id).deleteRecursively() }.getOrDefault(false)

    /** Keeps the newest [keep] points and drops anything older than [maxAgeMs]. */
    fun prune(keep: Int = DEFAULT_KEEP, maxAgeMs: Long = DEFAULT_MAX_AGE_MS): Int {
        val all = list()
        val cutoff = clock() - maxAgeMs
        var removed = 0
        all.forEachIndexed { index, point ->
            val tooMany = index >= keep
            val tooOld = point.createdAtMs < cutoff
            if (tooMany || tooOld) {
                if (delete(point.id)) removed += 1
            }
        }
        return removed
    }

    // -- restore ---------------------------------------------------------

    /**
     * Puts [point] back. The caller MUST have closed Room first — this replaces
     * database files and a live connection would keep writing into the
     * replaced inode.
     */
    fun restore(point: UndoPoint): RestoreResult {
        val pointDir = dirFor(point.id)
        if (!pointDir.isDirectory) {
            return RestoreResult(false, error = "Undo point ${point.id} is no longer available")
        }
        val tree = File(pointDir, TREE)
        // Hoisted out of the try: a partial restore reports how much it wrote
        // before failing, and that count lives across the failure boundary.
        var bytes = 0L
        return try {
            for (rel in point.paths) {
                val target = File(filesDir, rel)
                val snapshot = File(tree, rel)
                // Delete first, unconditionally: records added by the restore
                // live under roots that held nothing at capture time, and only
                // deleting them makes the state actually go back.
                target.deleteRecursively()
                if (snapshot.exists()) bytes += copyTree(snapshot, target)
            }
            RestoreResult(true, bytes)
        } catch (e: Exception) {
            RestoreResult(false, bytes, e.message ?: "Restore failed")
        }
    }

    // -- internals -------------------------------------------------------

    private fun uniqueId(now: Long): String {
        val base = "undo-$now"
        if (!dirFor(base).exists()) return base
        var n = 2
        while (dirFor("$base-$n").exists()) n += 1
        return "$base-$n"
    }

    private fun readManifest(dir: File): Manifest? = runCatching {
        val file = File(dir, MANIFEST)
        if (!file.isFile) return null
        BackupFormat.json.decodeFromString(Manifest.serializer(), file.readText())
    }.getOrNull()

    private fun Manifest.toPoint(): UndoPoint = UndoPoint(
        id = id,
        createdAtMs = createdAtMs,
        categories = categories.mapNotNull(BackupCategory::fromKey).toSet(),
        paths = paths,
        skipped = skipped,
        bytes = bytes,
        partial = partial,
    )

    /** Total bytes of regular files under [src]; 0 for a non-existent path. */
    private fun sizeOf(src: File): Long {
        if (src.isFile) return src.length()
        var total = 0L
        for (child in src.listFiles().orEmpty()) total += sizeOf(child)
        return total
    }

    /**
     * Recursive copy that returns the bytes written. Only directories and
     * regular files are followed — symlinks and sockets are skipped rather
     * than followed, so a link into the rootfs cannot balloon a snapshot.
     */
    private fun copyTree(src: File, dst: File): Long {
        if (src.isFile) {
            dst.parentFile?.mkdirs()
            return try {
                src.inputStream().use { inp ->
                    dst.outputStream().use { out -> inp.copyTo(out) }
                }
                dst.setLastModified(src.lastModified())
                src.length()
            } catch (e: IOException) {
                throw e
            }
        }
        if (!src.isDirectory) return 0L
        if (!dst.exists() && !dst.mkdirs()) throw IOException("Could not create $dst")
        var total = 0L
        for (child in src.listFiles().orEmpty()) {
            total += copyTree(child, File(dst, child.name))
        }
        return total
    }

    companion object {
        const val DIR_NAME = "backup-undo"
        const val TREE = "tree"
        const val MANIFEST = "manifest.json"

        /** 2 GB: a phone-sized snapshot, well above real backups, far below a rootfs. */
        const val DEFAULT_MAX_BYTES = 2L * 1024 * 1024 * 1024
        const val DEFAULT_KEEP = 3
        const val DEFAULT_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    }
}
