package com.openminis.app.tools

import com.openminis.app.sandbox.SessionWorkspace
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Snapshot/restore semantics, exercised without Android: the store takes a
 * filesDir plus a [FileCheckpointStore.Resolver], so the "did it actually put
 * the old bytes back" question is answerable on a plain JVM.
 */
class FileCheckpointStoreTest {

    private val filesDir: File = File(System.getProperty("java.io.tmpdir"), "minis-cp-test-${System.nanoTime()}")
    private val session = "sess-1"
    private val target = File(filesDir, "workspace/a.txt")

    /** guest path -> host file, the same shape PRootKernel's resolver has. */
    private fun resolver(vararg pairs: Pair<String, File>): FileCheckpointStore.Resolver {
        val map = pairs.toMap()
        return FileCheckpointStore.Resolver { map[it] }
    }

    private fun writeTarget(s: String) {
        target.parentFile.mkdirs()
        target.writeText(s)
    }

    @After
    fun clean() {
        filesDir.deleteRecursively()
    }

    @Test
    fun captureRestoreReturnsOriginalBytes() {
        writeTarget("original")
        val out = FileCheckpointStore.capture(filesDir, session, listOf("/a.txt"), resolver("/a.txt" to target), label = "t")
        assertNotNull(out.reason, out.checkpoint)
        assertEquals(1, out.checkpoint!!.entries.size)

        writeTarget("clobbered by a bad rewrite")
        val r = FileCheckpointStore.restore(filesDir, session, out.checkpoint!!.id, resolver("/a.txt" to target))
        assertTrue(r.lines.toString(), r.success)
        assertEquals("original", target.readText())
    }

    @Test
    fun restoreDeletesFileThatDidNotExistBeforeCheckpoint() {
        val fresh = File(filesDir, "workspace/new.txt")
        // The resolver reports the path (the agent is about to create it) while
        // nothing is on disk yet, so the snapshot records existed=false.
        val out = FileCheckpointStore.capture(filesDir, session, listOf("/new.txt"), resolver("/new.txt" to fresh))
        assertNotNull(out.reason, out.checkpoint)
        assertFalse("nothing on disk yet", out.checkpoint!!.entries.single().existed)

        fresh.parentFile.mkdirs()
        fresh.writeText("created by the run")
        val r = FileCheckpointStore.restore(filesDir, session, out.checkpoint!!.id, resolver("/new.txt" to fresh))
        assertTrue(r.lines.toString(), r.success)
        assertFalse("undo must also remove files the run created", fresh.exists())
    }

    @Test
    fun restoreWithoutIdUsesNewest() {
        writeTarget("old")
        FileCheckpointStore.capture(filesDir, session, listOf("/a.txt"), resolver("/a.txt" to target), label = "first")
        Thread.sleep(1100) // dir mtime is the sort key; 1s granularity on some FS
        writeTarget("new")
        FileCheckpointStore.capture(filesDir, session, listOf("/a.txt"), resolver("/a.txt" to target), label = "second")
        writeTarget("broken")

        val r = FileCheckpointStore.restore(filesDir, session, null, resolver("/a.txt" to target))
        assertEquals("second", r.checkpoint?.label)
        assertEquals("new", target.readText())
    }

    @Test
    fun unresolvableOversizedAndDuplicatePathsAreSkippedNotFatal() {
        writeTarget("keep")
        val big = File(filesDir, "workspace/big.bin")
        big.parentFile.mkdirs()
        big.writeBytes(ByteArray((FileCheckpointStore.MAX_FILE_BYTES + 1).toInt()))

        val out = FileCheckpointStore.capture(
            filesDir, session,
            listOf("/a.txt", "/nope.txt", "/big.bin", "/a.txt"),
            resolver("/a.txt" to target, "/big.bin" to big),
        )
        assertNotNull(out.reason, out.checkpoint)
        assertEquals("duplicates collapse to one snapshot", 1, out.checkpoint!!.entries.size)
        assertEquals(2, out.skipped.size)
        assertTrue(out.skipped.toString(), out.skipped.any { it.contains("unresolvable") })
        assertTrue(out.skipped.toString(), out.skipped.any { it.contains("bytes") })
        assertEquals("keep", target.readText())
    }

    @Test
    fun directoriesAreNotRecursivelyCopied() {
        val dir = File(filesDir, "workspace/tree")
        dir.mkdirs()
        File(dir, "x.txt").writeText("x")
        val out = FileCheckpointStore.capture(filesDir, session, listOf("/tree"), resolver("/tree" to dir))
        assertNull(out.reason, out.checkpoint)
        assertTrue(out.skipped.single(), out.skipped.single().contains("directory"))
        assertTrue("dir tree untouched", File(dir, "x.txt").exists())
    }

