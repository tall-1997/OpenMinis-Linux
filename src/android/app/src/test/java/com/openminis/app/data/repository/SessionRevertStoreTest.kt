package com.openminis.app.data.repository

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-p9-revert] Record round-trip on a plain JVM: the store is pure
 * File + org.json math over SessionWorkspace.base, so the "does the
 * staged record survive a process restart" question needs no Android.
 */
class SessionRevertStoreTest {

    private val filesDir: File = File(System.getProperty("java.io.tmpdir"), "minis-revert-test-${System.nanoTime()}")

    @After
    fun clean() {
        filesDir.deleteRecursively()
    }

    private fun record() = StagedRevert(
        sessionId = "s1",
        boundaryMessageId = "m1",
        boundarySortOrder = 7,
        boundaryCreatedAt = 123456L,
        originalCheckpointId = "ef56ab78",
        plan = listOf(StagedRevert.PlannedRestore("ab12cd34", listOf("/w/a.txt", "/w/b.txt"))),
        stagedAt = 999L,
    )

    @Test
    fun roundTripPreservesEveryField() {
        RevertStageStore.save(filesDir, "s1", record())
        val loaded = RevertStageStore.load(filesDir, "s1")
        assertNotNull(loaded)
        assertEquals("m1", loaded!!.boundaryMessageId)
        assertEquals(7, loaded.boundarySortOrder)
        assertEquals(123456L, loaded.boundaryCreatedAt)
        assertEquals("ef56ab78", loaded.originalCheckpointId)
        assertEquals(1, loaded.plan.size)
        assertEquals("ab12cd34", loaded.plan[0].checkpointId)
        assertEquals(listOf("/w/a.txt", "/w/b.txt"), loaded.plan[0].paths)
        assertEquals(999L, loaded.stagedAt)
        assertEquals(2, loaded.fileCount)
    }

    @Test
    fun deleteRemovesTheRecord() {
        RevertStageStore.save(filesDir, "s1", record())
        RevertStageStore.delete(filesDir, "s1")
        assertNull(RevertStageStore.load(filesDir, "s1"))
    }

    @Test
    fun corruptRecordFailsClosed() {
        val f = RevertStageStore.fileFor(filesDir, "s1")
        f.parentFile?.mkdirs()
        f.writeText("{ not json")
        assertNull(RevertStageStore.load(filesDir, "s1"))
    }

    @Test
    fun recordForAnotherSessionIsIgnored() {
        RevertStageStore.save(filesDir, "s1", record())
        assertNull(RevertStageStore.load(filesDir, "s2"))
    }

    @Test
    fun missingRecordLoadsNull() {
        assertNull(RevertStageStore.load(filesDir, "s1"))
    }
}
