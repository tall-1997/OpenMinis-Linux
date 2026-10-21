package com.openminis.app.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 悬浮胶囊必须完整落在屏幕内：不进状态栏，不进横屏手势条。
 */
class OverlayBoundsTest {

    private val screen = OverlayBounds.Screen(width = 1080, height = 2400, topInset = 80, bottomInset = 60)

    @Test
    fun `dragged into the status bar clamps below it`() {
        val (x, y) = OverlayBounds.clamp(500, 0, capsuleWidth = 400, capsuleHeight = 120, screen)
        assertEquals(80, y)
    }

    @Test
    fun `dragged past the bottom clears the gesture bar`() {
        val (_, y) = OverlayBounds.clamp(500, 9999, capsuleWidth = 400, capsuleHeight = 120, screen)
        assertEquals(2400 - 60 - 120, y)
    }

    @Test
    fun `dragged past the right edge stays fully visible`() {
        val (x, _) = OverlayBounds.clamp(9999, 500, capsuleWidth = 400, capsuleHeight = 120, screen)
        assertEquals(1080 - 400, x)
    }

    @Test
    fun `landscape stores its position separately from portrait`() {
        val store = OverlayBounds.positions(portrait = 10 to 20, landscape = 30 to 40)
        assertEquals(10 to 20, store.forOrientation(portrait = true))
        assertEquals(30 to 40, store.forOrientation(portrait = false))
    }
}
