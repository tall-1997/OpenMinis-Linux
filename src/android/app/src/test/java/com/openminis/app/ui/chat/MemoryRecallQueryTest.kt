package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemoryRecallQueryTest {

    @Test
    fun `a real user sentence is the query`() {
        assertEquals("pull the panel repo", memoryRecallQuery("pull the panel repo"))
    }

    @Test
    fun `a host reminder is not a query`() {
        assertNull(
            memoryRecallQuery(
                "<system-reminder>The previous response was empty. Continue.</system-reminder>",
            ),
        )
    }

    @Test
    fun `reminder text is stripped before the user words are kept`() {
        assertEquals(
            "continue the merge",
            memoryRecallQuery(
                "<system-reminder>nudge</system-reminder>\n\ncontinue the merge",
            ),
        )
    }
}
