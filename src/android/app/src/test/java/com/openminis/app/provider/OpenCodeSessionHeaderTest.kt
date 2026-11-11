package com.openminis.app.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-opencode-custom-endpoint-session GH#347] The custom-endpoint session
 * header is OPT-IN per provider.
 *
 * An earlier revision sent it for any non-blank custom base URL, which leaked a
 * stable per-conversation identifier to every third-party relay a user
 * configured. These tests pin the privacy default (off), the explicit opt-in
 * path (both header spellings), and that the official `*.opencode.ai` lane keeps
 * sending automatically because the service requires it.
 */
class OpenCodeSessionHeaderTest {

    private val sid = "123e4567-e89b-12d3-a456-426614174000"

    @Test
    fun `custom endpoint gets nothing unless the provider opts in`() {
        assertTrue(
            OpenCodeSessionHeader
                .customEndpointHeadersFor("https://relay.example.com/v1", sid)
                .isEmpty(),
        )
        assertTrue(
            OpenCodeSessionHeader
                .customEndpointHeadersFor("https://relay.example.com/v1", sid, enabled = false)
                .isEmpty(),
        )
    }

    @Test
    fun `opted-in custom endpoint receives both session header spellings`() {
        val headers = OpenCodeSessionHeader.customEndpointHeadersFor(
            "https://relay.example.com/v1",
            sid,
            enabled = true,
        )
        assertEquals(sid, headers[OpenCodeSessionHeader.HEADER])
        // ZenFreeLaneGate sends both spellings so relays keyed on either route.
        assertEquals(sid, headers["x-opencode-session-id"])
    }

    @Test
    fun `official endpoint receives no custom-endpoint header`() {
        // No custom base URL -> the official path; `headersFor` handles it.
        assertTrue(OpenCodeSessionHeader.customEndpointHeadersFor(null, sid, enabled = true).isEmpty())
        assertTrue(OpenCodeSessionHeader.customEndpointHeadersFor("   ", sid, enabled = true).isEmpty())
    }

    @Test
    fun `draft session ids are refused even when opted in`() {
        assertTrue(
            OpenCodeSessionHeader.customEndpointHeadersFor(
                "https://relay.example.com/v1",
                "__new__draft",
                enabled = true,
            ).isEmpty(),
        )
    }

    @Test
    fun `the official opencode host still auto-sends`() {
        assertEquals(
            sid,
            OpenCodeSessionHeader.headersFor("https://opencode.ai/zen/go/v1", sid)[OpenCodeSessionHeader.HEADER],
        )
        // A lookalike host stays excluded, and a plain relay never auto-sends.
        assertTrue(OpenCodeSessionHeader.headersFor("https://opencode.ai.example.com/v1", sid).isEmpty())
        assertTrue(OpenCodeSessionHeader.headersFor("https://relay.example.com/v1", sid).isEmpty())
    }
}
