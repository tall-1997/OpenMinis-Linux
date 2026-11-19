package com.openminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-memory-revision-day-scoped] A revision is only meaningful for the day file
 * it was minted from.
 *
 * Field report: writing the day's FIRST memory entry just after midnight was
 * refused with "MEMORY_CONFLICT — 日志文件已被并发更新". Nothing was concurrent.
 * The caller had written yesterday's log minutes earlier and pasted that
 * revision; today's file did not exist yet, so the comparison ran against `""`
 * (SHA-256 `e3b0c442…`) and reported a conflict that never happened.
 *
 * The fix makes revisions self-describing (`YYYY-MM-DD:hash16`) and checks the
 * day BEFORE the hash, so "the log rolled over" and "someone rewrote today"
 * classify differently instead of collapsing into one scary message.
 */
class MemoryRevisionTest {

    // -- token shape --------------------------------------------------------

    @Test
    fun `token carries the day it belongs to`() {
        val token = MemoryRevision.token("2026-11-20", "hello")
        assertTrue(token, token.startsWith("2026-11-20:"))
        assertEquals(11 + 16, token.length) // 10-char date + ':' + 16-char hash
    }

    @Test
    fun `same content in different days yields different tokens`() {
        val a = MemoryRevision.token("2026-11-19", "same body")
        val b = MemoryRevision.token("2026-11-20", "same body")
        assertTrue("day tag must distinguish the two", a != b)
        assertEquals(a.substringAfter(':'), b.substringAfter(':'))
    }

    // -- parsing ------------------------------------------------------------

    @Test
    fun `parse splits day and hash`() {
        val parsed = MemoryRevision.parse("2026-11-20:ca9639251e3d29e3")
        assertEquals("2026-11-20", parsed.day)
        assertEquals("ca9639251e3d29e3", parsed.hash)
    }

    @Test
    fun `parse keeps legacy bare hashes working`() {
        val parsed = MemoryRevision.parse("ca9639251e3d29e3")
        assertNull("a pre-fix revision has no day tag", parsed.day)
        assertEquals("ca9639251e3d29e3", parsed.hash)
    }

    // -- classification -----------------------------------------------------

    @Test
    fun `yesterday's revision against today's missing file is a rollover`() {
        val rejection = MemoryRevision.rejection(
            revision = "2026-11-19:abc123",
            day = "2026-11-20",
            fileName = "2026-11-20.md",
            dayFileExists = false,
        )
        assertTrue(rejection is MemoryRevision.Rejection.DayRolledOver)
        val rolled = rejection as MemoryRevision.Rejection.DayRolledOver
        assertEquals("2026-11-19", rolled.revisionDay)
        assertEquals("2026-11-20", rolled.today)
        assertTrue("no content yet → create path", !rolled.dayFileHasContent)
    }

    @Test
    fun `yesterday's revision against today's existing file reports content present`() {
        val rejection = MemoryRevision.rejection(
            revision = "2026-11-19:abc123",
            day = "2026-11-20",
            fileName = "2026-11-20.md",
            dayFileExists = true,
        )
        val rolled = rejection as MemoryRevision.Rejection.DayRolledOver
        assertTrue("append path", rolled.dayFileHasContent)
    }

    @Test
    fun `legacy bare hash against a missing file is not a rollover`() {
        // No day tag to compare: the only honest report is "no log today".
        val rejection = MemoryRevision.rejection(
            revision = "a6331fd448ee678e",
            day = "2026-11-20",
            fileName = "2026-11-20.md",
            dayFileExists = false,
        )
        assertTrue(rejection is MemoryRevision.Rejection.NoLogToday)
        assertEquals("2026-11-20.md", (rejection as MemoryRevision.Rejection.NoLogToday).fileName)
    }

    @Test
    fun `matching day on an existing file proceeds to the hash check`() {
        assertNull(
            MemoryRevision.rejection(
                revision = "2026-11-20:ca9639251e3d29e3",
                day = "2026-11-20",
                fileName = "2026-11-20.md",
                dayFileExists = true,
            ),
        )
    }

    @Test
    fun `legacy bare hash on an existing file proceeds to the hash check`() {
        assertNull(
            MemoryRevision.rejection(
                revision = "ca9639251e3d29e3",
                day = "2026-11-20",
                fileName = "2026-11-20.md",
                dayFileExists = true,
            ),
        )
    }

    // -- guard wording ------------------------------------------------------

    @Test
    fun `guard refuses a rollover without claiming a concurrent update`() {
        val msg = MemoryWriteRevisionGuard.refusal(
            expectedRevision = "2026-11-19:abc123",
            fileName = "2026-11-20.md",
            dayFileExists = false,
            hashOf = { "e3b0c44298fc1c14" },
            existingContent = "",
        )
        requireNotNull(msg)
        assertTrue("names both days", msg.contains("2026-11-19") && msg.contains("2026-11-20"))
        assertTrue("says it is a rollover", msg.contains("翻页"))
        assertTrue("must not claim a concurrent rewrite", !msg.contains("并发更新"))
        assertTrue("tells the caller to omit revision", msg.contains("省略 revision"))
    }

    @Test
    fun `guard reports a real hash mismatch as MEMORY_CONFLICT`() {
        val msg = MemoryWriteRevisionGuard.refusal(
            expectedRevision = "2026-11-20:aaaaaaaaaaaaaaaa",
            fileName = "2026-11-20.md",
            dayFileExists = true,
            hashOf = { "bbbbbbbbbbbbbbbb" },
            existingContent = "today's body",
        )
        requireNotNull(msg)
        assertTrue(msg.contains("MEMORY_CONFLICT"))
    }

    @Test
    fun `guard passes a matching revision`() {
        assertNull(
            MemoryWriteRevisionGuard.refusal(
                expectedRevision = "2026-11-20:aaaaaaaaaaaaaaaa",
                fileName = "2026-11-20.md",
                dayFileExists = true,
                hashOf = { "aaaaaaaaaaaaaaaa" },
                existingContent = "today's body",
            ),
        )
    }
}
