package com.openminis.app.backup

import com.openminis.app.logging.AppLogger
import com.openminis.app.util.Digest
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/**
 * Content-addressed blob writer for the staging directory (§2), mirroring
 * `src/ios/Agent/Backup/BackupBlobStore.swift`.
 *
 * Two jobs:
 *   1. **Dedup by content.** The same bytes referenced from several sessions —
 *      or from both a message attachment and a session's file tree — are stored
 *      once under their SHA-256. Callers don't coordinate; they offer files and
 *      get told which hash they landed on.
 *   2. **Enforce the §3.4 size cap.** Over-limit files are refused as
 *      [Outcome.SkippedTooLarge] and the caller records a tombstone instead of
 *      a blob. The cap is checked before any hashing or copying, so a 2GB file
 *      costs a `length()`, not a read.
 *
 * @param maxFileBytes null = unlimited (the §3.4 default).
 * @param reuse [Wave3 #10b] incremental fast path — resolves an UNCHANGED
 *   source file to a blob staged from the previous package (see
 *   [BackupIncrementalManifest]); null disables the fast path.
 * @param incremental receives one record per stored blob so the next run's
 *   [reuse] can recognise unchanged files; null skips bookkeeping.
 */
class BackupBlobStore(
    internal val stagingRoot: File,
    private val maxFileBytes: Long?,
    private val reuse: Reuse? = null,
    private val incremental: BackupIncrementalManifest? = null,
) {

    /**
     * [Wave3 #10b] Resolve [file] to already-packaged bytes. The resolver owns
     * the previous package and the trust decision (size + mtime match);
     * [ReusedBlob.staged] is a temp file whose bytes this store renames into
     * the blob tree. Returning null takes the normal read+hash+copy path.
     */
    fun interface Reuse {
        fun resolve(file: File, logicalPath: String, size: Long): ReusedBlob?
    }

    class ReusedBlob(val sha256: String, val staged: File)

    /**
     * `blobs/<first 2 hex>/<sha256>` — the two-level fan-out keeps any single
     * directory small enough to stay fast on huge packages.
     */
    private val blobsRoot = File(stagingRoot, "blobs")

    /**
     * The configured cap, for `manifest.limits.max_file_bytes`. Recorded even
     * when nothing was skipped, so a reader can tell "unlimited" apart from
     * "capped, but nothing exceeded it".
     */
    val maxFileBytesForManifest: Long? get() = maxFileBytes

    /** Hashes already written this run — cheap in-memory dedup. */
    private val seen = mutableSetOf<String>()

    private val _blobIndex = mutableListOf<BackupBlobIndexEntry>()
    val blobIndex: List<BackupBlobIndexEntry> get() = _blobIndex

    var totalBytesStored = 0L
        private set
    var skippedFiles = 0
        private set
    var skippedBytes = 0L
        private set

    /** Paths dropped by the cap, surfaced on the completion screen (§3.4). */
    val skippedPaths = mutableListOf<SkippedPath>()

    /**
     * Why a file stayed out of the package. The counter alone cannot carry
     * this: "too large", "excluded on purpose" and "could not be read" are
     * different problems wearing one number, and the completion screen used
     * to label the whole count with whichever of them happened to match the
     * current size-cap setting.
     */
    enum class SkipReason {
        /** Above the user's §3.4 per-file cap. */
        SIZE_CAP,
        /** Cap 0 — "do not back up files"; nothing exceeded anything. */
        NO_FILES,
        /** Unreadable / invalid at export time (diary pair, corrupt source). */
        UNREADABLE,
        /** A credential source could not be read (oauth token store, …). */
        CREDENTIALS,
        ;

        /**
         * Stored in the history record and the package tombstones. Same
         * vocabulary [BackupMemoryTree] already writes into its inventory.
         */
        val wireName: String
            get() = when (this) {
                SIZE_CAP -> "size_cap"
                NO_FILES -> "no_files"
                UNREADABLE -> "unreadable"
                CREDENTIALS -> "credentials"
            }
    }

    data class SkippedPath(val path: String, val size: Long, val reason: SkipReason)

    /**
     * [T-backup-decode-honesty] Record a skip that happened OUTSIDE the blob
     * pipeline (e.g. an unreadable credential store) so it surfaces exactly
     * like a size-cap skip in the counts ([skippedFiles] drives the run's
     * "with issues" status and the entry lands in the history record's
     * skipped list) — while keeping its OWN reason, so the report can say
     * what actually went wrong instead of blaming the size cap.
     */
    fun recordSkip(logicalPath: String, size: Long = 0, reason: SkipReason) {
        skippedFiles += 1
        skippedBytes += size
        skippedPaths.add(SkippedPath(logicalPath, size, reason))
    }

    sealed interface Outcome {
        data class Stored(val sha256: String, val size: Long) : Outcome
        /** Same content already in the package; no new bytes written. */
        data class Duplicate(val sha256: String, val size: Long) : Outcome
        /** Excluded by the §3.4 cap. Caller must write a tombstone. */
        data class SkippedTooLarge(val size: Long) : Outcome
    }

    /**
     * Rebuild in-memory state from a staging tree left by an interrupted run.
     *
     * `blobs.index.jsonl` is only written at the very end of an export, so a
     * resumed run cannot read its own prior index back. `files.index.jsonl` IS
     * written incrementally and every stored entry carries the digest, so the
     * index is reconstructed from there and cross-checked against the blobs
     * actually on disk.
     *
     * Entries whose blob is missing (the run died between writing the index
     * line and copying the content) are deliberately NOT re-registered: the
     * digest stays out of [seen], so the next pass re-stores that file. That is
     * what stops a resumed package from referencing content it doesn't contain.
     */
    fun rehydrateFromFileIndex(fileIndex: File) {
        if (!fileIndex.exists()) return
        var restored = 0
        fileIndex.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                val entry = runCatching {
                    BackupFormat.json.decodeFromString(BackupFileIndexEntry.serializer(), line)
                }.getOrNull() ?: continue
                val sha = entry.sha256 ?: continue
                if (entry.skipped != null || entry.isDirectory == true) continue
                if (!seen.add(sha)) continue
                val blob = blobFile(sha)
                if (!blob.exists()) {
                    seen.remove(sha)
                    continue
                }
                totalBytesStored += entry.size
                _blobIndex.add(
                    BackupBlobIndexEntry(sha, entry.size, entry.path, null, mimeType(entry.path))
                )
                restored += 1
            }
        }
        if (restored > 0) {
            AppLogger.info(TAG, "[Backup] resume: recovered $restored blob(s) from a previous attempt")
        }
    }

    /**
     * Offer a file on disk to the package. [logicalPath] is only recorded in
     * the index — the blob's location is derived purely from its content hash.
     */
    fun addFile(file: File, logicalPath: String, sessionId: String? = null): Outcome {
        val size = file.length()

        // Cap check FIRST — before hashing or copying. A skipped 2GB file must
        // cost a stat, not a full read.
        if (maxFileBytes != null && size > maxFileBytes) {
            skippedFiles += 1
            skippedBytes += size
            skippedPaths.add(SkippedPath(logicalPath, size, capSkipReason))
            AppLogger.info(TAG, "[Backup] skip (size cap) $logicalPath size=$size > $maxFileBytes")
            return Outcome.SkippedTooLarge(size)
        }

        // [Wave3 #10b] Incremental fast path: an unchanged file (size + mtime
        // match last-manifest.json) has the same content hash it had last run,
        // so its blob is still inside the previous package — take those bytes
        // instead of re-reading and re-hashing the source. Any mismatch (no
        // cache entry, missing zip entry, short extraction, rename failure)
        // falls through to the full path below, so a broken sidecar can only
        // ever cost a full export, never a wrong one.
        val reused = reuse?.resolve(file, logicalPath, size)
        if (reused != null) {
            if (reused.sha256 in seen) {
                reused.staged.delete()
                return Outcome.Duplicate(reused.sha256, size)
            }
            val dest = blobFile(reused.sha256)
            dest.parentFile?.mkdirs()
            if (dest.exists()) dest.delete()
            if (reused.staged.renameTo(dest)) {
                seen.add(reused.sha256)
                totalBytesStored += size
                incremental?.record(logicalPath, reused.sha256, size, file.lastModified())
                _blobIndex.add(
                    BackupBlobIndexEntry(reused.sha256, size, logicalPath, sessionId, mimeType(logicalPath))
                )
                return Outcome.Stored(reused.sha256, size)
            }
            // rename failed (cross-device or fs hiccup): clean up, go full.
            reused.staged.delete()
        }

        val digest = file.inputStream().use { sha256(it) }
        if (digest in seen) return Outcome.Duplicate(digest, size)

        val dest = blobFile(digest)
        dest.parentFile?.mkdirs()
        // An identical digest means identical content, so an existing file is
        // already correct — but `seen` missing it means a prior interrupted run
        // left it. Replace to be safe.
        if (dest.exists()) dest.delete()
        file.copyTo(dest, overwrite = true)

        seen.add(digest)
        totalBytesStored += size
        incremental?.record(logicalPath, digest, size, file.lastModified())
        _blobIndex.add(BackupBlobIndexEntry(digest, size, logicalPath, sessionId, mimeType(logicalPath)))
        return Outcome.Stored(digest, size)
    }

    /** Internal crash injection; production leaves it null. Fires before each member move. */
    internal var beforeMoveForTest: ((memberIndex: Int) -> Unit)? = null

    /** Publish a diary's immutable, validated members as one group; do not reuse stat-only blobs. */
    internal fun addDiaryPair(members: List<Pair<File, String>>, sessionId: String?): List<String> {
        val hashes = members.map { (file, _) -> sha256OfFile(file) }
        val fresh = LinkedHashMap<String, Pair<File, String>>()
        for ((index, member) in members.withIndex()) {
            val hash = hashes[index]
            if (hash !in seen) fresh.putIfAbsent(hash, member)
            else BackupDiaryPairs.verify(blobFile(hash), hash, member.first.length())
        }
        val moved = mutableListOf<File>()
        // [P3-5] A resumed run can leave blobs in the tree that its package
        // index already references (a killed run wrote the blob but never its
        // index line, so rehydration missed it). Such a destination exists
        // BEFORE this run's move — a rollback must not delete it, or the
        // resumed package loses content it references. Only bytes this run
        // created are rolled back.
        val preExisting = HashSet<String>()
        try {
            for ((index, entry) in fresh.entries.withIndex()) {
                val (hash, member) = entry.toPair()
                val destination = blobFile(hash)
                destination.parentFile?.mkdirs()
                if (destination.exists()) preExisting += destination.absolutePath
                beforeMoveForTest?.invoke(index)
                Files.move(member.first.toPath(), destination.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                moved += destination
            }
        } catch (e: Exception) {
            moved.forEach { if (it.absolutePath !in preExisting) it.delete() }
            throw e
        }
        for ((hash, member) in fresh) {
            val path = member.second
            val size = blobFile(hash).length()
            seen.add(hash)
            totalBytesStored += size
            _blobIndex.add(BackupBlobIndexEntry(hash, size, path, sessionId, mimeType(path)))
        }
        return hashes
    }

    /** Offer in-memory bytes (generated content that never hits disk). */
    fun addBytes(data: ByteArray, logicalPath: String, sessionId: String? = null): Outcome {
        val size = data.size.toLong()
        if (maxFileBytes != null && size > maxFileBytes) {
            skippedFiles += 1
            skippedBytes += size
            skippedPaths.add(SkippedPath(logicalPath, size, capSkipReason))
            return Outcome.SkippedTooLarge(size)
        }
        val digest = Digest.sha256Hex(data)
        if (digest in seen) return Outcome.Duplicate(digest, size)

        val dest = blobFile(digest)
        dest.parentFile?.mkdirs()
        dest.writeBytes(data)

        seen.add(digest)
        totalBytesStored += size
        _blobIndex.add(BackupBlobIndexEntry(digest, size, logicalPath, sessionId, mimeType(logicalPath)))
        return Outcome.Stored(digest, size)
    }

    private fun blobFile(digest: String) = File(File(blobsRoot, digest.take(2)), digest)

    /**
     * A cap of 0 is "do not back up files" (§3.4's sentinel): nothing is
     * "too large" then, everything is excluded on purpose. Reporting both
     * states under one reason made the completion screen lie in whichever
     * direction the wording did not match. Exposed for the diary-pair skip
     * path, which records its members through [recordSkip] directly.
     */
    internal val capSkipReason: SkipReason
        get() = if (maxFileBytes != null && maxFileBytes <= 0L) SkipReason.NO_FILES else SkipReason.SIZE_CAP

    companion object {
        private const val TAG = "Backup"

        /**
         * Streaming SHA-256 — never loads the file into memory. Media offloads
         * run to hundreds of MB; [Digest.sha256Hex] reads in chunks regardless
         * of size. [T-sha256-single-source]
         */
        fun sha256(input: InputStream): String = Digest.sha256Hex(input)

        fun sha256OfFile(file: File): String = file.inputStream().buffered().use { sha256(it) }

        fun mimeType(path: String): String? =
            when (path.substringAfterLast('.', "").lowercase()) {
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "heic" -> "image/heic"
                "pdf" -> "application/pdf"
                "txt", "log" -> "text/plain"
                "md" -> "text/markdown"
                "json" -> "application/json"
                "zip" -> "application/zip"
                "mp4", "m4v" -> "video/mp4"
                "mov" -> "video/quicktime"
                "mp3" -> "audio/mpeg"
                "m4a" -> "audio/mp4"
                "wav" -> "audio/wav"
                else -> null
            }
    }
}
