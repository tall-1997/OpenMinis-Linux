package com.openminis.app.backup

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [T-backup-undo-store] The undo point lifecycle against a real temp directory.
 *
 * Restore is the one destructive operation in the backup feature, and these
 * cases fix the three properties that make an undo trustworthy:
 *  1. a captured root comes back with its old bytes,
 *  2. a root that was empty at capture time is DELETED (records the restore
 *     added are the thing a user wants gone),
 *  3. a root the budget skipped is left alone (never half-restored).
 *
 * A regression here is silent data loss, so the assertions compare content,
 * not just existence.
 */
class BackupUndoStoreTest {

    private lateinit var root: File
    private var now = 1_000L

    @Before
    fun setUp() {
        root = Files.createTempDirectory("undo-store").toFile()
        now = 1_000L
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun store() = BackupUndoStore(root) { now }

    private fun write(rel: String, body: String) {
        val f = File(root, rel)
        f.parentFile?.mkdirs()
        f.writeText(body)
    }

    private fun read(rel: String): String? = File(root, rel).takeIf { it.isFile }?.readText()

    // -- capture ---------------------------------------------------------

    @Test
    fun `capture copies only the selected roots`() {
        write("databases/minis.db", "db")
        write("minis-global/skills/s.md", "skill")

        val result = store().capture(setOf(BackupCategory.SKILLS))

        assertNull(result.error)
        val point = assertNotNull(result.point).let { result.point!! }
        assertTrue(File(root, "backup-undo/${point.id}/tree/minis-global/skills/s.md").isFile)
        assertFalse(File(root, "backup-undo/${point.id}/tree/databases").exists())
        assertEquals(listOf("minis-global/skills"), point.paths)
    }

    @Test
    fun `capture records roots that did not exist yet`() {
        val point = store().capture(setOf(BackupCategory.MEMORY)).point!!
        assertEquals(listOf("minis-global/memory"), point.paths)
        assertFalse(File(root, "backup-undo/${point.id}/tree/minis-global/memory").exists())
    }

    @Test
    fun `list reads back what capture wrote`() {
        write("shared_prefs/providers.xml", "<map/>")
        val point = store().capture(setOf(BackupCategory.PROVIDERS)).point!!

        val listed = store().list().single()

        assertEquals(point.id, listed.id)
        assertEquals(point.createdAtMs, listed.createdAtMs)
        assertEquals(setOf(BackupCategory.PROVIDERS), listed.categories)
        assertEquals(point.bytes, listed.bytes)
        assertFalse(listed.partial)
    }

    @Test
    fun `oversized root is skipped whole and marks the point partial`() {
        write("databases/minis.db", "x".repeat(4_000))
        write("bodies/b1", "y".repeat(4_000))

        val point = store().capture(setOf(BackupCategory.CHATS), maxBytes = 1_000L).point!!

        assertTrue(point.partial)
        assertEquals(listOf("databases", "bodies"), point.skipped)
        assertFalse(File(root, "backup-undo/${point.id}/tree/databases").exists())
        assertEquals(0L, point.bytes)
    }

    @Test
    fun `a root that fits is captured even when a later root does not`() {
        write("databases/minis.db", "small")
        write("bodies/b1", "y".repeat(4_000))

        val point = store().capture(setOf(BackupCategory.CHATS), maxBytes = 1_000L).point!!

        assertTrue(point.paths.contains("databases"))
        assertEquals(listOf("bodies"), point.skipped)
        assertTrue(File(root, "backup-undo/${point.id}/tree/databases/minis.db").isFile)
    }

    // -- restore ---------------------------------------------------------

    @Test
    fun `restore brings overwritten files back and removes added ones`() {
        write("minis-global/skills/a.md", "old")
        val point = store().capture(setOf(BackupCategory.SKILLS)).point!!

        // The restore: a.md overwritten, b.md added.
        write("minis-global/skills/a.md", "new")
        write("minis-global/skills/b.md", "added")

        val result = store().restore(point)

        assertTrue(result.ok)
        assertEquals("old", read("minis-global/skills/a.md"))
        assertNull(read("minis-global/skills/b.md"))
    }

    @Test
    fun `restore deletes a root that held nothing at capture time`() {
        val point = store().capture(setOf(BackupCategory.MEMORY)).point!!

        // A package restored memories into a root that did not exist before.
        write("minis-global/memory/2026-11-03.md", "restored")

        assertTrue(store().restore(point).ok)
        assertFalse(File(root, "minis-global/memory").exists())
    }

    @Test
    fun `restore never touches a skipped root but still restores the captured one`() {
        write("databases/minis.db", "x".repeat(4_000))   // over budget -> skipped
        write("bodies/b1", "keep-me")                    // fits -> captured
        val point = store().capture(setOf(BackupCategory.CHATS), maxBytes = 1_000L).point!!
        // A skipped root never enters `paths`: restore walks `paths`, so listing
        // it without a snapshot would DELETE it instead of leaving it alone.
        assertEquals(listOf("databases"), point.skipped)
        assertFalse("a skipped root must not appear in paths", point.paths.contains("databases"))
        assertTrue(point.paths.contains("bodies"))

        write("databases/minis.db", "restored-db")
        write("bodies/b1", "changed-by-restore")

        assertTrue(store().restore(point).ok)
        // Half a snapshot must never be applied to the skipped root...
        assertEquals("restored-db", read("databases/minis.db"))
        // ...while the captured root still rolls back.
        assertEquals("keep-me", read("bodies/b1"))
    }

    @Test
    fun `restore reports a missing point instead of pretending`() {
        val point = BackupUndoStore.UndoPoint(
            id = "undo-gone",
            createdAtMs = 1L,
            categories = setOf(BackupCategory.SKILLS),
            paths = listOf("minis-global/skills"),
            skipped = emptyList(),
            bytes = 0L,
            partial = false,
        )
        val result = store().restore(point)
        assertFalse(result.ok)
        assertNotNull(result.error)
    }

    // -- prune / delete --------------------------------------------------

    @Test
    fun `prune keeps the newest points only`() {
        write("shared_prefs/a.xml", "1")
        val s = store()
        repeat(4) {
            now += 1_000
            s.capture(setOf(BackupCategory.PROVIDERS))
        }
        assertEquals(4, s.list().size)

        val removed = s.prune(keep = 3, maxAgeMs = Long.MAX_VALUE)

        assertEquals(1, removed)
        assertEquals(3, s.list().size)
        assertTrue(s.list().first().createdAtMs > s.list().last().createdAtMs)
    }

    @Test
    fun `prune drops points older than the age limit`() {
        write("shared_prefs/a.xml", "1")
        val s = store()
        now = 1_000
        s.capture(setOf(BackupCategory.PROVIDERS))
        now = 1_000 + BackupUndoStore.DEFAULT_MAX_AGE_MS + 1
        s.capture(setOf(BackupCategory.PROVIDERS))

        s.prune(keep = 10)

        assertEquals(1, s.list().size)
    }

    @Test
    fun `delete removes one point and leaves the rest`() {
        write("shared_prefs/a.xml", "1")
        val s = store()
        now = 1_000
        val first = s.capture(setOf(BackupCategory.PROVIDERS)).point!!
        now = 2_000
        val second = s.capture(setOf(BackupCategory.PROVIDERS)).point!!

        assertTrue(s.delete(first.id))

        assertEquals(listOf(second.id), s.list().map { it.id })
    }

    @Test
    fun `a directory without a manifest is dropped from the listing`() {
        File(root, "backup-undo/undo-broken/tree").mkdirs()
        write("shared_prefs/a.xml", "1")
        now = 5_000
        val real = store().capture(setOf(BackupCategory.PROVIDERS)).point!!

        assertEquals(listOf(real.id), store().list().map { it.id })
        assertFalse(File(root, "backup-undo/undo-broken").exists())
    }

    @Test
    fun `capture returns an error instead of throwing when the root cannot be created`() {
        // A file where the undo directory should be: mkdirs() fails.
        File(root, "backup-undo").writeText("not a directory")
        val result = store().capture(setOf(BackupCategory.PROVIDERS))
        assertNull(result.point)
        assertNotNull(result.error)
    }
}
