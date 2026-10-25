package com.openminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [T-prompt-cache-fingerprint] The prompt-fragment cache keys on
 * [MemoryRepository.fileFingerprint]. Stat-only (mtime+length) missed
 * same-length rewrites landing inside the mtime granularity — a rewritten
 * GLOBAL.md then kept serving a STALE prompt fragment. The fingerprint now
 * includes a short content digest.
 */
class MemoryFingerprintTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun repo() = MemoryRepository(tmp.root)

    @Test
    fun missingFileFingerprintIsNull() {
        assertNull(repo().fileFingerprint(File(tmp.root, "nope.md")))
    }

    @Test
    fun unchangedFileKeepsStableFingerprint() {
        val f = File(tmp.root, "GLOBAL.md").apply { writeText("stable content") }
        assertEquals(repo().fileFingerprint(f), repo().fileFingerprint(f))
    }

    @Test
    fun sameLengthSameMtimeDifferentContentStillInvalidates() {
        val f = File(tmp.root, "GLOBAL.md")
        f.writeText("aaa")
        val stamp = f.lastModified()
        val fp1 = repo().fileFingerprint(f)
        // Same-length rewrite, mtime forced back — the exact case a stat-only
        // fingerprint could not see.
        f.writeText("bbb")
        assertTrue("test needs setLastModified to stick", f.setLastModified(stamp))
        assertEquals(stamp, f.lastModified())
        val fp2 = repo().fileFingerprint(f)
        assertNotEquals("content digest must catch same-stat rewrites", fp1, fp2)
    }
}
