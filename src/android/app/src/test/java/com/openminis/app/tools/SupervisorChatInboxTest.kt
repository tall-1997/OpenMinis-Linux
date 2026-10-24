package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the supervisor session user inbox.
 *
 * The user is the third speaker: interjections land in an inbox and are
 * drained exactly once, in order, ahead of the next supervisor turn —
 * the supervisor decides how to fold them into its next instruction.
 */
class SupervisorChatInboxTest {

    @Test
    fun drainReturnsPushedMessagesInOrder() {
        val inbox = SupervisorChat.SupervisorInbox()
        inbox.push("use the faster fixture")
        inbox.push("also check the stall log")
        assertEquals(listOf("use the faster fixture", "also check the stall log"), inbox.drain())
    }

    @Test
    fun drainIsExactlyOnce() {
        val inbox = SupervisorChat.SupervisorInbox()
        inbox.push("first")
        inbox.drain()
        assertTrue(inbox.drain().isEmpty())
        assertEquals(0, inbox.size)
    }

    @Test
    fun blankPushesAreIgnored() {
        val inbox = SupervisorChat.SupervisorInbox()
        inbox.push("   ")
        inbox.push("")
        assertEquals(0, inbox.size)
        assertTrue(inbox.drain().isEmpty())
    }

    @Test
    fun sizeReflectsPendingCount() {
        val inbox = SupervisorChat.SupervisorInbox()
        inbox.push("one")
        inbox.push("two")
        assertEquals(2, inbox.size)
        inbox.drain()
        assertEquals(0, inbox.size)
    }

    @Test
    fun pushAfterDrainStartsFreshBatch() {
        val inbox = SupervisorChat.SupervisorInbox()
        inbox.push("first batch")
        inbox.drain()
        inbox.push("second batch")
        assertEquals(listOf("second batch"), inbox.drain())
    }
}