    @Test
    fun successfulRestoreDropsTheCheckpoint() {
        writeTarget("v1")
        val cp = FileCheckpointStore.capture(filesDir, session, listOf("/a.txt"), resolver("/a.txt" to target)).checkpoint!!
        assertTrue(FileCheckpointStore.restore(filesDir, session, cp.id, resolver("/a.txt" to target)).success)
        // Restoring twice must not resurrect a stale snapshot.
        val again = FileCheckpointStore.restore(filesDir, session, cp.id, resolver("/a.txt" to target))
        assertFalse(again.found)
        assertEquals("v1", target.readText())
    }

    @Test
    fun failedRestoreKeepsTheCheckpointForRetry() {
        writeTarget("v1")
        val cp = FileCheckpointStore.capture(filesDir, session, listOf("/a.txt"), resolver("/a.txt" to target)).checkpoint!!
        // A resolver that can no longer see the file: restore must report the
        // failure and keep the payload instead of consuming the checkpoint.
        val r = FileCheckpointStore.restore(filesDir, session, cp.id, FileCheckpointStore.Resolver { null })
        assertFalse(r.success)
        assertTrue(r.lines.toString(), r.lines.any { it.contains("no longer resolves") })
        assertTrue(FileCheckpointStore.restore(filesDir, session, cp.id, resolver("/a.txt" to target)).success)
        assertEquals("v1", target.readText())
    }

    @Test
    fun unknownIdIsReportedNotThrown() {
        val r = FileCheckpointStore.restore(filesDir, session, "nope", resolver("/a.txt" to target))
        assertFalse(r.found)
        assertFalse(r.success)
        assertTrue(r.lines.single(), r.lines.single().contains("nope"))
    }

    @Test
    fun snapshotsLandInTheSessionBaseNotTheGuestWorkspace() {
        writeTarget("v1")
        FileCheckpointStore.capture(filesDir, session, listOf("/a.txt"), resolver("/a.txt" to target))
        val base = File(SessionWorkspace.base(filesDir, session), ".checkpoints")
        assertTrue("snapshot must sit under the session base dir", base.isDirectory)
        assertFalse("and never in the shared workspace tree", File(filesDir, "workspace/.checkpoints").exists())
    }

    @Test
    fun dropRejectsTraversalIdsInsteadOfDeletingTheWorkspace() {
        // [T-checkpoint-id-guard] ".." resolves to the session base itself and
        // "../.." to the session collection; deleteRecursively() on either
        // would eat live session data, so the ids must be refused flat out.
        writeTarget("precious")
        val cp = FileCheckpointStore.capture(filesDir, session, listOf("/a.txt"), resolver("/a.txt" to target)).checkpoint!!
        val base = File(SessionWorkspace.base(filesDir, session), ".checkpoints")

        assertFalse(FileCheckpointStore.drop(filesDir, session, ".."))
        assertFalse(FileCheckpointStore.drop(filesDir, session, "../.."))
        assertFalse(FileCheckpointStore.drop(filesDir, session, "."))
        assertFalse(FileCheckpointStore.drop(filesDir, session, "a/../b"))
        assertTrue("well-formed but unknown ids delete nothing and report success (deleteRecursively on a missing dir)",
            FileCheckpointStore.drop(filesDir, session, "00000000"))

        assertTrue("session workspace must survive every refused drop", target.isFile)
        assertEquals("precious", target.readText())
        assertTrue("checkpoint pool must survive every refused drop", File(base, cp.id).isDirectory)
    }

    @Test
    fun restoreRejectsTraversalIdsWithoutThrowing() {
        writeTarget("v1")
        FileCheckpointStore.capture(filesDir, session, listOf("/a.txt"), resolver("/a.txt" to target))
        for (bad in listOf("..", "../..", "a/../b", "........", "ABCDEF12", "1234567", "123456789")) {
            val r = FileCheckpointStore.restore(filesDir, session, bad, resolver("/a.txt" to target))
            assertFalse("'$bad' must not resolve", r.found)
            assertTrue("'$bad' must be reported, not thrown", r.lines.single().contains(bad))
        }
        assertEquals("v1", target.readText())
    }

    @Test
    fun tamperedManifestPayloadNameFailsClosed() {
        writeTarget("v1")
        val cp = FileCheckpointStore.capture(filesDir, session, listOf("/a.txt"), resolver("/a.txt" to target)).checkpoint!!
        // A hand-edited manifest must not be able to point the restore at a
        // payload outside the checkpoint directory.
        val manifest = File(File(File(SessionWorkspace.base(filesDir, session), ".checkpoints"), cp.id), "manifest.json")
        val json = org.json.JSONObject(manifest.readText())
        json.getJSONArray("entries").getJSONObject(0).put("payload", "../evil")
        manifest.writeText(json.toString())

        writeTarget("v2")
        val r = FileCheckpointStore.restore(filesDir, session, cp.id, resolver("/a.txt" to target))
        assertFalse(r.lines.toString(), r.success)
        assertTrue(r.lines.toString(), r.lines.any { it.contains("malformed") })
        assertEquals("v2", target.readText())
    }
}