package com.openminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MemoryDiaryMigratorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun onlyDailyMarkdownIsRecognized() {
        assertTrue(MemoryDiaryMigrator.isDailyDiary("2026-09-21.md"))
        assertFalse(MemoryDiaryMigrator.isDailyDiary("GLOBAL.md"))
        assertFalse(MemoryDiaryMigrator.isDailyDiary("SOUL.md"))
        assertFalse(MemoryDiaryMigrator.isDailyDiary("LEARNED.md"))
        assertFalse(MemoryDiaryMigrator.isDailyDiary("2026-09-21.txt"))
        assertFalse(MemoryDiaryMigrator.isDailyDiary("notes.md"))
    }

    @Test
    fun copiesMissingDiariesAndLeavesIdentityFiles() {
        val global = tmp.newFolder("minis-global", "memory")
        File(global, "2026-09-20.md").writeText("day 20")
        File(global, "2026-09-21.md").writeText("day 21")
        File(global, "GLOBAL.md").writeText("identity")
        File(global, "LEARNED.md").writeText("prefs")
        File(global, "personas").mkdirs()

        val session = tmp.newFolder("minis-sessions", "s1", "memory")
        File(session, "2026-09-21.md").writeText("already here")

        val copied = MemoryDiaryMigrator.copyInto(session, global)
        assertEquals(1, copied)
        assertEquals("day 20", File(session, "2026-09-20.md").readText())
        assertEquals("already here", File(session, "2026-09-21.md").readText())
        assertFalse(File(session, "GLOBAL.md").exists())
        assertFalse(File(session, "LEARNED.md").exists())
    }
}
