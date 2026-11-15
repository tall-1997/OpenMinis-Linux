package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-prior-read] Decision-table contract for the read-before-edit gate. The
 * stat hook is injected, so no real filesystem (and no android.system.Os) is
 * involved: each test pins one line of the qwen priorReadEnforcement tree.
 */
class PriorReadEnforcementTest {

    private val file = File("/tmp/whatever.md")
    private val cache = FileReadCache(clock = { 1_000L })

    /** Fake stat: hands out a canned identity regardless of the real file. */
    private fun statOf(identity: FileIdentity?): (File) -> FileIdentity? = { identity }

    private fun check(identity: FileIdentity?): PriorReadEnforcement.Decision =
        PriorReadEnforcement.checkOverwrite(cache, file, displayPath = "notes.md", stat = statOf(identity))

    @Test
    fun `fresh cacheable read allows the edit`() {
        val id = FileIdentity(1L, 42L, 100L, 10L)
        cache.recordRead(id, cacheable = true)
        val d = check(id)
        assertTrue(d.ok)
        assertNull(d.errorCode)
    }

    @Test
    fun `never-read file is refused with a read hint`() {
        val d = check(FileIdentity(1L, 42L, 100L, 10L))
        assertFalse(d.ok)
        assertEquals(PriorReadEnforcement.EDIT_REQUIRES_PRIOR_READ, d.errorCode)
        assertTrue(d.modelMessage!!.contains("notes.md"))
        assertTrue("must tell the model the remedy", d.modelMessage!!.contains("file_read"))
        assertTrue("must carry the machine-readable code", d.modelMessage!!.contains("[${PriorReadEnforcement.EDIT_REQUIRES_PRIOR_READ}]"))
    }

    @Test
    fun `drifted fingerprint is refused as changed-on-disk`() {
        val read = FileIdentity(1L, 42L, 100L, 10L)
        cache.recordRead(read, cacheable = true)
        val d = check(FileIdentity(1L, 42L, 101L, 10L))
        assertFalse(d.ok)
        assertEquals(PriorReadEnforcement.FILE_CHANGED_SINCE_READ, d.errorCode)
        assertTrue(d.modelMessage!!.contains("changed on disk"))
    }

    @Test
    fun `fresh but binary-only read is refused`() {
        val id = FileIdentity(1L, 42L, 100L, 10L)
        cache.recordRead(id, cacheable = false)
        val d = check(id)
        assertFalse(d.ok)
        assertEquals(PriorReadEnforcement.EDIT_REQUIRES_PRIOR_READ, d.errorCode)
        assertTrue(d.modelMessage!!.contains("binary/preview"))
    }

    @Test
    fun `authored bytes (recordWrite) allow the edit`() {
        val written = FileIdentity(1L, 42L, 105L, 12L)
        cache.recordWrite(written)
        val d = check(written)
        assertTrue("write→edit chain must not deadlock on a re-read", d.ok)
    }

    @Test
    fun `stat failure fails closed`() {
        val d = check(null)
        assertFalse(d.ok)
        assertEquals(PriorReadEnforcement.PRIOR_READ_VERIFICATION_FAILED, d.errorCode)
        assertTrue(d.modelMessage!!.contains("stat failed"))
    }

    @Test
    fun `zero ino fails closed`() {
        val d = check(FileIdentity(1L, 0L, 100L, 10L))
        assertFalse(d.ok)
        assertEquals(PriorReadEnforcement.PRIOR_READ_VERIFICATION_FAILED, d.errorCode)
        assertTrue(d.modelMessage!!.contains("ino=0"))
    }

    @Test
    fun `unverifiable cached state fails closed`() {
        val d = check(FileIdentity(1L, 0L, 100L, 10L))
        assertFalse(d.ok)
        assertEquals(PriorReadEnforcement.PRIOR_READ_VERIFICATION_FAILED, d.errorCode)
    }

    @Test
    fun `error messages quote the guest-side display path`() {
        val d = check(FileIdentity(1L, 42L, 100L, 10L))
        assertTrue(
            "the model only knows the path it passed; refusals quote it verbatim",
            d.modelMessage!!.contains("notes.md"),
        )
    }
}
