package com.openminis.app.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-m9-supervisor-experience] Pure-function tests for the per-session tool
 * experience folds: first-call defaults, bounded counters, fingerprint
 * rotation, and the supervisor recall block format.
 */
class ProjectExperienceCoreTest {

    @Test
    fun idFor_isStableForSessionAndTool() {
        val a = ProjectExperienceCore.idFor("s1", "file_write")
        val b = ProjectExperienceCore.idFor("s1", "file_write")
        val c = ProjectExperienceCore.idFor("s2", "file_write")
        val d = ProjectExperienceCore.idFor("s1", "shell_execute")
        assertEquals(a, b)
        assertTrue(a != c)
        assertTrue(a != d)
    }

    @Test
    fun idFor_isNotBlankForBlankInputs() {
        assertTrue(ProjectExperienceCore.idFor("", "shell").isNotBlank())
        assertTrue(ProjectExperienceCore.idFor("s", "").isNotBlank())
    }

    @Test
    fun applyResult_firstCall_startsCountersAtOne() {
        val row = ProjectExperienceCore.applyResult(
            sessionId = "s",
            toolName = "shell_execute",
            prev = null,
            success = true,
            nowMs = 1_000L,
        )
        assertEquals(1, row.usedCount)
        assertEquals(1, row.successCount)
        assertEquals(0, row.failureCount)
        assertEquals(1_000L, row.lastUsedAtMs)
        assertEquals("shell_execute", row.toolName)
    }

    @Test
    fun applyResult_failureFirst_startsFailureCounterAtOne() {
        val row = ProjectExperienceCore.applyResult(
            sessionId = "s",
            toolName = "shell_execute",
            prev = null,
            success = false,
            nowMs = 2_000L,
        )
        assertEquals(1, row.usedCount)
        assertEquals(0, row.successCount)
        assertEquals(1, row.failureCount)
    }

    @Test
    fun applyResult_successAfterFailure_keepsBothCounters() {
        val prev = ProjectExperienceCore.applyResult(
            sessionId = "s",
            toolName = "t",
            prev = null,
            success = false,
            nowMs = 1_000L,
        )
        val row = ProjectExperienceCore.applyResult(
            sessionId = "s",
            toolName = "t",
            prev = prev,
            success = true,
            nowMs = 2_000L,
        )
        assertEquals(2, row.usedCount)
        assertEquals(1, row.successCount)
        assertEquals(1, row.failureCount)
        assertEquals(2_000L, row.lastUsedAtMs)
    }

    @Test
    fun applyResult_preservesFirstUsedAndSessionId() {
        val first = ProjectExperienceCore.applyResult("s", "t", null, success = true, nowMs = 5_000L)
        val second = ProjectExperienceCore.applyResult("s", "t", first, success = true, nowMs = 9_000L)
        assertEquals(5_000L, second.firstUsedAtMs)
        assertEquals("s", second.sessionId)
        assertEquals("s|t", second.id)
    }

    @Test
    fun fingerprint_rotatesWindow() {
        val f1 = ProjectExperienceCore.fingerprintFor("s", "t", usedCount = 1, lastUsedAtMs = 100L)
        val f2 = ProjectExperienceCore.fingerprintFor("s", "t", usedCount = 2, lastUsedAtMs = 200L)
        val f3 = ProjectExperienceCore.fingerprintFor("s", "t", usedCount = 3, lastUsedAtMs = 300L)
        assertEquals("1|100", f1)
        assertEquals("2|200", f2)
        assertEquals("3|300", f3)
    }

    @Test
    fun recallBlock_emptyRows_isBlank() {
        assertEquals("", ProjectExperienceCore.recallBlock(emptyList()))
    }

    @Test
    fun recallBlock_listsToolsNewestFirst() {
        val old = ProjectExperienceCore.applyResult("s", "shell_execute", null, true, 1_000L)
        val mid = ProjectExperienceCore.applyResult("s", "file_write", null, false, 2_000L)
        val fresh = ProjectExperienceCore.applyResult("s", "file_read", null, true, 3_000L)
        val block = ProjectExperienceCore.recallBlock(listOf(old, mid, fresh))
        val shellAt = block.indexOf("shell_execute")
        val writeAt = block.indexOf("file_write")
        val readAt = block.indexOf("file_read")
        assertTrue(shellAt >= 0)
        assertTrue(writeAt >= 0)
        assertTrue(readAt >= 0)
        // Newest lastUsedAtMs first: file_read(3000) above file_write(2000) above shell_execute(1000).
        assertTrue(readAt < writeAt)
        assertTrue(writeAt < shellAt)
    }

    @Test
    fun recallBlock_dropsRowsWithZeroUses() {
        val stub = ProjectExperienceEntity(
            id = ProjectExperienceCore.idFor("s", "never"),
            sessionId = "s",
            toolName = "never",
            usedCount = 0,
            successCount = 0,
            failureCount = 0,
            firstUsedAtMs = 0L,
            lastUsedAtMs = 0L,
            fingerprint = "",
        )
        assertEquals("", ProjectExperienceCore.recallBlock(listOf(stub)))
    }

    @Test
    fun applyResult_keepsIdentityAcrossFolds() {
        val first = ProjectExperienceCore.applyResult("s", "t", null, success = true, nowMs = 1L)
        val second = ProjectExperienceCore.applyResult("s", "t", first, success = false, nowMs = 2L)
        assertEquals(first.id, second.id)
        assertEquals(first.sessionId, second.sessionId)
        assertEquals(first.firstUsedAtMs, second.firstUsedAtMs)
    }
}
