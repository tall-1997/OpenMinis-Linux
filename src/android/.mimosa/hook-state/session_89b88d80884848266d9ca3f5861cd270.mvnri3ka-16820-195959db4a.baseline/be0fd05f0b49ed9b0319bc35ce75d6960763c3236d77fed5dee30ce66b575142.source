package com.openminis.app.browser

import org.junit.Assert.*
import org.junit.Test

class ScreenshotBudgetTest {
    @Test fun ordinaryViewportIsUnchanged() {
        assertEquals(1080 to 1920, ScreenshotBudget.size(1080, 1920))
    }

    @Test fun longWideAndOverflowSizedPagesStayWithinNativeBudget() {
        for ((w, h) in listOf(1080 to 32768, 32768 to 1080, Int.MAX_VALUE to Int.MAX_VALUE, 1 to Int.MAX_VALUE)) {
            val (bw, bh) = ScreenshotBudget.size(w, h)
            assertTrue(bw > 0 && bh > 0)
            assertTrue(bw <= w && bh <= h)
            assertTrue(bw <= ScreenshotBudget.MAX_EDGE && bh <= ScreenshotBudget.MAX_EDGE)
            assertTrue(bw.toLong() * bh <= ScreenshotBudget.MAX_PIXELS)
        }
    }
}
