package com.openminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流式跟随的门控语义：跟随与否由 [ChatScrollPolicy] 的模式（Pinned/Reading）
 * 决定，视口坐标不再参与判定。
 *
 * 关键背景（T-android-send-follow-race）：reverseLayout 的 LazyList 在
 * index 0 头部插入新 flat item 时会把锚点保持在上一个 item，视口被"推离"
 * index 0。Pinned 下这种推离必须跟随，否则新任务开始后视口永久钉在上一轮
 * 结束位置（旧条件 firstVisibleIndex==0 把推离误判为阅读）。
 *
 * 真实的阅读保护由模式转换承担：离开底部的每一次手势终点（DragStop 离底、
 * fling 离底的停止边缘 rearm）都会把模式转成 Reading（isPinned=false），
 * "停在最新一条中部（offset>0）"必然经过手势，因此必然已是 Reading。
 */
class ScrollAnchorTest {

    @Test
    fun `pinned viewport displaced by a head insertion still follows`() {
        // 新 flat item 插入把锚点推到旧 item（firstIdx=1..N，off 任意）——
        // 这不是阅读，是插入的几何副作用，Pinned 必须拉回。
        assertTrue(
            ScrollAnchor.shouldFollow(
                isPinned = true, firstVisibleIndex = 1, firstVisibleOffset = 0,
                isScrolling = false, withinInterruptWindow = false,
            ),
        )
        assertTrue(
            ScrollAnchor.shouldFollow(
                isPinned = true, firstVisibleIndex = 5, firstVisibleOffset = 400,
                isScrolling = false, withinInterruptWindow = false,
            ),
        )
    }

    @Test
    fun `pinned viewport anchored at the bottom follows`() {
        assertTrue(
            ScrollAnchor.shouldFollow(
                isPinned = true, firstVisibleIndex = 0, firstVisibleOffset = 0,
                isScrolling = false, withinInterruptWindow = false,
            ),
        )
    }

    @Test
    fun `reading mode never follows regardless of coordinates`() {
        // 真实的阅读状态必然已是 Reading（isPinned=false）——无论是停在
        // 上一条回复里还是停在最新一条的中部。
        assertFalse(
            ScrollAnchor.shouldFollow(
                isPinned = false, firstVisibleIndex = 3, firstVisibleOffset = 0,
                isScrolling = false, withinInterruptWindow = false,
            ),
        )
        assertFalse(
            ScrollAnchor.shouldFollow(
                isPinned = false, firstVisibleIndex = 0, firstVisibleOffset = 400,
                isScrolling = false, withinInterruptWindow = false,
            ),
        )
    }

    @Test
    fun `active scrolling and the interrupt window suppress following`() {
        assertFalse(ScrollAnchor.shouldFollow(true, 0, 0, isScrolling = true, withinInterruptWindow = false))
        assertFalse(ScrollAnchor.shouldFollow(true, 0, 0, isScrolling = false, withinInterruptWindow = true))
    }
}
