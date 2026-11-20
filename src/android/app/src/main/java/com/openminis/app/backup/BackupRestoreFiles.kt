package com.openminis.app.backup

import com.openminis.app.data.repository.MemoryLifecycle
import com.openminis.app.logging.AppLogger
import java.io.File
import java.util.UUID

/**
 * Writes a package's file tree back onto the device.
 *
 * Split out of the importer because the two rules it enforces are the ones with
 * teeth, and they must apply identically to every category rather than being
 * re-stated per call site.
 */
object BackupRestoreFiles {

    private const val TAG = "Restore"

    data class Result(
        var written: Int = 0,
        var bytes: Long = 0,
        /**
         * Index entries whose content blob is MISSING from the package.
         *
         * The export is only a snapshot to the extent its cut-off holds: a file
         * recorded in files.index between the index pass and the blob copy can
         * be referenced and then never stored. This used to be worth a silent
         * skip; it is not. The file simply never appears and the restore still
         * reports success, which is the one outcome a backup tool must never
         * produce. Counted and surfaced instead (iOS review S7).
         */
        var missingBlobs: Int = 0,
        /** §3.4 tombstones — the export excluded these by the size cap. */
        var sizeSkippedInPackage: Int = 0,
        /** Tombstones for files that were not downloaded on the source device. */
        var notDownloadedInPackage: Int = 0,
        /** Entries refused because their path escaped the category root. */
        var rejectedPaths: Int = 0,
    )

    /**
     * Restore every [fileIndex] entry belonging to [category].
     *
     * @param destinationFor maps a package-relative logical path to its
     *   destination on this device, or null to ignore the entry.
     * @param containmentRoot every write must land inside this directory.
     */
    fun restore(
        packageRoot: File,
        fileIndex: List<BackupFileIndexEntry>,
        category: BackupCategory,
        containmentRoot: File,
        destinationFor: (String) -> File?,
    ): Result = restoreTree(packageRoot, fileIndex, category, containmentRoot, false, destinationFor)

    internal fun restoreSessionTrees(packageRoot: File, fileIndex: List<BackupFileIndexEntry>, sessionsRoot: File): Result =
        restoreTree(packageRoot, fileIndex, BackupCategory.CHATS, sessionsRoot, true) { path ->
            val parts = path.split('/')
            if (parts.size < 3 || parts[0] != "chats" || parts[1] == "_bodies") null
            else File(sessionsRoot, parts.drop(1).joinToString("/"))
        }

