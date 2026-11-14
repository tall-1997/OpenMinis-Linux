package com.openminis.app.data

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Personality stacks once on the identity section OF THE CONVERSATION:
 * history rewriting is forbidden (those copies made the persona outrank the
 * current task). The dynamic tail is the sanctioned re-assertion surface —
 * a per-request system-prompt suffix that never touches user content
 * ([T-standing-contract-recency]); the tests below pin that split.
 */
class PersonaStackOnceTest {

    @Test
    fun `the request history is not rewritten to carry the persona`() {
        val history = ProductionSources.read("ui/chat/ChatViewModel.kt")
        assertFalse(history.contains("applyHistorySteering("))
        assertFalse(history.contains("personaHistorySteering"))
    }

    @Test
    fun `the dynamic tail re-asserts the contract without a hardcoded reminder`() {
        val prompt = ProductionSources.read("ui/chat/ChatViewModelPromptExt.kt")
        assertFalse(prompt.contains("PERSONALITY_REMINDER"))
        // [T-standing-contract-recency] The tail slot is wired to the
        // StandingContract block (persona + standing-rules authority,
        // rebuilt per request) — the old hard-wired null was the drift
        // window; any future nulling of the slot must go through a tagged
        // decision, not a silent literal.
        assertFalse(prompt.contains("personalityReminder = null"))
        assertTrue(prompt.contains("personalityReminder = StandingContract.block("))
        assertTrue(prompt.contains("internal object StandingContract"))
    }

    @Test
    fun `the persona does not outrank the latest user message`() {
        val soul = ProductionSources.read("agent/SoulStore.kt")
        assertFalse(soul.contains("do not drop it when a later user message"))
        assertTrue(soul.contains("does not replace them, the tools, or the user's latest message"))
    }
}
