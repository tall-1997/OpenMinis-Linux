package com.openminis.app.backup

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Content-addressed storage and the §3.4 size cap.
 *
 * The cap's boundary and its tombstone behaviour are called out explicitly in
 * the design doc's test plan, because both have a silent-data-loss failure mode:
 * an off-by-one at the threshold drops a file the user expected to keep, and a
 * skipped file with no tombstone vanishes without trace.
 */
class BackupBlobStoreTest {

    private lateinit var tmp: File
    private lateinit var staging: File

    @Before
    fun setUp() {
        tmp = File.createTempFile("minisbak-blob", "").apply { delete(); mkdirs() }
        staging = File(tmp, "staging").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tmp.deleteRecursively()
    }

    private fun file(name: String, bytes: ByteArray): File =
        File(tmp, name).apply { parentFile?.mkdirs(); writeBytes(bytes) }

    @Test
    fun `identical content is stored once and reported as a duplicate`() {
        val store = BackupBlobStore(staging, maxFileBytes = null)
        val content = "shared attachment".toByteArray()
        val a = store.addFile(file("a.txt", content), "chats/s1/attachments/a.txt")
        val b = store.addFile(file("b.txt", content), "chats/s2/attachments/b.txt")

        assertTrue(a is BackupBlobStore.Outcome.Stored)
        assertTrue("the second copy must dedup, not re-store", b is BackupBlobStore.Outcome.Duplicate)
        assertEquals(
            (a as BackupBlobStore.Outcome.Stored).sha256,
            (b as BackupBlobStore.Outcome.Duplicate).sha256,
        )
        // One blob on disk, and the index carries one entry for it.
        assertEquals(1, store.blobIndex.size)
        assertEquals(content.size.toLong(), store.totalBytesStored)
    }

    @Test
    fun `blobs fan out two levels by digest prefix`() {
        val store = BackupBlobStore(staging, maxFileBytes = null)
        val outcome = store.addFile(file("x.bin", byteArrayOf(1, 2, 3)), "shared_files/x.bin")
        val sha = (outcome as BackupBlobStore.Outcome.Stored).sha256
        val expected = File(File(File(staging, "blobs"), sha.take(2)), sha)
        assertTrue("blob should live at blobs/<2 hex>/<sha256>", expected.exists())
    }

    /**
     * The design doc pins this boundary: "恰好等于阈值的文件必须纳入（边界取 `>`
     * 而非 `>=`）". A `>=` here would drop a file that is exactly at the limit.
     */
    @Test
    fun `a file exactly at the cap is included, one byte over is not`() {
        val store = BackupBlobStore(staging, maxFileBytes = 100)
        val atLimit = store.addFile(file("at.bin", ByteArray(100)), "chats/s1/at.bin")
        val overLimit = store.addFile(file("over.bin", ByteArray(101)), "chats/s1/over.bin")

        assertTrue("a file exactly at the cap must be included", atLimit is BackupBlobStore.Outcome.Stored)
        assertTrue("one byte over must be skipped", overLimit is BackupBlobStore.Outcome.SkippedTooLarge)
        assertEquals(1, store.skippedFiles)
        assertEquals(101L, store.skippedBytes)
        assertEquals("chats/s1/over.bin", store.skippedPaths.single().path)
    }

    /** Unlimited is the default, so nothing is dropped from a migration package. */
    @Test
    fun `an unlimited store keeps everything`() {
        val store = BackupBlobStore(staging, maxFileBytes = null)
        store.addFile(file("huge.bin", ByteArray(5_000_000)), "shared_files/huge.bin")
        assertEquals(0, store.skippedFiles)
        assertEquals(null, store.maxFileBytesForManifest)
    }

