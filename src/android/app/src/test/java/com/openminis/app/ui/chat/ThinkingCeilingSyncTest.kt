package com.openminis.app.ui.chat

import com.openminis.app.data.model.ThinkingLevel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-thinking-ceiling-sync] The session level must never exceed the active
 * model's ceiling — pure core of the ChatViewModel ceiling-sync flow
 * (config/entry changes re-clamp the session state and persist the result).
 */
class ThinkingCeilingSyncTest {

    @Test
    fun `level within ceiling passes through`() {
        assertEquals(ThinkingLevel.MEDIUM, clampThinkingToCeiling(ThinkingLevel.MEDIUM, ThinkingLevel.HIGH))
        assertEquals(ThinkingLevel.OFF, clampThinkingToCeiling(ThinkingLevel.OFF, ThinkingLevel.OFF))
        assertEquals(ThinkingLevel.ULTRA, clampThinkingToCeiling(ThinkingLevel.ULTRA, ThinkingLevel.ULTRA))
    }

    @Test
    fun `level above ceiling is clamped down`() {
        assertEquals(ThinkingLevel.HIGH, clampThinkingToCeiling(ThinkingLevel.ULTRA, ThinkingLevel.HIGH))
        assertEquals(ThinkingLevel.OFF, clampThinkingToCeiling(ThinkingLevel.HIGH, ThinkingLevel.OFF))
    }

    @Test
    fun `session OFF stays OFF under any ceiling`() {
        assertEquals(ThinkingLevel.OFF, clampThinkingToCeiling(ThinkingLevel.OFF, ThinkingLevel.ULTRA))
    }
}
