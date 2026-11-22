package com.openminis.app.backup

import com.openminis.app.data.repository.MemoryLifecycle
import com.openminis.app.logging.AppLogger
import java.io.File

/**
 * Walks a directory tree into the package: content into the blob store,
 * structure into `files.index.jsonl`. Mirrors
 * `src/ios/Agent/Backup/BackupFileTreeExporter.swift`.
 *
 * Shared by every category that carries files — Chats' `<sid>/` directories,
 * Shared Files, and Skills' attached files — so the §3.4 size cap and the dedup
 * behaviour are identical everywhere by construction, rather than by three
 * exporters agreeing to behave the same way.
 */
class BackupFileTreeExporter(
    private val blobStore: BackupBlobStore,
    private val fileIndex: BackupFileIndexWriter,
    /**
     * Snapshot cut-off. Files modified after this instant are not part of this
     * backup's view of the data.
     *
     * **Excludes only, never includes.** mtime is not always trustworthy: a
     * file restored from another backup, synced down by a provider, or copied
     * in can carry an mtime unrelated to when this device saw it, and a clock
     * change moves every comparison at once. So the rule is asymmetric on
     * purpose — a file is dropped ONLY when its mtime is clearly after the
     * cut-off, and anything unreadable or ambiguous is INCLUDED. Wrongly
     * including a slightly-too-new file costs a little package size; wrongly
     * excluding one silently loses data from the user's backup, which is the
     * failure this feature exists to prevent.
     */
    private val snapshotAtMillis: Long = Long.MAX_VALUE,
) {

    data class Result(
        var filesIncluded: Int = 0,
        var filesSkipped: Int = 0,
        var bytesIncluded: Long = 0,
        var directories: Int = 0,
        /**
         * Files excluded because they were modified after the snapshot cut-off.
         * NOT a gap in the backup — they are outside this backup's point in
         * time, so they get no tombstone.
         */
        var filesAfterSnapshot: Int = 0,
    )

    /**
     * Export everything under [root] into the package beneath [logicalPrefix].
     *
     * A missing root is not an error: a user with no skills, or a session with
     * no files, is the normal case and must not fail the whole backup.
     */
    fun export(
        root: File,
        logicalPrefix: String,
        category: BackupCategory,
        sessionId: String? = null,
    ): Result {
        val result = Result()
        if (!root.exists() || !root.isDirectory) return result

        val base = root.canonicalFile
        val tree = base.walkTopDown().onEnter { directory ->
            category != BackupCategory.CHATS || directory == base ||
                !BackupDiaryPairs.isInternalRestorePath(directory.relativeTo(base).invariantSeparatorsPath)
        }
        for (entry in tree) {
            // walkTopDown yields the root itself first; it is the prefix, not
            // an entry inside the package.
            if (entry == base) continue

            val rel = entry.relativeTo(base).path.replace(File.separatorChar, '/')
            if (rel.isEmpty()) continue
            val logicalPath = "$logicalPrefix/$rel"

            if (isBackupArtifact(rel)) continue
            val diaryMember = if (category == BackupCategory.CHATS) BackupDiaryPairs.sessionMember(rel) else null
            if (category == BackupCategory.CHATS && (BackupDiaryPairs.isInternalRestorePath(rel) ||
                    BackupMemoryTree.isMemoryTemp(rel) && ('/' !in rel || rel.startsWith("memory/") ||
                    rel.startsWith("${MemoryLifecycle.SIDECAR_DIR}/")))) continue
            if (diaryMember?.member == BackupDiaryPairs.Member.METADATA) continue

            if (entry.isDirectory) {
                // Empty directories would otherwise vanish, since nothing
                // references them.
                if (entry.listFiles()?.isEmpty() == true) {
                    fileIndex.write(BackupFileIndexEntry.directory(logicalPath, category))
                    result.directories += 1
                }
                continue
            }
            if (!entry.isFile) continue // sockets, fifos, dangling symlinks

            if (diaryMember?.member == BackupDiaryPairs.Member.DIARY) {
                exportSessionDiary(entry, logicalPrefix, logicalPath, sessionId, result)
            } else if (inSnapshot(entry, result)) {
                // [T-backup-unknown-mtime] `inSnapshot` treats a 0/negative mtime
                // as "unknown, keep it". Writing that same 0 into
                // memory_mtime_ms would make the restore stamp the file 1970,
                // which `MemoryMetadata.isExpired` then reads as an ancient
                // diary — the exact "unknown must not read as ancient"
                // invariant this file states a few lines above.
                val memoryMtime = if (category == BackupCategory.CHATS && BackupDiaryPairs.isSessionMemoryMarkdown(rel))
                    entry.lastModified().takeIf { it > 0 } else null
                writeFile(entry, logicalPath, sessionId, category, result, memoryMtime)
            }
        }
        return result
    }

    private fun inSnapshot(file: File, result: Result): Boolean {
        val modified = file.lastModified()
        if (modified <= 0 || modified <= snapshotAtMillis) return true
        result.filesAfterSnapshot += 1
        return false
    }

    /** Decide by diary mtime, then stage both blobs before publishing either index row. */
    private fun exportSessionDiary(file: File, prefix: String, logicalPath: String, sessionId: String?, result: Result) {
        val category = BackupCategory.CHATS
        val sidecarPath = "$prefix/${MemoryLifecycle.SIDECAR_DIR}/${file.name}.json"
        val files = mutableListOf(logicalPath to file)
        val entries = try {
            MemoryLifecycle.locked(checkNotNull(file.parentFile)) { dir ->
                val pair = MemoryLifecycle.snapshotFiles(dir, file.name)
                val diary = pair.diary ?: return@locked emptyList()
                if (!inSnapshot(diary, result)) return@locked emptyList()
                files[0] = logicalPath to diary
                pair.metadata?.let { files.add(sidecarPath to it) }
                val cap = blobStore.maxFileBytesForManifest
                if (cap != null && files.any { it.second.length() > cap }) {
                    files.map { (path, member) ->
                        blobStore.recordSkip(path, member.length(), blobStore.capSkipReason)
                        BackupFileIndexEntry.sizeSkipped(path, member.length(), category)
                    }
                } else BackupDiaryPairs.withSnapshot(pair, file.name, blobStore.stagingRoot) { staged ->
                    val members = listOfNotNull(checkNotNull(staged.diary) to logicalPath, staged.metadata?.let { it to sidecarPath })
                    val sizes = members.map { it.first.length() }
                    val mtimes = members.map { it.first.lastModified() } // The blob store moves these staged files.
                    if (cap != null && sizes.any { it > cap }) throw java.io.IOException("Diary pair changed size")
                    MemoryLifecycle.validateBackupPair(checkNotNull(staged.diary), staged.metadata, requireMatchingEntries = false)
                    val hashes = blobStore.addDiaryPair(members, sessionId)
                    members.indices.map { i ->
                        BackupFileIndexEntry.file(members[i].second, sizes[i], hashes[i], category).copy(
                            memoryPair = if (i == 0) hashes.getOrNull(1) ?: BackupDiaryPairs.NO_METADATA else null,
                            memoryMtimeMs = mtimes[i],
                        )
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.warning(TAG, "[Backup] cannot include diary $logicalPath: ${e.message}")
            files.map { (path, member) ->
                blobStore.recordSkip(path, member.length(), BackupBlobStore.SkipReason.UNREADABLE)
                BackupFileIndexEntry.unreadable(path, member.length(), category)
            }
        }
        if (entries.isEmpty()) return
        fileIndex.writeBatch(entries)
        for (index in entries) {
            if (index.skipped != null) result.filesSkipped++ else {
                result.filesIncluded++
                result.bytesIncluded += index.size
            }
        }
    }

    private fun writeFile(
        file: File, path: String, sessionId: String?, category: BackupCategory, result: Result, memoryMtime: Long?,
    ): Boolean = when (val outcome = addFile(file, path, sessionId)) {
            is BackupBlobStore.Outcome.Stored -> {
                fileIndex.write(BackupFileIndexEntry.file(path, outcome.size, outcome.sha256, category).copy(memoryMtimeMs = memoryMtime))
                result.filesIncluded += 1
                result.bytesIncluded += outcome.size
                true
            }
            is BackupBlobStore.Outcome.Duplicate -> {
                fileIndex.write(BackupFileIndexEntry.file(path, outcome.size, outcome.sha256, category).copy(memoryMtimeMs = memoryMtime))
                result.filesIncluded += 1
                true
            }
            is BackupBlobStore.Outcome.SkippedTooLarge -> {
                fileIndex.write(BackupFileIndexEntry.sizeSkipped(path, outcome.size, category))
                result.filesSkipped += 1
                false
            }
            null -> {
                unavailable(file, path, category, result)
                false
            }
        }

    private fun unavailable(file: File, path: String, category: BackupCategory, result: Result) {
        fileIndex.write(BackupFileIndexEntry.unreadable(path, file.length(), category))
        result.filesSkipped += 1
    }

    private fun addFile(file: File, logicalPath: String, sessionId: String?): BackupBlobStore.Outcome? = try {
        blobStore.addFile(file, logicalPath, sessionId)
    } catch (e: Exception) {
        // One bad file must not sink an entire backup — the tombstone above
        // keeps the gap visible.
        AppLogger.error(TAG, "[Backup] unreadable file $logicalPath: ${e.message}")
        null
    }

    /**
     * True for anything that is itself a backup artifact — a delivered
     * `.minisbak`, the directory they are delivered into, or one of the two
     * scratch trees.
     *
     * Without this a backup sweeps up the previous backup as "user data",
     * nesting packages inside packages until the size runs away. Matched on the
     * leading path component as well as the extension, so a whole `Backups/`
     * tree is skipped in one step rather than file by file.
     *
     * [T-restore-plaintext-scratch] `restore-work` (cacheDir, the BackupImporter's
     * DECRYPTED plaintext tree — chats, secrets.json, env-var values) and
     * `backup-staging` ([Wave3 #10a] now rooted at getExternalFilesDir(null);
     * the exporter's pre-zip scratch tree) are the
     * same hazard in scratch form: a CACHE-category export taken while a
     * previous restore left its scratch behind would package a full plaintext
     * copy of the user's data into the next backup — and restoring that package
     * would then write it back over the importer's own work directory. Neither
     * is user data; both must never be walked.
     */
    private fun isBackupArtifact(rel: String): Boolean {
        if (rel.endsWith(".${BackupFormat.FILE_EXTENSION}")) return true
        return when (rel.substringBefore('/')) {
            BACKUPS_DIR_NAME, RESTORE_WORK_DIR_NAME, STAGING_DIR_NAME -> true
            else -> false
        }
    }

    companion object {
        private const val TAG = "Backup"

        /** Sibling of `shared`/`skills`/`memory`, matching iOS's delivery directory. */
        const val BACKUPS_DIR_NAME = "Backups"

        /** cacheDir scratch of [BackupImporter] — decrypted plaintext, never user data. */
        const val RESTORE_WORK_DIR_NAME = "restore-work"

        /**
         * Scratch of [BackupExporter] — pre-zip staging, never user data.
         * [Wave3 #10a] rooted at getExternalFilesDir(null) (filesDir fallback);
         * the name match stays so legacy filesDir leftovers are still skipped.
         */
        const val STAGING_DIR_NAME = "backup-staging"
    }
}

/**
 * Append-only writer for `files.index.jsonl`.
 *
 * Flushed after every record, deliberately. This file is what a resumed run
 * reads back to discover which blobs an interrupted attempt already stored
 * ([BackupBlobStore.rehydrateFromFileIndex]) — and the interruption it has to
 * survive is the process dying, which is precisely when buffered-but-unwritten
 * lines are lost. A buffer here would make resume silently forget the tail of
 * the previous attempt and re-copy blobs that are already staged.
 *
 * The cost is one small write per file rather than per 8KB; next to hashing and
 * copying the file's contents, that is noise.
 */
class BackupFileIndexWriter(file: File) : AutoCloseable {
    private val stream = file.also { it.parentFile?.mkdirs() }.outputStream()

    var written = 0
        private set

    fun write(entry: BackupFileIndexEntry) = writeBatch(listOf(entry))

    /** A diary and its metadata are appended only after both blobs have landed. */
    internal fun writeBatch(entries: List<BackupFileIndexEntry>) {
        val lines = buildString {
            for (entry in entries) appendLine(BackupFormat.json.encodeToString(BackupFileIndexEntry.serializer(), entry))
        }
        stream.write(lines.toByteArray(Charsets.UTF_8))
        stream.flush()
        written += entries.size
    }

    override fun close() {
        stream.flush()
        stream.close()
    }
}
