package com.openminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Memory files are read on every system-prompt build, concurrently with the
 * writes that memory_write and Settings → Memory perform. A plain writeText
 * left a window where a reader saw a truncated or empty file, and the readers
 * treat empty as "nothing to inject" — one unlucky turn silently dropped the
 * user's global rules. These pin the two helpers that close that hole.
 */
class MemoryFileIoTest {
    private fun tempDir(): File = Files.createTempDirectory("memio").toFile()

    @Test
    fun atomicWriteLeavesContentIntactAndNoTmpBehind() {
        val dir = tempDir()
        val file = File(dir, "GLOBAL.md")
        writeTextAtomic(file, "rule one\nrule two\n")
        assertEquals("rule one\nrule two\n", file.readText())
        assertTrue(
            "no .tmp sibling may survive: ${dir.listFiles()?.map { it.name }}",
            dir.listFiles().orEmpty().none { it.name.endsWith(".tmp") },
        )
    }

    @Test
    fun atomicWriteReplacesExistingContentFully() {
        val dir = tempDir()
        val file = File(dir, "2026-10-03.md")
        writeTextAtomic(file, "a".repeat(4096))
        writeTextAtomic(file, "short")
        assertEquals("short", file.readText())
    }

    @Test
    fun resilientReadReturnsNullForMissingFile() {
        assertNull(readTextResilient(File(tempDir(), "nope.md"), "test"))
    }

    @Test
    fun resilientReadReturnsContentWhenPresent() {
        val dir = tempDir()
        val file = File(dir, "GLOBAL.md")
        file.writeText("standing rule")
        assertEquals("standing rule", readTextResilient(file, "test"))
    }
}
