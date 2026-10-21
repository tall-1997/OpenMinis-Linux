package com.openminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 新一轮回复开始时，正在阅读上一条回复的视口不能被拽到底部。
 * 只有视口确实锚在最新一条（index 0 且贴底）时才跟随。
 */
class ScrollAnchorTest {

    @Test
    fun `reading an earlier reply does not follow`() {
        val follow = ScrollAnchor.shouldFollow(
            isPinned = true, firstVisibleIndex = 3, firstVisibleOffset = 0,
            isScrolling = false, withinInterruptWindow = false,
        )
        assertFalse(follow)
    }

    @Test
    fun `paused mid latest reply does not jump`() {
        // index 0 但偏移不为 0：用户停在最新一条的中部阅读，不能跳到底。
        val follow = ScrollAnchor.shouldFollow(
            isPinned = true, firstVisibleIndex = 0, firstVisibleOffset = 400,
            isScrolling = false, withinInterruptWindow = false,
        )
        assertFalse(follow)
    }

    @Test
    fun `viewport anchored at the bottom does follow`() {
        val follow = ScrollAnchor.shouldFollow(
            isPinned = true, firstVisibleIndex = 0, firstVisibleOffset = 0,
            isScrolling = false, withinInterruptWindow = false,
        )
        assertTrue(follow)
    }

    @Test
    fun `unpinned reading never follows`() {
        val follow = ScrollAnchor.shouldFollow(
            isPinned = false, firstVisibleIndex = 0, firstVisibleOffset = 0,
            isScrolling = false, withinInterruptWindow = false,
        )
        assertFalse(follow)
    }

    @Test
    fun `active scrolling and the interrupt window suppress following`() {
        assertFalse(ScrollAnchor.shouldFollow(true, 0, 0, isScrolling = true, withinInterruptWindow = false))
        assertFalse(ScrollAnchor.shouldFollow(true, 0, 0, isScrolling = false, withinInterruptWindow = true))
    }
}