    /**
     * Resume: a digest whose blob is missing must NOT be re-registered, or the
     * resumed package references content it does not contain — a package that
     * passes its own integrity check and restores empty files.
     */
    @Test
    fun `rehydrate skips index entries whose blob is gone`() {
        val first = BackupBlobStore(staging, maxFileBytes = null)
        val present = first.addFile(file("p.bin", byteArrayOf(9, 9, 9)), "chats/s1/p.bin")
        val presentSha = (present as BackupBlobStore.Outcome.Stored).sha256

        // A files.index from an interrupted run: one entry whose blob landed,
        // one whose blob never did.
        val index = File(staging, "files.index.jsonl")
        index.writeText(
            listOf(
                BackupFileIndexEntry.file("chats/s1/p.bin", 3, presentSha, BackupCategory.CHATS),
                BackupFileIndexEntry.file(
                    "chats/s1/lost.bin", 42,
                    "0000000000000000000000000000000000000000000000000000000000000000",
                    BackupCategory.CHATS,
                ),
            ).joinToString("\n") {
                BackupFormat.json.encodeToString(BackupFileIndexEntry.serializer(), it)
            } + "\n"
        )

        val resumed = BackupBlobStore(staging, maxFileBytes = null)
        resumed.rehydrateFromFileIndex(index)
        assertEquals("only the blob actually on disk may be re-registered", 1, resumed.blobIndex.size)
        assertEquals(presentSha, resumed.blobIndex.single().sha256)

        // And the missing one must be re-stored rather than skipped as "seen".
        val again = resumed.addFile(file("lost.bin", ByteArray(42)), "chats/s1/lost.bin")
        assertTrue("a blob missing from disk must be stored again", again is BackupBlobStore.Outcome.Stored)
    }

    @Test
    fun `tombstones and directories are ignored when rehydrating`() {
        val index = File(staging, "files.index.jsonl")
        index.writeText(
            listOf(
                BackupFormat.json.encodeToString(
                    BackupFileIndexEntry.serializer(),
                    BackupFileIndexEntry.sizeSkipped("chats/s1/big.zip", 999, BackupCategory.CHATS),
                ),
                BackupFormat.json.encodeToString(
                    BackupFileIndexEntry.serializer(),
                    BackupFileIndexEntry.directory("chats/s1/empty", BackupCategory.CHATS),
                ),
                "{ not json at all",
            ).joinToString("\n") + "\n"
        )
        val store = BackupBlobStore(staging, maxFileBytes = null)
        store.rehydrateFromFileIndex(index)
        assertEquals(0, store.blobIndex.size)
    }

