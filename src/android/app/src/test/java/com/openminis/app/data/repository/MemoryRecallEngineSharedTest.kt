package com.openminis.app.data.repository

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [T-memory-shared-recall] Recall now covers the app-wide `minis-global/memory`
 * directory as a second scope, so a lesson recorded in another chat is reachable
 * here. The session copy still wins on a filename collision, because that is the
 * one the verbatim window shows.
 */
class MemoryRecallEngineSharedTest {

    private lateinit var root: File
    private lateinit var sessionDir: File
    private lateinit var sharedDir: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("recall-shared").toFile()
        sessionDir = File(root, "session").apply { mkdirs() }
        sharedDir = File(root, "shared").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun engine(): MemoryRecallEngine =
        MemoryRecallEngine({ sessionDir }, { sharedDir }).also { it.loadRecallCounts() }

    @Test
    fun aHitLivingOnlyInTheSharedScopeIsFound() {
        File(sharedDir, "2026-10-20.md").writeText("prlimit was the real OOM cause\n")
        val hits = engine().recall("prlimit OOM")
        assertTrue("shared scope must be searchable", hits.any { it.line.contains("prlimit") })
    }

    @Test
    fun theSessionCopyWinsOnTheSameFilename() {
        File(sessionDir, "2026-10-20.md").writeText("session copy about prlimit\n")
        File(sharedDir, "2026-10-20.md").writeText("shared copy about prlimit\n")
        val hits = engine().recall("prlimit")
        assertEquals(1, hits.size)
        assertTrue(hits.single().line.contains("session copy"))
    }

    @Test
    fun anEngineWithoutASharedScopeBehavesExactlyAsBefore() {
        File(sharedDir, "2026-10-20.md").writeText("shared only\n")
        val isolated = MemoryRecallEngine({ sessionDir }).also { it.loadRecallCounts() }
        assertTrue(isolated.recall("shared").isEmpty())
        assertNull(isolated.recallCountsSnapshot()["2026-10-20:shared only"])
    }

    @Test
    fun cacheDistinguishesScopesSoAnEngineIsNotReusedAcrossThem() {
        MemoryRecallEngineCache.clearForTest()
        val sessionOnly = MemoryRecallEngineCache.forSession(sessionDir)
        val withShared = MemoryRecallEngineCache.forSession(sessionDir, sharedDir)
        assertTrue(sessionOnly !== withShared)
        // Same scope twice is still one engine.
        assertTrue(withShared === MemoryRecallEngineCache.forSession(sessionDir, sharedDir))
        MemoryRecallEngineCache.clearForTest()
    }
}
