package com.openminis.app.backup

import com.openminis.app.data.repository.MemoryDiaryFormat
import com.openminis.app.data.repository.MemoryLifecycle
import com.openminis.app.data.repository.MemoryRestoreJournal
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/** Paths and input verification shared by the session index and the portable global memory tree. */
internal object BackupDiaryPairs {
    const val NO_METADATA = "none"
    private const val SIDECAR = MemoryLifecycle.SIDECAR_DIR
    private val digest = Regex("[0-9a-f]{64}")
    private const val MEMORY = "memory"

    enum class Member { DIARY, METADATA }
    data class MemberPath(val source: String, val member: Member)
    data class SessionMember(val session: String, val path: MemberPath)

    fun globalMember(relative: String): MemberPath? = when {
        MemoryDiaryFormat.dateFile.matches(relative) -> MemberPath(relative, Member.DIARY)
        else -> metadataSource(relative)?.let { MemberPath(it, Member.METADATA) }
    }

    fun sessionMember(relative: String): MemberPath? = when {
        relative.startsWith("$MEMORY/") && MemoryDiaryFormat.dateFile.matches(relative.removePrefix("$MEMORY/")) ->
            MemberPath(relative.removePrefix("$MEMORY/"), Member.DIARY)
        else -> metadataSource(relative)?.let { MemberPath(it, Member.METADATA) }
    }

    private fun metadataSource(relative: String): String? = relative
        .takeIf { it.startsWith("$SIDECAR/") && it.endsWith(".json") }
        ?.removePrefix("$SIDECAR/")?.removeSuffix(".json")?.takeIf { MemoryDiaryFormat.dateFile.matches(it) }

    fun sessionPath(relative: String): SessionMember? {
        val session = relative.substringBefore('/')
        if (session.isEmpty() || session == "." || session == ".." || session.any { it == '\\' || it == ':' } ||
            !relative.contains('/')
        ) return null
        val member = sessionMember(relative.substringAfter('/')) ?: return null
        return SessionMember(session, member)
    }

    fun isInternalRestorePath(relative: String): Boolean {
        // A journal is host-owned on the destination. It is not a backup member.
        val top = relative.substringBefore('/')
        if (top.startsWith(MemoryRestoreJournal.DIRECTORY_PREFIX)) return true
        if (relative.startsWith("$SIDECAR/") || relative.startsWith("$MEMORY/")) {
            return relative.substringAfter('/').substringBefore('/').startsWith(MemoryRestoreJournal.DIRECTORY_PREFIX)
        }
        return false
    }

    fun fingerprint(file: File): String = BackupBlobStore.sha256OfFile(file)

    fun verify(file: File, expected: String, size: Long? = null) {
        if (!file.isFile || !digest.matches(expected) || (size != null && file.length() != size) || fingerprint(file) != expected) {
            throw IOException("Invalid memory backup member")
        }
    }

    /** Lock protects host changes; the private snapshot also decouples hashing from external diary edits. */
    fun <T> withSnapshot(
        files: MemoryRestoreJournal.PairFiles,
        source: String,
        stagingRoot: File,
        use: (MemoryRestoreJournal.PairFiles) -> T,
    ): T {
        Files.createDirectories(stagingRoot.toPath())
        val stage = Files.createTempDirectory(stagingRoot.toPath(), "${MemoryRestoreJournal.DIRECTORY_PREFIX}stage-").toFile()
        try {
            val diary = files.diary?.copyTo(File(stage, source)) ?: throw IOException("Missing diary")
            val metadata = files.metadata?.copyTo(File(stage, "$source.json"))
            return use(MemoryRestoreJournal.PairFiles(diary, metadata))
        } finally {
            stage.deleteRecursively()
        }
    }

    fun exists(file: File): Boolean = Files.exists(file.toPath(), NOFOLLOW_LINKS)

    fun publish(
        directory: File,
        source: String,
        diary: File,
        metadata: File?,
        legacy: Boolean,
        verify: (MemoryRestoreJournal.PairFiles) -> Unit = {},
    ) {
        MemoryLifecycle.locked(directory) { dir ->
            val metadataDirectory = MemoryLifecycle.metadataDirectory(dir)
            val staging = MemoryLifecycle.child(checkNotNull(metadataDirectory.parentFile), metadataDirectory.name)
                ?: throw IOException("Invalid lifecycle staging directory")
            withSnapshot(MemoryRestoreJournal.PairFiles(diary, metadata), source, staging) { staged ->
                verify(staged)
                MemoryLifecycle.validateBackupPair(checkNotNull(staged.diary), staged.metadata, requireMatchingEntries = legacy)
                MemoryRestoreJournal.publish(dir, source, staged)
            }
        }
    }
}