    private fun restoreTree(
        packageRoot: File,
        fileIndex: List<BackupFileIndexEntry>,
        category: BackupCategory,
        containmentRoot: File,
        pairSessionDiaries: Boolean,
        destinationFor: (String) -> File?,
    ): Result {
        val result = Result()
        val root = containmentRoot.canonicalFile
        val diaryPairs = LinkedHashMap<Pair<String, String>, MutableList<Pair<BackupDiaryPairs.Member, BackupFileIndexEntry>>>()
        val invalidPairs = HashSet<Pair<String, String>>()

        for (entry in fileIndex) {
            if (entry.category != category.key) continue
            val diaryPath = if (pairSessionDiaries && entry.path.startsWith("chats/")) {
                BackupDiaryPairs.sessionPath(entry.path.removePrefix("chats/"))
            } else null
            if (diaryPath != null) {
                val key = diaryPath.session to diaryPath.path.source
                val destination = destinationFor(entry.path)
                val expected = File(root, entry.path.removePrefix("chats/"))
                if (destination == null || !isContained(destination, root) || destination.canonicalFile != expected || expected.canonicalFile != expected) {
                    invalidPairs += key
                    if (destination != null) result.rejectedPaths++
                    continue
                }
                val paired = diaryPairs.getOrPut(key) { mutableListOf() }
                paired += diaryPath.path.member to entry
                continue
            }

            if (countTombstone(entry, result)) continue
            val dest = destinationFor(entry.path) ?: continue
            if (pairSessionDiaries && isContained(dest, root)) {
                val relative = dest.canonicalFile.relativeTo(root).invariantSeparatorsPath
                if (BackupDiaryPairs.sessionPath(relative) != null ||
                    BackupDiaryPairs.isInternalRestorePath(relative.substringAfter('/', ""))
                ) {
                    // An alias is not allowed to bypass the paired route (nor install a restore journal).
                    result.rejectedPaths++
                    continue
                }
            }

            // Path traversal via index CONTENT.
            //
            // `entry.path` comes from files.index.jsonl INSIDE the package,
            // which is attacker-controlled whenever a user restores a file
            // someone sent them — and this feature exists to accept packages
            // shared over messaging apps and Files. The ZIP extractor's own
            // traversal guard does NOT cover this: the archive's entry names
            // are all benign (`blobs/<xx>/<sha>`), and the malicious path lives
            // in the index content, not in an entry name.
            //
            // Checked on the canonical path, which is what the filesystem will
            // actually use.
            if (!isContained(dest, root)) {
                AppLogger.error(TAG, "[Restore] REJECTED path escaping its category root: ${entry.path}")
                result.rejectedPaths += 1
                continue
            }

            if (entry.isDirectory == true) {
                dest.mkdirs()
                continue
            }
            val sha = entry.sha256 ?: continue
            if (entry.size < 0 || !sha.matches(Regex("[0-9a-f]{64}"))) {
                result.rejectedPaths += 1
                continue
            }
            val blobsRoot = File(packageRoot, "blobs").canonicalFile
            val blob = File(File(blobsRoot, sha.take(2)), sha)
            if (!isContained(blob, blobsRoot)) {
                result.rejectedPaths += 1
                continue
            }
            if (!blob.isFile) {
                AppLogger.info(
                    TAG,
                    "[Restore] index references a blob not in the package: ${entry.path} sha=${sha.take(12)}"
                )
                result.missingBlobs += 1
                continue
            }

            dest.parentFile?.mkdirs()
            val memoryTime = entry.memoryMtimeMs?.takeIf {
                pairSessionDiaries && BackupDiaryPairs.isSessionMemoryMarkdown(
                    entry.path.removePrefix("chats/").substringAfter('/', ""),
                )
            }
            if (writeViaStagedSwap(blob, dest, entry.path, memoryTime)) {
                result.written += 1
                result.bytes += entry.size
            }
        }
        for ((key, parts) in diaryPairs) {
            if (key in invalidPairs) continue
            val (session, source) = key
            restoreDiaryPair(packageRoot, File(root, "$session/memory"), source, parts, result)
        }
        return result
    }

    private fun countTombstone(entry: BackupFileIndexEntry, result: Result): Boolean {
        if (entry.skipped == null) return false
        if (entry.skipped == "not_downloaded") result.notDownloadedInPackage++ else result.sizeSkippedInPackage++
        return true
    }

