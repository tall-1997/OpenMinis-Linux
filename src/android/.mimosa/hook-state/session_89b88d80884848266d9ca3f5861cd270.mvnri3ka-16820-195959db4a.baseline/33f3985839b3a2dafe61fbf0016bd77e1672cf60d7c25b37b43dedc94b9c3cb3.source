package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the single scroll authority. The mode machine is the
 * whole fix for "读历史时新消息到达导致跳屏": content-driven followers are
 * gated on Pinned, and Reading is only ever left through explicit gestures
 * or deliberate jumps.
 */
class ChatScrollPolicyTest {

    @Test
    fun `starts pinned`() {
        val policy = ChatScrollPolicy()
        assertTrue(policy.isPinned)
        assertEquals(ScrollMode.Pinned, policy.current)
    }

    @Test
    fun `drag stop at bottom keeps pinned`() {
        val policy = ChatScrollPolicy()
        policy.onDragStop(atBottom = true, anchorKey = "user:u1", anchorOffset = 0)
        assertTrue(policy.isPinned)
    }

    @Test
    fun `drag stop off bottom lands reading with the drag anchor`() {
        val policy = ChatScrollPolicy()
        policy.onDragStop(atBottom = false, anchorKey = "mdblock:m1:b:0", anchorOffset = 128)
        val mode = policy.current
        assertTrue(mode is ScrollMode.Reading)
        mode as ScrollMode.Reading
        assertEquals("mdblock:m1:b:0", mode.anchorKey)
        assertEquals(128, mode.anchorOffset)
    }

    @Test
    fun `content scroll is allowed only while pinned`() {
        val policy = ChatScrollPolicy()
        assertTrue(policy.allowsContentScroll())
        policy.landReading("user:u1", 0)
        assertFalse(policy.allowsContentScroll())
    }

    @Test
    fun `fling re-arm flips pinned to reading`() {
        val policy = ChatScrollPolicy()
        // Finger lift happened at the bottom (Stop said pinned), then the
        // fling carried the viewport away — the settle edge re-arms.
        policy.onDragStop(atBottom = true, anchorKey = null, anchorOffset = 0)
        policy.rearmIfDraggedAway("user:u2", 64)
        assertTrue(policy.current is ScrollMode.Reading)
    }

    @Test
    fun `re-arm never overrides an explicit reading anchor`() {
        val policy = ChatScrollPolicy()
        policy.landReading("user:u1", 0)
        policy.rearmIfDraggedAway("user:u9", 7)
        val mode = policy.current
        mode as ScrollMode.Reading
        // landReading set the anchor; the re-arm must not clobber it.
        assertEquals("user:u1", mode.anchorKey)
    }

    @Test
    fun `pin returns to following`() {
        val policy = ChatScrollPolicy()
        policy.landReading("user:u1", 0)
        policy.pin()
        assertTrue(policy.isPinned)
    }

    @Test
    fun `reset starts a new session pinned`() {
        val policy = ChatScrollPolicy()
        policy.landReading("user:u1", 0)
        policy.reset()
        assertTrue(policy.isPinned)
    }
}
