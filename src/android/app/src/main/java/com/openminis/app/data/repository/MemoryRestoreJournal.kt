package com.openminis.app.data.repository

import com.openminis.app.util.Digest
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.util.UUID
import org.json.JSONObject

/** Two file replacements are not atomic. A committed rollback journal holds the old pair until both are durable. */
internal object MemoryRestoreJournal {
    const val DIRECTORY_PREFIX = ".memory-restore-"
    /** [P3-6] Journal directories renamed for deletion; cleaned up on recovery. */
    internal const val RETIRED_PREFIX = "retired-"
    private const val DIARY = "diary"
    private const val METADATA = "metadata"
    private const val MANIFEST = "original.json"

    data class PairFiles(val diary: File?, val metadata: File?)

    /** Internal crash injection; production leaves it null. An Error models loss of the process, not a caught I/O error. */
    internal var beforeCommitForTest: ((publishedMembers: Int) -> Unit)? = null

    fun original(dir: File, source: String): PairFiles? {
        val journal = pending(dir, source) ?: return null
        val manifest = MemoryLifecycle.child(journal, MANIFEST)?.takeIf { it.isFile }
            ?: throw IOException("Missing memory restore journal manifest")
        val descriptor = JSONObject(manifest.readText())
        fun originalFile(name: String): File? {
            if (!descriptor.has(name)) throw IOException("Incomplete memory restore journal")
            if (descriptor.isNull(name)) return null
            val member = MemoryLifecycle.child(journal, name)?.takeIf { it.isFile }
                ?: throw IOException("Missing original memory $name")
            if (sha(member) != descriptor.getString(name)) throw IOException("Corrupt original memory $name")
            return member
        }
        return PairFiles(originalFile(DIARY), originalFile(METADATA))
    }

    /** Finish an interrupted rollback on disk before tools see the session/global tree again. */
    fun recoverInterrupted(filesDir: File) {
        val sessionRoot = File(filesDir, "minis-sessions")
        val sessionDirs = sessionRoot.listFiles().orEmpty().asSequence()
            .filter { it.isDirectory && !Files.isSymbolicLink(it.toPath()) }
            .map { File(it, "memory") }
        for (directory in sequenceOf(MemoryIndex.sharedDir(filesDir)) + sessionDirs) {
            if (!metadataDirectoryExists(directory)) continue
            runCatching {
                MemoryLifecycle.locked(directory) { dir ->
                    val root = MemoryLifecycle.metadataDirectory(dir)
                    val safeRoot = MemoryLifecycle.child(checkNotNull(root.parentFile), root.name) ?: return@locked
                    for (candidate in safeRoot.listFiles().orEmpty()) {
                        val name = candidate.name
                        if (name.startsWith("$DIRECTORY_PREFIX$RETIRED_PREFIX") && candidate.isDirectory) {
                            // [P3-6] retire() renames the journal to retired-* and
                            // then deletes it; a kill in between leaves the renamed
                            // directory behind. Its content is no longer
                            // authoritative either way, so the leftover is garbage.
                            runCatching { candidate.deleteRecursively() }.onFailure {
                                android.util.Log.w("MemoryRestoreJournal", "Could not clean $name", it)
                            }
                            continue
                        }
                        if (!name.startsWith(DIRECTORY_PREFIX) || !name.endsWith(".pending")) continue
                        val source = name.removePrefix(DIRECTORY_PREFIX).removeSuffix(".pending")
                        if (!MemoryDiaryFormat.dateFile.matches(source)) continue
                        runCatching { recover(dir, source) }.onFailure {
                            android.util.Log.w("MemoryRestoreJournal", "Could not recover $source", it)
                        }
                    }
                }
            }.onFailure { android.util.Log.w("MemoryRestoreJournal", "Could not inspect memory restore state", it) }
        }
    }

    private fun metadataDirectoryExists(directory: File): Boolean =
        MemoryLifecycle.metadataDirectory(directory).isDirectory

    /** Must be under the diary directory lock. Read-only callers use [original] instead. */
    fun recover(dir: File, source: String) {
        val journal = pending(dir, source) ?: return
        val before = original(dir, source) ?: throw IOException("Missing memory restore journal")
        val target = targets(dir, source)
        replace(before.diary, target.first)
        replace(before.metadata, target.second)
        retire(journal)
    }

