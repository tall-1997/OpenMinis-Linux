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

    @Test
    fun `repeated older pages never exceed fixed capacity`() {
        var start = 4_162
        var count = 400
        repeat(50) {
            val move = ChatHistoryWindow.moveOlder(
                loadedStart = start,
                loadedCount = count,
                fetchStart = (start - 100).coerceAtLeast(0),
                fetchCount = minOf(100, start),
                capacity = 400,
            )
            start = move.retainedStart
            count = move.retainedCount
            assert(count <= 400)
        }
        assertEquals(0, start)
        assertEquals(400, count)
    }

    @Test
    fun `appending at the tail evicts the oldest loaded rows`() {
        val move = ChatHistoryWindow.appendTail(
            totalBefore = 4_562,
            loadedStart = 4_162,
            loadedCount = 400,
            appended = 3,
            capacity = 400,
        )
        assertEquals(4_165, move.retainedStart)
        assertEquals(400, move.retainedCount)
    }
}
