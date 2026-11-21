package com.openminis.app.data.repository

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-memory-header-cache] `withScope` has to read every other diary's header to
 * decide supersession. That is correct, but it made a single-file reindex cost
 * one open+read per file in the directory — the write path calls it on every
 * `memory_write`. The cache must make a repeat pass read NOTHING while still
 * observing a real edit.
 */
class MemoryHeaderCacheTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("memory-header-cache").toFile()
        MemorySources.clearHeaderCacheForTest()
    }

    @After
    fun tearDown() {
        MemorySources.clearHeaderCacheForTest()
        dir.deleteRecursively()
    }

    private fun seedDiaries(count: Int) {
        for (i in 0 until count) {
            File(dir, "2026-11-%02d.md".format((i % 28) + 1)).writeText("entry $i\n")
        }
    }

    private fun rawHeadersRead(target: String): Int {
        val before = MemorySources.headerReadsForTest
        MemorySources.withScope(dir, 0L, loadBody = { it == target }) { }
        return MemorySources.headerReadsForTest - before
    }

    @Test
    fun aSecondPassReadsNoHeaders() {
        seedDiaries(20)
        val target = "2026-11-01.md"
        val first = rawHeadersRead(target)
        assertTrue("the first pass must read the other diaries: $first", first > 0)
        assertEquals("the second pass must be served from the cache", 0, rawHeadersRead(target))
    }

    @Test
    fun anEditedDiaryIsReReadAndStillGovernsSupersession() {
        seedDiaries(3)
        val older = File(dir, "2026-11-01.md")
        older.writeText("\uFEFF---\r\nscope: daily\r\n---\r\nold note\r\n")
        MemorySources.withScope(dir, 0L) { }
        // Add a note that retires the older one; the cache must not hide it.
        File(dir, "2026-11-02.md").writeText("\uFEFF---\r\nsupersedes: 2026-11-01.md\r\n---\r\nnew note\r\n")

        val names = mutableListOf<String>()
        MemorySources.withScope(dir, 0L) { sources -> sources.forEach { names += it.name } }
        assertTrue("the retired diary must be filtered out, got $names", "2026-11-01.md" !in names)
    }
}
