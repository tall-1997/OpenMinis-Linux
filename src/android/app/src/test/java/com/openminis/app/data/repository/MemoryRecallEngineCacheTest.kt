package com.openminis.app.data.repository

import org.junit.After
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [T-memory-recall-io] The engine must be reused per memory directory, and the
 * cache must stay bounded. Both halves matter: reuse is what stops the
 * recall-count sidecar from being re-parsed every turn, and the LRU bound is
 * what stops a user browsing many sessions from pinning one engine each.
 */
class MemoryRecallEngineCacheTest {

    @Before
    fun setUp() = MemoryRecallEngineCache.clearForTest()

    @After
    fun tearDown() = MemoryRecallEngineCache.clearForTest()

    private fun tempDir(): File = Files.createTempDirectory("recall-cache").toFile()

    @Test
    fun sameDirectoryYieldsTheSameInstance() {
        val dir = tempDir()
        val first = MemoryRecallEngineCache.forSession(dir)
        val second = MemoryRecallEngineCache.forSession(dir)
        assertSame(first, second)
    }

    @Test
    fun differentDirectoriesYieldDifferentInstances() {
        val a = MemoryRecallEngineCache.forSession(tempDir())
        val b = MemoryRecallEngineCache.forSession(tempDir())
        assertNotSame(a, b)
    }

    @Test
    fun canonicalPathIsTheKey() {
        val dir = tempDir()
        val first = MemoryRecallEngineCache.forSession(dir)
        // "./sub/.." style alias of the same directory must hit the same entry.
        val alias = File(dir, ".").let { File(it, ".") }
        val second = MemoryRecallEngineCache.forSession(alias)
        assertSame(first, second)
    }

    @Test
    fun lruEvictsBeyondCapacity() {
        val dirs = (0 until MemoryRecallEngineCache.CAPACITY + 2).map { tempDir() }
        val engines = dirs.map { MemoryRecallEngineCache.forSession(it) }

        // The oldest entry was evicted, so it comes back as a new instance.
        assertNotSame(engines.first(), MemoryRecallEngineCache.forSession(dirs.first()))
        // The newest entry is still cached.
        assertSame(engines.last(), MemoryRecallEngineCache.forSession(dirs.last()))
    }
}
