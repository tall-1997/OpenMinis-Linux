package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatHistoryWindowTest {
    @Test
    fun `reveals loaded prefix before fetching database`() {
        val plan = ChatHistoryWindow.planOlderLoad(
            loadedVisible = 400,
            visibleCap = 200,
            loadedOffset = 4_162,
            step = 100,
        )
        assertEquals(300, plan.nextVisibleCap)
        assertNull(plan.fetchOffset)
        assertEquals(0, plan.fetchCount)
    }

    @Test
    fun `fetches persisted prefix after loaded window is visible`() {
        val plan = ChatHistoryWindow.planOlderLoad(
            loadedVisible = 400,
            visibleCap = 400,
            loadedOffset = 4_162,
            step = 100,
        )
        assertEquals(500, plan.nextVisibleCap)
        assertEquals(4_062, plan.fetchOffset)
        assertEquals(100, plan.fetchCount)
    }

    @Test
    fun `first partial page grows cap by exact row count`() {
        val plan = ChatHistoryWindow.planOlderLoad(
            loadedVisible = 400,
            visibleCap = 400,
            loadedOffset = 62,
            step = 100,
        )
        assertEquals(462, plan.nextVisibleCap)
        assertEquals(0, plan.fetchOffset)
        assertEquals(62, plan.fetchCount)
    }
}
