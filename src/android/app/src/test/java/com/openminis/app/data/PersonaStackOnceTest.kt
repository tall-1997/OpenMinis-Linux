package com.openminis.app.data

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Personality stacks once on the identity section. It must not be copied onto
 * the user's latest turn or repeated as a second binding reminder: those two
 * copies made the persona outrank the current task.
 */
class PersonaStackOnceTest {

    @Test
    fun `the request history is not rewritten to carry the persona`() {
        val history = ProductionSources.read("ui/chat/ChatViewModel.kt")
        assertFalse(history.contains("applyHistorySteering("))
        assertFalse(history.contains("personaHistorySteering"))
    }

    @Test
    fun `the dynamic tail does not repeat a personality reminder`() {
        val prompt = ProductionSources.read("ui/chat/ChatViewModelPromptExt.kt")
        assertFalse(prompt.contains("PERSONALITY_REMINDER"))
        assertTrue(prompt.contains("personalityReminder = null"))
    }

    @Test
    fun `the persona does not outrank the latest user message`() {
        val soul = ProductionSources.read("agent/SoulStore.kt")
        assertFalse(soul.contains("do not drop it when a later user message"))
        assertTrue(soul.contains("does not replace them, the tools, or the user's latest message"))
    }
}
