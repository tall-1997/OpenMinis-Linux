package com.openminis.app.ui.chat

import com.openminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemoryRecallQueryTest {

    @Test
    fun `a file already in the system prompt is not recalled again`() {
        val hits = listOf("2026-11-12.md" to "today", "2026-11-01.md" to "old")
        val kept = recallHitsNotAlreadyResident(hits, setOf("2026-11-12.md")) { it.first }
        assertEquals(listOf("2026-11-01.md" to "old"), kept)
    }


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
    fun `semantic cache identity follows the latest real row and never the previous row of a draft`() {
        val user = LLMMessage(LLMMessage.Role.USER, "autumn harvest", dbMessageId = "old-user")
        val followUp = user.copy(content = "continue", dbMessageId = "next-user")
        val host = user.copy(content = "unmarked caller nudge", dbMessageId = "host", isHostGenerated = true)
        val past = listOf(user, followUp, host)
        assertEquals("autumn harvest", memoryRecallQueryForHistory(past))
        assertEquals("next-user", memoryRecallTurnIdForHistory(past))
        val draft = followUp.copy(dbMessageId = null)
        assertEquals("autumn harvest", memoryRecallQueryForHistory(past + draft))
        assertNull(memoryRecallTurnIdForHistory(past + draft))
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
