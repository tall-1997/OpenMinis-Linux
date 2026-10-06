package com.openminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-file-hub] Merge rules, quiet-window semantics, noise filtering and the
 * pending cap — the pure core behind FileChangeHub, driven by a fake clock.
 */
class FileEventCoalescerTest {

    private var now = 1_000L
    private fun coalescer(windowMs: Long = 400L) = FileEventCoalescer(windowMs = windowMs, clock = { now })

    private val CREATE = FileEventCoalescer.Kind.CREATE
    private val MODIFY = FileEventCoalescer.Kind.MODIFY
    private val DELETE = FileEventCoalescer.Kind.DELETE

    @Test
    fun singleEventDueAfterWindow() {
        val c = coalescer()
        c.offer("a.txt", CREATE, false)
        assertTrue(c.drainDue().isEmpty())
        now += 399
        assertTrue(c.drainDue().isEmpty())
        now += 1
        val due = c.drainDue()
        assertEquals(1, due.size)
        assertEquals("a.txt", due[0].relPath)
        assertEquals(CREATE, due[0].kind)
        assertEquals(1, due[0].count)
        assertFalse(due[0].isDir)
        assertEquals("drained entries are removed", 0, c.pendingCount())
        assertTrue(c.drainDue().isEmpty())
    }

    @Test
    fun createAbsorbsModifyWithCount() {
        val c = coalescer()
        c.offer("f", CREATE, false)
        c.offer("f", MODIFY, false)
        c.offer("f", MODIFY, false)
        now += 400
        val due = c.drainDue().single()
        assertEquals(CREATE, due.kind)
        assertEquals(3, due.count)
    }

    @Test
    fun createThenDeleteCancels() {
        val c = coalescer()
        c.offer("tmpish", CREATE, false)
        c.offer("tmpish", DELETE, false)
        assertEquals(0, c.pendingCount())
        now += 400
        assertTrue(c.drainDue().isEmpty())
    }

    @Test
    fun deleteDominatesModify() {
        val c = coalescer()
        c.offer("f", MODIFY, false)
        c.offer("f", DELETE, false)
        now += 400
        assertEquals(DELETE, c.drainDue().single().kind)
    }

    @Test
    fun deleteThenCreateIsRecreation() {
        val c = coalescer()
        c.offer("f", DELETE, false)
        c.offer("f", CREATE, false)
        now += 400
        assertEquals(CREATE, c.drainDue().single().kind)
    }

    @Test
    fun windowResetsOnNewOffer() {
        val c = coalescer()
        c.offer("f", MODIFY, false)
        now += 300
        c.offer("f", MODIFY, false) // resets the quiet window
        now += 399
        assertTrue(c.drainDue().isEmpty())
        now += 1
        assertEquals(1, c.drainDue().size)
    }

    @Test
    fun isDirIsSticky() {
        val c = coalescer()
        c.offer("d", CREATE, true)
        c.offer("d", MODIFY, false)
        now += 400
        assertTrue(c.drainDue().single().isDir)
    }

    @Test
    fun noiseNamesNeverEnterPending() {
        val c = coalescer()
        for (p in listOf("x.minis-tmp", "y.tmp-replace", "z.lock", "w.swp", ".git/index", "/var/minis/workspace/.git/HEAD", "sub/.git/objects/pack")) {
            c.offer(p, MODIFY, false)
        }
        assertEquals(0, c.pendingCount())
    }

    @Test
    fun isIgnoredRules() {
        assertTrue(FileEventCoalescer.isIgnored("a.minis-tmp"))
        assertTrue(FileEventCoalescer.isIgnored("a.tmp-replace"))
        assertTrue(FileEventCoalescer.isIgnored("a.lock"))
        assertTrue(FileEventCoalescer.isIgnored("a.swp"))
        assertTrue(FileEventCoalescer.isIgnored(".git"))
        assertTrue(FileEventCoalescer.isIgnored(".git/index"))
        assertTrue(FileEventCoalescer.isIgnored("/x/.git/y"))
        assertFalse(FileEventCoalescer.isIgnored("src/main.py"))
        assertFalse(FileEventCoalescer.isIgnored("gitnotes.txt"))
    }

    @Test
    fun mergeKindsTable() {
        assertEquals(CREATE, FileEventCoalescer.mergeKinds(CREATE, CREATE))
        assertEquals(MODIFY, FileEventCoalescer.mergeKinds(MODIFY, MODIFY))
        assertEquals(DELETE, FileEventCoalescer.mergeKinds(DELETE, DELETE))
        assertEquals(CREATE, FileEventCoalescer.mergeKinds(CREATE, MODIFY))
        assertNull(FileEventCoalescer.mergeKinds(CREATE, DELETE))
        assertEquals(DELETE, FileEventCoalescer.mergeKinds(MODIFY, DELETE))
        assertEquals(CREATE, FileEventCoalescer.mergeKinds(DELETE, CREATE))
        assertEquals(CREATE, FileEventCoalescer.mergeKinds(DELETE, MODIFY))
        assertEquals(CREATE, FileEventCoalescer.mergeKinds(MODIFY, CREATE))
    }

    @Test
    fun capEvictsOldestByLastEvent() {
        val c = coalescer(windowMs = 100_000) // nothing drains during the test
        for (i in 0 until 600) {
            now += 1
            c.offer("p$i", MODIFY, false)
        }
        assertEquals(FileEventCoalescer.MAX_PENDING, c.pendingCount())
        now += 200_000
        val due = c.drainDue()
        assertEquals(512, due.size)
        val paths = due.map { it.relPath }.toSet()
        assertFalse("oldest evicted first", "p0" in paths)
        assertFalse("p87" in paths) // 600 - 512 = 88 evicted: p0..p87
        assertTrue("p88" in paths)
        assertTrue("p599" in paths)
    }

    @Test
    fun clearEmptiesPending() {
        val c = coalescer()
        c.offer("f", MODIFY, false)
        c.clear()
        assertEquals(0, c.pendingCount())
    }
}
