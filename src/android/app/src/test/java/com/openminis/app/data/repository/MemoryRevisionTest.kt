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

    // -- [T-memory-revision-degenerate-inputs] defensive-predicate degenerate --
    // -- inputs: empty / short / multi-colon / non-hex / oversized / uppercase --
    // -- every one of these walked straight past the old startsWith() check,   --
    // -- silently letting a concurrent writer overwrite the first.             --

    @Test
    fun `an empty hash is malformed, never a pass`() {
        assertTrue(
            MemoryRevision.rejection(
                revision = "2026-11-20:",
                day = "2026-11-20",
                fileName = "2026-11-20.md",
                dayFileExists = true,
            ) is MemoryRevision.Rejection.Malformed,
        )
        // The guard-level reproduction of the reported F1 bypass: the old code
        // returned null (proceed) here — JVM-reproduced before the fix.
        val f1Msg = MemoryWriteRevisionGuard.refusal(
            expectedRevision = "2026-11-20:",
            fileName = "2026-11-20.md",
            dayFileExists = true,
            hashOf = { "ca9639251e3d29e3" },
            existingContent = "someone wrote this first",
        )
        assertEquals(MemoryWriteRevisionGuard.malformedCopy(), f1Msg)
        // The copy must stay actionable: the model self-corrects from it, so
        // losing either half of the guidance turns the refusal into a dead end.
        requireNotNull(f1Msg)
        assertTrue("must tell the caller to pass it verbatim", f1Msg.contains("原样传入"))
        assertTrue("must offer the omit-revision escape", f1Msg.contains("省略 revision"))
    }

    @Test
    fun `a short hash prefix is malformed, never a weakened pass`() {
        // 1..15 hex chars must not match: the old startsWith() let any prefix
        // of the real hash through (1-char guess = 1/16 hit rate).
        for (short in listOf("a", "ca963", "ca9639251e3d29e")) {
            assertTrue(
                "prefix '$short' must be malformed",
                MemoryRevision.rejection(
                    revision = "2026-11-20:$short",
                    day = "2026-11-20",
                    fileName = "2026-11-20.md",
                    dayFileExists = true,
                ) is MemoryRevision.Rejection.Malformed,
            )
        }
    }

    @Test
    fun `a multi-colon revision is malformed`() {
        assertTrue(
            MemoryRevision.rejection(
                revision = "2026-11-20:abc:def",
                day = "2026-11-20",
                fileName = "2026-11-20.md",
                dayFileExists = true,
            ) is MemoryRevision.Rejection.Malformed,
        )
    }

    @Test
    fun `non-hex, oversized and uppercase hashes are malformed`() {
        for (hash in listOf("zzzzzzzzzzzzzzzz", "ca9639251e3d29e3f", "CA9639251E3D29E3")) {
            assertTrue(
                "hash '$hash' must be malformed",
                MemoryRevision.rejection(
                    revision = "2026-11-20:$hash",
                    day = "2026-11-20",
                    fileName = "2026-11-20.md",
                    dayFileExists = true,
                ) is MemoryRevision.Rejection.Malformed,
            )
        }
    }

    @Test
    fun `a malformed day segment is malformed even when the hash fits`() {
        // "20261145" is not a YYYY-MM-DD shape (no separators): it can carry no
        // rollover diagnosis, so it must fail the shape gate.
        assertTrue(
            MemoryRevision.rejection(
                revision = "20261145:ca9639251e3d29e3",
                day = "2026-11-20",
                fileName = "2026-11-20.md",
                dayFileExists = true,
            ) is MemoryRevision.Rejection.Malformed,
        )
    }

    @Test
    fun `a legal yesterday day still classifies as rollover despite a bad hash`() {
        // Diagnosis order: the actionable "rolled over" beats the shape complaint.
        assertTrue(
            MemoryRevision.rejection(
                revision = "2026-11-19:zzzz",
                day = "2026-11-20",
                fileName = "2026-11-20.md",
                dayFileExists = true,
            ) is MemoryRevision.Rejection.DayRolledOver,
        )
    }

    @Test
    fun `legacy 16-hex hashes stay well-formed`() {
        assertTrue(MemoryRevision.wellFormed("ca9639251e3d29e3"))
        assertNull(
            MemoryRevision.rejection(
                revision = "ca9639251e3d29e3",
                day = "2026-11-20",
                fileName = "2026-11-20.md",
                dayFileExists = true,
            ),
        )
    }

    @Test
    fun `wellFormed mirrors the rejection shape gate for the tool layer`() {
        assertTrue(MemoryRevision.wellFormed("2026-11-20:ca9639251e3d29e3"))
        assertTrue(
            "empty hash is not well-formed",
            !MemoryRevision.wellFormed("2026-11-20:"),
        )
        assertTrue(
            "a 1MB garbled parameter is not well-formed",
            !MemoryRevision.wellFormed("2026-11-20:" + "x".repeat(1_000_000)),
        )
    }

    @Test
    fun `a 15-char prefix is malformed, not a weakened pass`() {
        // The old startsWith() would have PASSED this (the 15 chars are a real
        // prefix of the file's hash); the shape gate refuses it as malformed
        // instead of letting a truncated revision through.
        val msg = MemoryWriteRevisionGuard.refusal(
            expectedRevision = "2026-11-20:ca9639251e3d29e",
            fileName = "2026-11-20.md",
            dayFileExists = true,
            hashOf = { "ca9639251e3d29e3" },
            existingContent = "today's body",
        )
        assertEquals(MemoryWriteRevisionGuard.malformedCopy(), msg)
    }

    @Test
    fun `writeMemory's returned revision round-trips into the next write`() {
        // [F9 follow-up] The token minted by a write must be accepted verbatim
        // by the next write on the same day — the whole point of the format.
        // Fixed clock: a wall-clock read between the two writes could roll over
        // midnight and turn this into a DayRolledOver flake (23:59 boundary).
        val dir = java.nio.file.Files.createTempDirectory("memrev-roundtrip").toFile()
        try {
            val repo = MemoryRepository(dir) { 1_798_200_000_000L }
            val first = repo.writeMemory("round-trip entry one", null)
            assertTrue(first.startsWith("Memory saved"))
            val token = Regex("revision (\\d{4}-\\d{2}-\\d{2}:[0-9a-f]{16})").find(first)
                ?.groupValues?.get(1)
                ?: error("no revision in write reply: $first")
            val second = repo.writeMemory("round-trip entry two", token)
            assertTrue(
                "the freshly minted revision must be accepted verbatim, got: $second",
                second.startsWith("Memory saved"),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a legacy bare hash passes the guard end-to-end when it matches`() {
        // [P3 review follow-up] The legacy path was only covered piecewise
        // (rejection()==null at the classifier, matching token at the guard).
        // This pins the combined legacy contract: a pre-#407 bare 16-hex hash
        // that matches today's file still writes.
        assertNull(
            MemoryWriteRevisionGuard.refusal(
                expectedRevision = "ca9639251e3d29e3",
                fileName = "2026-11-20.md",
                dayFileExists = true,
                hashOf = { "ca9639251e3d29e3" },
                existingContent = "today's body",
            ),
        )
    }
}