    /** Incoming was fully validated before we touch the target. Old and new copies never mix in a memory read. */
    fun publish(dir: File, source: String, incoming: PairFiles) {
        recover(dir, source)
        val (diary, metadata) = targets(dir, source)
        val sideRoot = checkNotNull(metadata.parentFile)
        Files.createDirectories(sideRoot.toPath())
        sideRoot.parentFile?.let(::syncDirectory)
        val journal = File(sideRoot, "$DIRECTORY_PREFIX$source.pending")
        val scratch = Files.createTempDirectory(sideRoot.toPath(), "${DIRECTORY_PREFIX}stage-").toFile()
        try {
            val descriptor = JSONObject()
            for ((name, target) in listOf(DIARY to diary, METADATA to metadata)) {
                val old = existing(target)
                if (old == null) descriptor.put(name, JSONObject.NULL) else {
                    val copy = File(scratch, name)
                    copySynced(old, copy)
                    descriptor.put(name, sha(copy))
                }
            }
            writeLifecycleAtomic(File(scratch, MANIFEST), descriptor.toString())
            syncDirectory(scratch)
            Files.move(scratch.toPath(), journal.toPath(), ATOMIC_MOVE)
            syncDirectory(sideRoot)
            try {
                replace(incoming.diary, diary)
                beforeCommitForTest?.invoke(1)
                replace(incoming.metadata, metadata)
                beforeCommitForTest?.invoke(2)
                retire(journal) // Only this rename makes the new pair visible.
                MemoryLifecycle.invalidateRecall()
            } catch (e: Exception) {
                // Ordinary failure should restore the raw files immediately; if that also fails the
                // journal stays authoritative, and a later mutation/restore will retry recovery.
                try { recover(dir, source) } catch (rollback: Exception) { e.addSuppressed(rollback) }
                throw e
            }
        } finally {
            scratch.deleteRecursively()
        }
    }

    private fun pending(dir: File, source: String): File? {
        if (!MemoryDiaryFormat.dateFile.matches(source)) return null
        val base = MemoryLifecycle.metadataDirectory(dir)
        val candidate = File(base, "$DIRECTORY_PREFIX$source.pending")
        if (!Files.exists(candidate.toPath(), NOFOLLOW_LINKS)) return null
        val safeBase = MemoryLifecycle.child(checkNotNull(base.parentFile), base.name)
            ?: throw IOException("Linked lifecycle directory")
        return MemoryLifecycle.child(safeBase, candidate.name)?.takeIf { it.isDirectory }
            ?: throw IOException("Invalid memory restore journal")
    }

    private fun targets(dir: File, source: String): Pair<File, File> =
        (MemoryLifecycle.child(dir, source) ?: throw IOException("Invalid diary target")) to
            (MemoryLifecycle.sidecar(dir, source) ?: throw IOException("Invalid lifecycle target"))

    private fun existing(target: File): File? = when {
        target.isFile -> target
        Files.exists(target.toPath(), NOFOLLOW_LINKS) -> throw IOException("Invalid memory restore target")
        else -> null
    }

    private fun replace(source: File?, target: File) {
        val parent = checkNotNull(target.parentFile)
        if (source == null) {
            if (Files.deleteIfExists(target.toPath())) syncDirectory(parent)
            return
        }
        Files.createDirectories(parent.toPath())
        val temp = Files.createTempFile(parent.toPath(), DIRECTORY_PREFIX, ".tmp").toFile()
        try {
            copySynced(source, temp)
            Files.move(temp.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            syncDirectory(parent)
        } finally {
            temp.delete()
        }
    }

    private fun copySynced(from: File, to: File) {
        FileOutputStream(to).use { out ->
            from.inputStream().use { it.copyTo(out) }
            Files.setLastModifiedTime(to.toPath(), Files.getLastModifiedTime(from.toPath()))
            out.fd.sync()
        }
    }

    private fun sha(file: File): String = file.inputStream().use(Digest::sha256Hex)

    private fun retire(journal: File) {
        val parent = checkNotNull(journal.parentFile)
        val retired = File(parent, "$DIRECTORY_PREFIX${RETIRED_PREFIX}${UUID.randomUUID()}")
        Files.move(journal.toPath(), retired.toPath(), ATOMIC_MOVE)
        syncDirectory(parent)
        retired.deleteRecursively()
    }

    private fun syncDirectory(dir: File) {
        // Directory handles are unavailable on some filesystems (e.g. Windows); file data is still synced.
        runCatching { FileChannel.open(dir.toPath(), READ).use { it.force(true) } }
    }
}
