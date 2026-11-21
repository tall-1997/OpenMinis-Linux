package com.openminis.app.data.repository

import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Locks the [MemoryRepository.getMemory] scope contract for standing files:
 * scope="all" sweeps everything including the persona/standing files, while
 * any narrower scope ("daily") searches memory entries only — the standing
 * persona/prefs files are configuration, never diary content, and must not
 * leak into the narrower results. TTL-governed diary files, by contrast, are
 * lifecycle-managed content: an unexpired one keeps appearing in "daily", and
 * an expired one is retired by governance before any scope filter runs.
 */
class MemoryScopeFilterTest {
    @get:Rule val tmp = TemporaryFolder()
    private val start = 1_798_200_000_000L
    private var now = start

    private val date = LocalDate.parse(com.openminis.app.util.IsoTime.formatLocalDate(start))

    @Test fun standingFilesAppearOnlyInTheAllScope() {
        val dir = tmp.newFolder("memory")
        File(dir, "SOUL.md").apply { writeText("cobalt-soul-marker\n"); setLastModified(start) }
        File(dir, "PERSONA.md").apply { writeText("cobalt-persona-marker\n"); setLastModified(start) }
        val repo = MemoryRepository(dir) { now }

        val daily = repo.getMemory("cobalt", "daily")
        assertFalse(
            "a standing persona file must not leak into the daily scope",
            daily.contains("cobalt-soul-marker"),
        )
        assertFalse(daily.contains("cobalt-persona-marker"))

        val all = repo.getMemory("cobalt", "all")
        assertTrue(all.contains("cobalt-soul-marker"))
        assertTrue(all.contains("cobalt-persona-marker"))
    }

    @Test fun unexpiredTtlFilesStayInTheDailyScope() {
        val dir = tmp.newFolder("memory")
        val present = File(dir, "present.md")
        present.writeText(
            "\uFEFF---\r\nttl_days: 5\r\n---\r\n<!-- $date 00:00:00 -->\r\ncobalt-present-marker\r\n\r\n",
        )
        present.setLastModified(start)
        val repo = MemoryRepository(dir) { now }

        assertTrue(
            "an unexpired TTL file is diary content, not standing config",
            repo.getMemory("cobalt", "daily").contains("cobalt-present-marker"),
        )
        assertTrue(repo.getMemory("cobalt", "all").contains("cobalt-present-marker"))
    }

    @Test fun expiredTtlFilesAreRetiredBeforeAnyScopeFilter() {
        val dir = tmp.newFolder("memory")
        val past = File(dir, "past.md")
        past.writeText(
            "\uFEFF---\r\nttl_days: 1\r\n---\r\n<!-- $date 00:00:00 -->\r\ncobalt-past-marker\r\n\r\n",
        )
        past.setLastModified(start - 2 * MemoryLifecycle.DAY_MS)
        val repo = MemoryRepository(dir) { now }

        assertFalse(repo.getMemory("cobalt", "daily").contains("cobalt-past-marker"))
        assertFalse(repo.getMemory("cobalt", "all").contains("cobalt-past-marker"))
    }
}
