package com.openminis.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HighRefreshRateTest {
    @Test
    fun `keeps the current resolution when a lower resolution is faster`() {
        val picked = pickHighestRefresh(
            listOf(
                RefreshMode(1, 1080, 2400, 60f),
                RefreshMode(2, 1080, 2400, 120f),
                RefreshMode(3, 720, 1600, 144f),
            ),
            width = 1080,
            height = 2400,
        )
        assertEquals(2, picked?.id)
        assertEquals(120f, picked?.refreshRate)
    }

    @Test
    fun `uses the fastest mode when no mode matches the current size`() {
        val picked = pickHighestRefresh(
            listOf(
                RefreshMode(1, 1080, 2400, 60f),
                RefreshMode(4, 1080, 2400, 90f),
            ),
            width = 720,
            height = 1600,
        )
        assertEquals(4, picked?.id)
    }

    @Test
    fun `empty mode list requests nothing`() {
        assertNull(pickHighestRefresh(emptyList(), 1080, 2400))
    }
}
