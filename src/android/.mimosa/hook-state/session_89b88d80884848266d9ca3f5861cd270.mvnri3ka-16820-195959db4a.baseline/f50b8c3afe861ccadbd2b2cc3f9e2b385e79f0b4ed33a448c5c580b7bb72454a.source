package com.openminis.app.ui.chat

import org.junit.Assert.*
import org.junit.Test

class IdleSessionBudgetTest {
    @Test fun activeDraftAndRunningSessionsAreNeverVictims() {
        val entries = listOf(
            IdleSessionBudget.Entry("running", 0, 1_000_000, true),
            IdleSessionBudget.Entry("draft", 1, 1_000_000, true),
            IdleSessionBudget.Entry("old", 2, 30, false),
            IdleSessionBudget.Entry("recent", 3, 30, false),
        )
        assertEquals(listOf("old"), IdleSessionBudget.victims(entries, maxIdle = 1))
        assertEquals(listOf("old", "recent"), IdleSessionBudget.victims(entries, maxIdle = 0))
    }

    @Test fun byteBudgetAppliesEvenBelowSessionCountLimit() {
        val entries = listOf(
            IdleSessionBudget.Entry("old", 1, 60, false),
            IdleSessionBudget.Entry("recent", 2, 60, false),
        )
        assertEquals(listOf("old"), IdleSessionBudget.victims(entries, maxBytes = 100))
        assertTrue(IdleSessionBudget.victims(entries, maxBytes = 120).isEmpty())
    }

    @Test fun pinnedStayUntilPressureHasFrozenThem() {
        val entries = listOf(
            IdleSessionBudget.Entry("active", 3, 80, true),
            IdleSessionBudget.Entry("pinned-old", 1, 80, true),
            IdleSessionBudget.Entry("idle", 2, 10, false),
        )
        assertTrue(IdleSessionBudget.victims(entries, maxIdle = 3, maxBytes = 100).isEmpty())
        assertEquals(
            listOf("idle"),
            IdleSessionBudget.pressureVictims(entries, maxBytes = 100, frozenIds = emptySet()),
        )
        assertEquals(
            listOf("idle", "pinned-old"),
            IdleSessionBudget.pressureVictims(entries, maxBytes = 100, frozenIds = setOf("pinned-old", "active")),
        )
    }
}