    @Test
    fun `sha256 matches a known vector`() {
        // Streaming digest must equal the standard SHA-256 of "abc".
        val f = file("abc.txt", "abc".toByteArray())
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            BackupBlobStore.sha256OfFile(f),
        )
    }

    /**
     * [P3-5] A resumed run can carry blobs its package index already
     * references (a killed run wrote the blob but never its index line, so
     * rehydration missed it). A failed diary-pair move must roll back only
     * bytes THIS run created — deleting a destination that existed before the
     * move would strip content the resumed package points at.
     */
    @Test
    fun `diary pair rollback keeps blobs the resumed package already references`() {
        val resumedContent = "resumed package payload".toByteArray()
        val resumedSha = com.openminis.app.util.Digest.sha256Hex(resumedContent)
        val preExisting = File(File(File(staging, "blobs"), resumedSha.take(2)), resumedSha)
        preExisting.parentFile?.mkdirs()
        preExisting.writeBytes(resumedContent)

        val store = BackupBlobStore(staging, maxFileBytes = null)
        val freshBytes = "fresh member bytes".toByteArray()
        val freshSha = com.openminis.app.util.Digest.sha256Hex(freshBytes)
        val brokenBytes = "third member bytes".toByteArray()
        val freshMember = file("fresh.md", freshBytes)
        val resumedMember = file("diary.md", resumedContent)
        val brokenMember = file("broken.json", brokenBytes)
        try {
            // [P3-5] The failure must land AFTER a destination this run created
            // has already moved, otherwise the rollback branch never executes and
            // the assertion below is vacuous. Member order is fresh → resumed →
            // broken; deleting the third member's SOURCE makes its move fail with
            // the first two already on disk.
            store.beforeMoveForTest = { if (it == 2) brokenMember.delete() }
            val failure = runCatching {
                store.addDiaryPair(
                    listOf(
                        freshMember to "data/memory/fresh.md",
                        resumedMember to "data/memory/diary.md",
                        brokenMember to "data/memory/broken.json",
                    ),
                    sessionId = "s1",
                )
            }.exceptionOrNull()
            assertNotNull("the third member must fail after the first two moved", failure)
        } finally {
            store.beforeMoveForTest = null
        }

        assertTrue(
            "a destination that existed before the move must survive the rollback",
            preExisting.exists() && preExisting.readBytes().contentEquals(resumedContent),
        )
        assertFalse(
            "this run's fresh bytes must still be rolled back",
            File(File(File(staging, "blobs"), freshSha.take(2)), freshSha).exists(),
        )
        // Nothing was indexed: the batch failed as a unit.
        assertTrue(store.blobIndex.isEmpty())
    }

    /**
     * [T-backup-decode-honesty] An unreadable credential store is recorded as a
     * skip through the SAME accounting as a size-cap skip, so the run lands as
     * "completed with issues" naming the omission instead of packaging as a
     * clean success that silently left every provider signed out — but it
     * keeps its own reason, so the report blames the unreadable store and not
     * the size cap.
     */
    @Test
    fun `recordSkip counts an external omission like a size-cap skip`() {
        val store = BackupBlobStore(staging, maxFileBytes = null)
        assertEquals(0, store.skippedFiles)

        store.recordSkip(
            "secrets/oauth_prefs (oauth token store unreadable)",
            reason = BackupBlobStore.SkipReason.CREDENTIALS,
        )
        assertEquals(1, store.skippedFiles)
        assertEquals(0L, store.skippedBytes)
        assertEquals("secrets/oauth_prefs (oauth token store unreadable)", store.skippedPaths.single().path)
        assertEquals(
            BackupBlobStore.SkipReason.CREDENTIALS,
            store.skippedPaths.single().reason,
        )

        // A skip is what makes the summary honest: skippedFiles > 0 is exactly
        // the condition the history record maps to COMPLETED_WITH_ISSUES.
        store.recordSkip("secrets/another", size = 128, reason = BackupBlobStore.SkipReason.CREDENTIALS)
        assertEquals(2, store.skippedFiles)
        assertEquals(128L, store.skippedBytes)
        assertEquals(2, store.skippedPaths.size)
    }

    /**
     * One counter, several causes: the reason carried per entry is what keeps
     * the completion screen honest. A cap of 0 means "do not back up files"
     * (nothing is "too large" then), while a real cap over is SIZE_CAP — and
     * an unreadable source is neither.
     */
    @Test
    fun `skip entries carry the reason that actually happened`() {
        val capped = BackupBlobStore(staging, maxFileBytes = 100)
        capped.addFile(file("over.bin", ByteArray(101)), "chats/s1/over.bin")
        assertEquals(BackupBlobStore.SkipReason.SIZE_CAP, capped.skippedPaths.single().reason)
        assertEquals("size_cap", capped.skippedPaths.single().reason.wireName)

        val noFiles = BackupBlobStore(staging, maxFileBytes = 0)
        noFiles.addFile(file("any.bin", byteArrayOf(1)), "chats/s1/any.bin")
        assertEquals(BackupBlobStore.SkipReason.NO_FILES, noFiles.skippedPaths.single().reason)
        assertEquals("no_files", noFiles.skippedPaths.single().reason.wireName)

        val unreadable = BackupBlobStore(staging, maxFileBytes = null)
        unreadable.recordSkip("chats/s1/broken-diary.md", 10, BackupBlobStore.SkipReason.UNREADABLE)
        assertEquals("unreadable", unreadable.skippedPaths.single().reason.wireName)
    }
}
