package com.openminis.app.tools

import com.openminis.app.data.authority.MemoryOrigin
import com.openminis.app.data.repository.MemoryRepository
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [T-p0-authority] The tool-access matrix was real code with real tests but no
 * production dispatch point: `executeMemoryGet` had no gate at all, and
 * `executeMemoryWrite` hard-coded [MemoryOrigin.AGENT], so the
 * `AUTOMATIC_RECALL` read-only rule could never actually refuse anything.
 *
 * These run on the plain JVM: the origin gate is checked before any JSON
 * parsing or repository I/O, and the project supplies a real `org.json` for
 * unit tests (`unitTests.isReturnDefaultValues` also keeps `android.util.Log`
 * quiet).
 */
class MemoryToolsOriginTest {

    private lateinit var dir: File
    private lateinit var repository: MemoryRepository

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("memory-tools-origin").toFile()
        repository = MemoryRepository(dir)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun automaticRecallCannotWrite() {
        val result = MemoryTools.executeMemoryWrite("", repository, MemoryOrigin.AUTOMATIC_RECALL)
        assertFalse(result.success)
        assertTrue(result.output.contains("not permitted"))
    }

    @Test
    fun automaticRecallCanRead() {
        assertTrue(MemoryTools.guardAccess(MemoryOrigin.AUTOMATIC_RECALL, "memory_get"))
    }

    @Test
    fun automaticRecallIsRefusedOnTheWriteGuard() {
        assertFalse(MemoryTools.guardAccess(MemoryOrigin.AUTOMATIC_RECALL, "memory_write"))
    }

    @Test
    fun agentOriginPassesBothGuards() {
        assertTrue(MemoryTools.guardAccess(MemoryOrigin.AGENT, "memory_write"))
        assertTrue(MemoryTools.guardAccess(MemoryOrigin.AGENT, "memory_get"))
    }

    @Test
    fun interfaceOriginPassesBothGuards() {
        assertTrue(MemoryTools.guardAccess(MemoryOrigin.INTERFACE, "memory_write"))
        assertTrue(MemoryTools.guardAccess(MemoryOrigin.INTERFACE, "memory_get"))
    }

    /**
     * Default-parameter regression: existing call sites pass no origin, so they
     * must still be treated as an agent (gate open). Asserted through the
     * refusal message rather than the parse result, because an empty payload
     * fails validation for unrelated reasons.
     */
    @Test
    fun existingTwoArgumentCallSitesDoNotHitTheOriginGate() {
        val result = MemoryTools.executeMemoryWrite("", repository)
        assertFalse(result.output.contains("not permitted"))
    }
}
