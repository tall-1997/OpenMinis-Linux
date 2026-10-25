package com.openminis.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InterceptFeedbackTest {

    @Before
    fun setUp() {
        InterceptFeedback.clear()
    }

    @Test
    fun publishesDeniedAndRejectedThenDismiss() {
        InterceptFeedback.publishDenied("shell_execute", "blocked")
        InterceptFeedback.publishRejected("file_write", "User rejected or timed out")
        val events = InterceptFeedback.events.value
        assertEquals(2, events.size)
        assertEquals(InterceptEvent.Kind.DENIED, events[0].kind)
        assertEquals("shell_execute", events[0].toolName)
        assertEquals(InterceptEvent.Kind.REJECTED, events[1].kind)
        InterceptFeedback.dismiss(events[0].id)
        assertEquals(1, InterceptFeedback.events.value.size)
        assertEquals("file_write", InterceptFeedback.events.value.single().toolName)
    }

    @Test
    fun capsAtEightEvents() {
        repeat(12) { InterceptFeedback.publishDenied("t$it", "r") }
        assertEquals(8, InterceptFeedback.events.value.size)
        assertTrue(InterceptFeedback.events.value.first().toolName.endsWith("4"))
    }
}