    private fun restoreDiaryPair(
        packageRoot: File,
        directory: File,
        source: String,
        parts: List<Pair<BackupDiaryPairs.Member, BackupFileIndexEntry>>,
        result: Result,
    ) {
        // A prior writer might have emitted only half of a pair. An orphan sidecar
        // is never eligible to replace the metadata for the device's existing diary.
        if (parts.map { countTombstone(it.second, result) }.any { it }) return
        val diary = parts.singleOrNull { it.first == BackupDiaryPairs.Member.DIARY }?.second
        val metadata = parts.singleOrNull { it.first == BackupDiaryPairs.Member.METADATA }?.second
        if (diary == null || parts.size != 1 + (if (metadata != null) 1 else 0) ||
            parts.any { it.second.isDirectory == true || it.second.size < 0 } ||
            (diary.memoryPair != null && diary.memoryPair != (metadata?.sha256 ?: BackupDiaryPairs.NO_METADATA))
        ) {
            result.rejectedPaths++
            return
        }
        val blobs = File(packageRoot, "blobs").canonicalFile
        fun member(index: BackupFileIndexEntry): File? {
            val sha = index.sha256
            if (sha == null || !SHA256.matches(sha)) { result.rejectedPaths++; return null }
            val path = File(File(blobs, sha.take(2)), sha)
            if (!isContained(path, blobs) || path.canonicalFile != path) { result.rejectedPaths++; return null }
            if (!path.isFile) { result.missingBlobs++; return null }
            return path
        }
        val body = member(diary) ?: return
        val meta = metadata?.let { member(it) ?: return }
        try {
            BackupDiaryPairs.publish(directory, source, body, meta, legacy = diary.memoryPair == null,
                diaryMtimeMs = diary.memoryMtimeMs, metadataMtimeMs = metadata?.memoryMtimeMs,
            ) { staged ->
                BackupDiaryPairs.verify(checkNotNull(staged.diary), checkNotNull(diary.sha256), diary.size)
                if (metadata != null) BackupDiaryPairs.verify(checkNotNull(staged.metadata), checkNotNull(metadata.sha256), metadata.size)
            }
        } catch (e: Exception) {
            AppLogger.warning(TAG, "[Restore] kept previous diary $source: ${e.message}")
            result.rejectedPaths++
            return
        }
        result.written += parts.size
        result.bytes += diary.size + (metadata?.size ?: 0L)
    }

    private val SHA256 = Regex("[0-9a-f]{64}")

    /**
     * Copy to a sibling temp file, then rename over the destination.
     *
     * NOT delete-then-copy. That pair leaves the user's existing file
     * NON-EXISTENT for the whole duration of the copy, so a process kill in
     * that window deletes it permanently with nothing written back — and the
     * window is entered once per file. Merge idempotency does not help a file
     * that was deleted and never rewritten. A rename within one filesystem is
     * atomic, so the destination is either the old bytes or the new ones, never
     * missing (iOS review I2).
     */
    private fun writeViaStagedSwap(blob: File, dest: File, logicalPath: String, memoryMtimeMs: Long?): Boolean {
        val staged = File(dest.parentFile, ".restore-${UUID.randomUUID()}.tmp")
        return try {
            if (memoryMtimeMs != null) {
                MemoryLifecycle.locked(checkNotNull(dest.parentFile)) { dir ->
                    val target = MemoryLifecycle.child(dir, dest.name)
                        ?: throw BackupException("invalid memory path $logicalPath")
                    BackupDiaryPairs.restoreMarkdown(blob, target, memoryMtimeMs)
                }
                return true
            }
            blob.copyTo(staged, overwrite = true)
            if (!staged.renameTo(dest)) {
                // Rename can fail across filesystems or if the destination is a
                // non-empty directory. Fall back to an explicit replace, which
                // still never leaves a gap longer than the delete itself.
                if (dest.exists() && !dest.delete()) throw BackupException("cannot replace $logicalPath")
                if (!staged.renameTo(dest)) throw BackupException("cannot finalise $logicalPath")
            }
            true
        } catch (e: Exception) {
            AppLogger.error(TAG, "[Restore] failed to write $logicalPath: ${e.message}")
            staged.delete()
            false
        }
    }

    /**
     * True when [candidate] resolves inside [root].
     *
     * Canonicalises both sides so `..` segments and symlinks are resolved the
     * way the kernel will resolve them. The parent is canonicalised when the
     * candidate does not exist yet, since a non-existent path has no canonical
     * form of its own on every filesystem.
     */
    private fun isContained(candidate: File, root: File): Boolean = runCatching {
        val rootPath = root.canonicalPath
        val target = if (candidate.exists()) {
            candidate.canonicalFile
        } else {
            File(candidate.parentFile?.canonicalFile ?: return false, candidate.name)
        }
        val path = target.path
        path == rootPath || path.startsWith(rootPath + File.separator)
    }.getOrDefault(false)
}
