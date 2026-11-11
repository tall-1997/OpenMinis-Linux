package com.openminis.app.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-opencode-custom-endpoint-session GH#347] Custom OpenAI-compatible
 * endpoints get the same per-conversation routing header as the built-in
 * OpenCode Go host; official endpoints and draft ids still get nothing.
 */
class OpenCodeSessionHeaderTest {

    private val sid = "123e4567-e89b-12d3-a456-426614174000"

    @Test
    fun `custom endpoint receives both session header spellings`() {
        val headers = OpenCodeSessionHeader.customEndpointHeadersFor(
            "https://relay.example.com/v1",
            sid,
        )
        assertEquals(sid, headers[OpenCodeSessionHeader.HEADER])
        // ZenFreeLaneGate sends both spellings so relays keyed on either route.
        assertEquals(sid, headers["x-opencode-session-id"])
    }

    @Test
    fun `official endpoint receives no session header`() {
        assertTrue(OpenCodeSessionHeader.customEndpointHeadersFor(null, sid).isEmpty())
        assertTrue(OpenCodeSessionHeader.customEndpointHeadersFor("   ", sid).isEmpty())
    }

    @Test
    fun `draft session ids are refused`() {
        assertTrue(
            OpenCodeSessionHeader.customEndpointHeadersFor(
                "https://relay.example.com/v1",
                "__new__draft",
            ).isEmpty(),
        )
    }
}
