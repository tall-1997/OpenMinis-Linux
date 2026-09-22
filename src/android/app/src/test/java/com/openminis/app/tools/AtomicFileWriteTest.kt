package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pins the two properties that were missing when `file_write` / `file_edit`
 * "reported success but nothing landed".
 */
class AtomicFileWriteTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun writeVerifiesAndReturnsLength() {
        val f = File(tmp.root, "a.txt")
        val n = AtomicFileWrite.write(f, "hello world")
        assertEquals(11L, n)
        assertEquals("hello world", f.readText())
    }

    @Test
    fun overwriteReplacesRatherThanAppends() {
        val f = File(tmp.root, "b.txt")
        AtomicFileWrite.write(f, "first")
        AtomicFileWrite.write(f, "second")
        assertEquals("second", f.readText())
    }

    @Test
    fun appendKeepsExistingBytes() {
        val f = File(tmp.root, "c.txt")
        AtomicFileWrite.write(f, "one")
        AtomicFileWrite.write(f, "-two", append = true)
        assertEquals("one-two", f.readText())
    }

    @Test
    fun concurrentWritesAllLand() {
        // N writers, distinct content, same file. Without the per-path lock a
        // writer can observe a snapshot another is mid-way through replacing.
        val f = File(tmp.root, "race.txt")
        AtomicFileWrite.write(f, "seed")
        val pool = Executors.newFixedThreadPool(8)
        val done = CountDownLatch(40)
        val applied = AtomicInteger()
        repeat(40) { i ->
            pool.submit {
                try {
                    // read-modify-write: each task appends its own marker to
                    // whatever is currently there. A lost update shows up as a
                    // missing marker.
                    AtomicFileWrite.readModifyWrite(f) { cur ->
                        val next = if (cur.isEmpty()) "m$i" else "$cur,m$i"
                        AtomicFileWrite.write(f, next)
                        next
                    }
                    applied.incrementAndGet()
                } finally {
                    done.countDown()
                }
            }
        }
        assertTrue("tasks did not finish", done.await(30, TimeUnit.SECONDS))
        pool.shutdownNow()

        val markers = f.readText().split(",").filter { it.startsWith("m") }.toSet()
        assertEquals("lost updates: ${f.readText()}", 40, markers.size)
        assertEquals(40, applied.get())
    }

    @Test
    fun concurrentEditsDoNotLoseEachOthersChange() {
        // The file_edit shape specifically: many readers computing a distinct
        // replacement over a shared base. Every change must survive.
        val f = File(tmp.root, "base.txt")
        val base = (1..50).joinToString("\n") { "line-$it" }
        AtomicFileWrite.write(f, base)

        val pool = Executors.newFixedThreadPool(8)
        val done = CountDownLatch(50)
        repeat(50) { i ->
            pool.submit {
                try {
                    AtomicFileWrite.readModifyWrite(f) { cur ->
                        cur.replaceFirst("line-1", "line-1 edited-$i")
                    }
                } finally {
                    done.countDown()
                }
            }
        }
        assertTrue("tasks did not finish", done.await(30, TimeUnit.SECONDS))
        pool.shutdownNow()

        val lines = f.readText().lines()
        // Exactly one edited line-1 (the last writer to win), and every other
        // line still present — no edit may have reverted a sibling's change.
        assertEquals(1, lines.count { it.startsWith("line-1 edited-") })
        assertEquals(50, lines.size)
        (2..50).forEach { assertTrue("line-$it missing", lines.contains("line-$it")) }
    }

    @Test
    fun readModifyWriteReturnsNullWhenWriteCannotLand() {
        val f = File(tmp.root, "locked.txt")
        AtomicFileWrite.write(f, "orig")
        // A failed replace must not delete the destination first. OS read-only
        // bits are not reliable here (Windows ignores them), so force the miss.
        AtomicFileWrite.failReplaceForTest = true
        try {
            val out = AtomicFileWrite.readModifyWrite(f) { it + "!" }
            assertNull("a failed write must not look like success", out)
            assertEquals("original content must survive a failed write", "orig", f.readText())
        } finally {
            AtomicFileWrite.failReplaceForTest = false
        }
    }

    @Test
    fun readReturnsCurrentBytesUnderLock() {
        val f = File(tmp.root, "r.txt")
        AtomicFileWrite.write(f, "v1")
        assertEquals("v1", AtomicFileWrite.read(f))
        AtomicFileWrite.write(f, "v2")
        assertEquals("v2", AtomicFileWrite.read(f))
        assertNotNull(AtomicFileWrite.read(File(tmp.root, "missing.txt")))
    }
}
