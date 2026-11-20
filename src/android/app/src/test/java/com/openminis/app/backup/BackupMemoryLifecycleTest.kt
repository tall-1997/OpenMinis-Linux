package com.openminis.app.backup

import com.openminis.app.data.repository.MemoryEntryOrigin
import com.openminis.app.data.repository.MemoryLifecycle
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.MemoryRestoreJournal
import com.openminis.app.util.IsoTime
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BackupMemoryLifecycleTest {
    @get:Rule val tmp = TemporaryFolder()
    private val start = 1_798_200_000_000L
    private val source = "${IsoTime.formatLocalDate(start)}.md"

    @Test fun chatsBackupIncludesLateLifecycleSidecarWithItsDiary() {
        val root = tmp.newFolder("from")
        val session = File(root, "minis-sessions/sid")
        val dir = File(session, "memory")
        val sidecar = seed(dir)
        val cutoff = System.currentTimeMillis() - 10_000L
        assertTrue(File(dir, source).setLastModified(cutoff))
        assertTrue(sidecar.setLastModified(cutoff + 10_000))
        val pkg = tmp.newFolder("package")
        val indexFile = File(pkg, "files.index.jsonl")
        BackupFileIndexWriter(indexFile).use { writer ->
            val result = BackupFileTreeExporter(BackupBlobStore(pkg, maxFileBytes = null), writer, cutoff)
                .export(session, "chats/sid", BackupCategory.CHATS, "sid")
            assertEquals(2, result.filesIncluded)
            assertEquals(0, result.filesAfterSnapshot)
        }
        val index = indexFile.readLines().map { BackupFormat.json.decodeFromString(BackupFileIndexEntry.serializer(), it) }
        assertTrue(index.any { it.path == "chats/sid/.memory-lifecycle/$source.json" })
        val restored = File(tmp.newFolder("to"), "minis-sessions")
        val result = BackupRestoreFiles.restoreSessionTrees(pkg, index, restored)
        assertEquals(2, result.written)
        checkRestored(File(restored, "sid/memory"))
    }

    @Test fun standaloneMemoryBackupRoundTripsLifecycleAndSoul() {
        val dir = BackupMemoryTree.deviceRoot(tmp.newFolder("from"))
        seed(dir)
        File(dir, "SOUL.md").writeText("user persona")
        File(dir, ".memory-stale.minis-tmp").writeText("unfinished")
        val pkg = File(tmp.newFolder("pkg"), "data/memory")
        assertEquals(3, BackupMemoryTree.export(dir, pkg).entries)
        val to = BackupMemoryTree.deviceRoot(tmp.newFolder("to"))
        assertEquals(3, BackupMemoryTree.restore(pkg, to).filesWritten)
        assertEquals("user persona", File(to, "SOUL.md").readText())
        assertFalse(File(to, ".memory-stale.minis-tmp").exists())
        checkRestored(to)
    }

    @Test fun bothZipTreesPreserveFileTtlAgeHeadersAndPerPathMtimes() {
        for (asChats in listOf(true, false)) {
            val fromRoot = tmp.newFolder("mtime-from-$asChats")
            val toRoot = tmp.newFolder("mtime-to-$asChats")
            val from = if (asChats) File(fromRoot, "minis-sessions/sid/memory") else BackupMemoryTree.deviceRoot(fromRoot)
            val to = if (asChats) File(toRoot, "minis-sessions/sid/memory") else BackupMemoryTree.deviceRoot(toRoot)
            assertTrue(from.mkdirs())
            val header = "\uFEFF---\r\nttl_days: 1\r\nunknown: kept\r\n---\r\n"
            val diary = File(from, source).apply { writeText(header + "legacy pear\r\n") }
            val sidecar = seed(from)
            val original = diary.readBytes()
            assertTrue(diary.setLastModified(start - 3 * MemoryLifecycle.DAY_MS + 313))
            assertTrue(sidecar.setLastModified(start + 517))
            val first = File(from, "past.md").apply {
                writeText(header + "matching plum\r\n")
                assertTrue(setLastModified(diary.lastModified()))
            }
            val second = File(from, "present.md").apply {
                writeBytes(first.readBytes())
                assertTrue(setLastModified(start + 731))
            }
            val packageDir = if (asChats) exportChats(from, "mtime-package-$asChats").first
                else tmp.newFolder("mtime-package-$asChats").also {
                    assertEquals(4, BackupMemoryTree.export(from, File(it, "data/memory")).entries)
                }
            val archive = File(tmp.root, "mtime-$asChats.minisbak")
            BackupZip.archive(packageDir, archive)
            val extracted = tmp.newFolder("mtime-extracted-$asChats")
            BackupZip.extract(archive, extracted)
            if (asChats) {
                val index = File(extracted, "files.index.jsonl").readLines().map {
                    BackupFormat.json.decodeFromString(BackupFileIndexEntry.serializer(), it)
                }
                assertEquals(index.single { it.path.endsWith("/past.md") }.sha256,
                    index.single { it.path.endsWith("/present.md") }.sha256)
                assertEquals(4, BackupRestoreFiles.restoreSessionTrees(extracted, index, sessionsRoot(to)).written)
            } else {
                assertEquals(4, BackupMemoryTree.restore(File(extracted, "data/memory"), to).filesWritten)
            }
            val restored = File(to, source)
            assertArrayEquals(original, restored.readBytes())
            assertTrue(restored.readText().startsWith(header))
            assertEquals(diary.lastModified(), restored.lastModified())
            assertEquals(sidecar.lastModified(), MemoryLifecycle.sidecar(to.canonicalFile, source)!!.lastModified())
            assertEquals(first.lastModified(), File(to, first.name).lastModified())
            assertEquals(second.lastModified(), File(to, second.name).lastModified())
            val reader = MemoryRepository(to) { start + MemoryLifecycle.DAY_MS }
            assertTrue(reader.restoreArchivedMemory(reader.archivedEntries("time bound", 1).single().token))
            assertFalse(reader.getMemory("time bound", "daily").contains("time bound note"))
            assertFalse(reader.getMemory("legacy pear", "daily").contains("legacy pear"))
            val active = reader.getMemory("matching plum", "daily")
            assertFalse(active.contains("past.md"))
            assertTrue(active.contains("present.md"))
        }
    }

    @Test fun legacyGlobalZipWithoutAnInventoryKeepsTheOriginalMarkdownAndItsZipTime() {
        val staging = tmp.newFolder("old-format")
        val tree = File(staging, "data/memory").apply { mkdirs() }
        val body = File(tree, source).apply {
            writeText("\uFEFF---\r\nttl_days: 1\r\n---\r\nlegacy only\r\n")
            assertTrue(setLastModified(start - 2 * MemoryLifecycle.DAY_MS))
        }
        val archive = File(tmp.root, "old-format.minisbak")
        BackupZip.archive(staging, archive)
        val extracted = tmp.newFolder("old-extracted")
        BackupZip.extract(archive, extracted)
        val to = BackupMemoryTree.deviceRoot(tmp.newFolder("old-destination"))
        assertEquals(1, BackupMemoryTree.restore(File(extracted, "data/memory"), to).filesWritten)
        val restored = File(to, source)
        assertArrayEquals(body.readBytes(), restored.readBytes())
        assertEquals(body.lastModified(), restored.lastModified()) // Whole-second ZIP timestamp, no inventory needed.
        assertFalse(MemoryRepository(to) { start }.getMemory("legacy only", "daily").contains("legacy only"))
    }

    @Test fun cappedSessionPairNeverPublishesEitherMember() {
        for (which in listOf("diary", "metadata")) {
            val from = File(tmp.newFolder("from-$which"), "minis-sessions/sid/memory")
            val rollover = seed(from, "time bound incoming")
            val diary = File(from, source)
            if (which == "diary") diary.appendText("user addendum\n".repeat(80))
            val cap = minOf(diary.length(), rollover.length())
            assertNotEquals(diary.length(), rollover.length())
            val (pkg, index, result) = exportChats(from, "pkg-$which", cap)
            assertEquals(0, result.filesIncluded)
            assertEquals(2, result.filesSkipped)
            assertTrue(index.all { it.skipped == "size" && it.sha256 == null })
            assertFalse(File(pkg, "blobs").exists())
            val to = File(tmp.newFolder("to-$which"), "minis-sessions/sid/memory")
            seed(to)
            val before = bytesOf(to)
            val restored = BackupRestoreFiles.restoreSessionTrees(pkg, index, sessionsRoot(to))
            assertEquals(0, restored.written)
            assertEquals(2, restored.sizeSkippedInPackage)
            assertUnchanged(to, before)
        }
    }

    @Test fun malformedSessionPairsNeverChangeTheDestination() {
        val from = File(tmp.newFolder("from"), "minis-sessions/sid/memory")
        seed(from, "time bound incoming")
        val (pkg, index) = exportChats(from, "pkg")
        val to = File(tmp.newFolder("to"), "minis-sessions/sid/memory")
        seed(to)
        val before = bytesOf(to)
        val root = sessionsRoot(to)
        for (alone in index) {
            assertEquals(0, BackupRestoreFiles.restoreSessionTrees(pkg, listOf(alone), root).written)
            assertUnchanged(to, before)
        }
        for (entry in index) {
            val sha = checkNotNull(entry.sha256)
            val blob = File(pkg, "blobs/${sha.take(2)}/$sha")
            val original = blob.readBytes()
            assertTrue(blob.delete())
            val missing = BackupRestoreFiles.restoreSessionTrees(pkg, index, root)
            assertEquals(0, missing.written)
            assertEquals(1, missing.missingBlobs)
            assertUnchanged(to, before)
            blob.writeBytes(original.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() })
            val damaged = BackupRestoreFiles.restoreSessionTrees(pkg, index, root)
            assertEquals(0, damaged.written)
            assertTrue(damaged.rejectedPaths > 0)
            assertUnchanged(to, before)
            blob.writeBytes(original)
        }
    }

    @Test fun globalLimitAndBrokenMembersKeepTheOldPairAndDoNotBlockSoul() {
        val from = BackupMemoryTree.deviceRoot(tmp.newFolder("from"))
        val sidecar = seed(from, "time bound incoming")
        val body = File(from, source)
        File(from, "SOUL.md").writeText("keep this persona portable")
        val to = BackupMemoryTree.deviceRoot(tmp.newFolder("to"))
        seed(to)
        val before = bytesOf(to)
        for (which in listOf("metadata", "diary")) {
            if (which == "diary") body.appendText("user addendum\n".repeat(80))
            val capped = tmp.newFolder("capped-$which")
            val skipped = mutableListOf<String>()
            assertEquals(1, BackupMemoryTree.export(from, capped, minOf(body.length(), sidecar.length())) { path, _ -> skipped += path }.entries)
            assertEquals(2, skipped.size)
            assertEquals(1, BackupMemoryTree.restore(capped, to).filesWritten)
            assertUnchanged(to, before)
        }
        val full = tmp.newFolder("full")
        BackupMemoryTree.export(from, full)
        val fullMeta = File(full, ".memory-lifecycle/$source.json")
        for (member in listOf(File(full, source), fullMeta)) {
            val original = member.readBytes()
            assertTrue(member.delete())
            val missing = BackupMemoryTree.restore(full, to)
            assertEquals(1, missing.filesWritten)
            assertTrue(missing.rejectedPaths > 0)
            assertUnchanged(to, before)
            member.writeBytes(original.copyOf().also { it[it.lastIndex - 2] = (it[it.lastIndex - 2].toInt() xor 1).toByte() })
            val damaged = BackupMemoryTree.restore(full, to)
            assertEquals(1, damaged.filesWritten)
            assertTrue(damaged.rejectedPaths > 0)
            assertUnchanged(to, before)
            member.writeBytes(original)
        }
        assertEquals("keep this persona portable", File(to, "SOUL.md").readText())
    }

    @Test fun interruptedRestorePublishesOnlyACompletePairAcrossBothTrees() {
        for ((pairedChats, cutoff) in listOf(true to 1, true to 2, false to 1, false to 2)) {
            val kind = "$pairedChats-$cutoff"
            val from = if (pairedChats) File(tmp.newFolder("from-$kind"), "minis-sessions/sid/memory")
                else BackupMemoryTree.deviceRoot(tmp.newFolder("from-$kind"))
            val toRoot = tmp.newFolder("to-$kind")
            val to = if (pairedChats) File(toRoot, "minis-sessions/sid/memory") else BackupMemoryTree.deviceRoot(toRoot)
            seed(from, "time bound incoming")
            seed(to)
            val previousTime = start - 7 * MemoryLifecycle.DAY_MS + 777
            val incomingTime = start - 5 * MemoryLifecycle.DAY_MS + 313
            assertTrue(File(to, source).setLastModified(previousTime))
            assertTrue(File(from, source).setLastModified(incomingTime))
            val before = bytesOf(to)
            val incoming = bytesOf(from)
            val restore: () -> Int = if (pairedChats) {
                val (pkg, index) = exportChats(from, "pkg-$kind")
                val run: () -> Int = { BackupRestoreFiles.restoreSessionTrees(pkg, index, sessionsRoot(to)).written }
                run
            } else {
                val pkg = tmp.newFolder("pkg-$kind")
                BackupMemoryTree.export(from, pkg)
                val run: () -> Int = { BackupMemoryTree.restore(pkg, to).filesWritten }
                run
            }
            val crash = object : Error("simulated process loss") {}
            try {
                MemoryRestoreJournal.beforeCommitForTest = { if (it == cutoff) throw crash }
                try {
                    restore()
                    fail("Expected an interrupted publication")
                } catch (e: Error) {
                    assertSame(crash, e)
                }
            } finally {
                MemoryRestoreJournal.beforeCommitForTest = null
            }
            assertArrayEquals(incoming.first, File(to, source).readBytes()) // The crash did interrupt raw publication.
            assertEquals(incomingTime, File(to, source).lastModified())
            MemoryLifecycle.locked(to) { dir ->
                assertEquals(previousTime, MemoryLifecycle.snapshotFiles(dir, source).diary!!.lastModified())
            }
            val now = start + MemoryLifecycle.DAY_MS
            val reopened = MemoryRepository(to) { now }
            assertArrayEquals(before.first, reopened.readFile(source).toByteArray())
            assertEquals("time bound note", reopened.archivedEntries("", 10).single().body)
            assertTrue(reopened.loadRecentDailyFragments("time bound").fragments.isEmpty())
            assertArrayEquals(incoming.first, File(to, source).readBytes()) // Preview never recovers or mutates.
            if (cutoff == 2) {
                MemoryRestoreJournal.recoverInterrupted(toRoot)
                assertUnchanged(to, before) // Startup restores raw bytes for external file readers too.
                assertEquals(previousTime, File(to, source).lastModified())
            }
            assertEquals(2, restore()) // Retrying directly also recovers a pending pair before committing the new pair.
            assertEquals(incomingTime, File(to, source).lastModified())
            assertEquals("time bound incoming", reopened.archivedEntries("", 10).single().body)
            assertArrayEquals(incoming.second, bytesOf(to).second)
            assertTrue(MemoryLifecycle.metadataDirectory(to).listFiles().orEmpty().none { it.name.endsWith(".pending") })
        }
    }

    @Test fun corruptSourceMetadataCannotLeaveAnOrphanInEitherPackage() {
        val from = File(tmp.newFolder("bad-chats"), "minis-sessions/sid/memory")
        seed(from).writeText("broken sidecar")
        val (chatPkg, chatIndex, result) = exportChats(from, "bad-chats-pkg")
        assertEquals(0, result.filesIncluded)
        assertEquals(2, result.filesSkipped)
        assertTrue(chatIndex.all { it.skipped == "unreadable" && it.sha256 == null })
        assertFalse(File(chatPkg, "blobs").exists())

        val global = BackupMemoryTree.deviceRoot(tmp.newFolder("bad-global"))
        seed(global).writeText("broken sidecar")
        File(global, "SOUL.md").writeText("portable persona")
        val globalPkg = tmp.newFolder("bad-global-pkg")
        val skipped = mutableListOf<String>()
        assertEquals(1, BackupMemoryTree.export(global, globalPkg) { path, _ -> skipped += path }.entries)
        assertEquals(2, skipped.size)
        assertFalse(File(globalPkg, source).exists())
        assertFalse(File(globalPkg, ".memory-lifecycle/$source.json").exists())
        assertEquals("portable persona", File(globalPkg, "SOUL.md").readText())
    }

    /**
     * [P3-6] retire() renames the journal to retired-* and then deletes it; a
     * kill between the two leaves the renamed directory behind. Startup
     * recovery must sweep that garbage — its content is no longer
     * authoritative either way — and leave no *.pending / retired-* residue.
     */
    @Test fun recoverInterruptedCleansAJournalRetiredBeforeItsDelete() {
        val toRoot = tmp.newFolder("to-retired")
        val to = BackupMemoryTree.deviceRoot(toRoot)
        seed(to)
        val from = BackupMemoryTree.deviceRoot(tmp.newFolder("from-retired"))
        seed(from, "time bound incoming")
        val pkg = tmp.newFolder("pkg-retired")
        BackupMemoryTree.export(from, pkg)
        val crash = object : Error("simulated process loss") {}
        try {
            MemoryRestoreJournal.beforeCommitForTest = { if (it == 2) throw crash }
            try {
                BackupMemoryTree.restore(pkg, to)
                fail("Expected an interrupted publication")
            } catch (e: Error) {
                assertSame(crash, e)
            }
        } finally {
            MemoryRestoreJournal.beforeCommitForTest = null
        }
        val metadata = MemoryLifecycle.metadataDirectory(to)
        val pending = checkNotNull(metadata.listFiles()?.singleOrNull { it.name.endsWith(".pending") })
        val retired = File(metadata, "${MemoryRestoreJournal.DIRECTORY_PREFIX}${MemoryRestoreJournal.RETIRED_PREFIX}leftover")
        assertTrue(pending.renameTo(retired))
        MemoryRestoreJournal.recoverInterrupted(toRoot)
        assertFalse("the leftover retired journal must be swept", retired.exists())
        assertTrue(
            metadata.listFiles().orEmpty().none {
                it.name.endsWith(".pending") || it.name.contains(MemoryRestoreJournal.RETIRED_PREFIX)
            },
        )
        assertTrue(
            "the published pair stays visible after the sweep",
            MemoryRepository(to) { start + MemoryLifecycle.DAY_MS }.readFile(source).contains("time bound incoming"),
        )
    }

    private fun sessionsRoot(directory: File): File = checkNotNull(checkNotNull(directory.parentFile).parentFile)

    private fun exportChats(from: File, name: String, cap: Long? = null): Triple<File, List<BackupFileIndexEntry>, BackupFileTreeExporter.Result> {
        val pkg = tmp.newFolder(name)
        val indexFile = File(pkg, "files.index.jsonl")
        val blobs = BackupBlobStore(pkg, cap)
        val result = BackupFileIndexWriter(indexFile).use {
            BackupFileTreeExporter(blobs, it).export(checkNotNull(from.parentFile), "chats/sid", BackupCategory.CHATS, "sid")
        }
        val index = indexFile.readLines().map { BackupFormat.json.decodeFromString(BackupFileIndexEntry.serializer(), it) }
        return Triple(pkg, index, result)
    }

    private fun bytesOf(dir: File): Pair<ByteArray, ByteArray> =
        File(dir, source).readBytes() to checkNotNull(MemoryLifecycle.sidecar(dir.canonicalFile, source)).readBytes()

    private fun assertUnchanged(dir: File, before: Pair<ByteArray, ByteArray>) {
        val after = bytesOf(dir)
        assertArrayEquals(before.first, after.first)
        assertArrayEquals(before.second, after.second)
        val repo = MemoryRepository(dir) { start + MemoryLifecycle.DAY_MS }
        assertEquals("time bound note", repo.archivedEntries("", 10).single().body)
    }

    private fun seed(dir: File, body: String = "time bound note"): File {
        val repo = MemoryRepository(dir) { start }
        assertTrue(repo.writeHostMemory(body, origin = MemoryEntryOrigin.MEMORY_WRITE, ttlDays = 1, autoArchive = true).startsWith("Memory saved"))
        val entry = MemoryLifecycle.read(dir, source)!!.managed().single()
        MemoryLifecycle.recordDelivery(setOf(entry.ref), "old-user", start + 1)
        return MemoryLifecycle.sidecar(dir.canonicalFile, source)!!
    }

    private fun checkRestored(dir: File) {
        val now = start + MemoryLifecycle.UNUSED_MS + 1
        val repo = MemoryRepository(dir) { now }
        val state = MemoryLifecycle.read(dir, source)!!.managed().single().state
        assertEquals(1, state.usage)
        assertEquals(start + 1, state.lastUsedAt)
        assertEquals(MemoryLifecycle.Status.ARCHIVED, state.status(now))
        val raw = File(dir, source).readBytes()
        assertTrue(repo.restoreArchivedMemory(repo.archivedEntries("time", 1).single().token))
        assertTrue(repo.getMemory("time", "daily").contains("time bound note"))
        assertArrayEquals(raw, File(dir, source).readBytes())
    }
}
