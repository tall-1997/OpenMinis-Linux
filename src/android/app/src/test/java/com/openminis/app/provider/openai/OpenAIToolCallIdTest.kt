package com.openminis.app.provider.openai

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-toolcall-missing-id] One tool_call keeps one identity for its whole
 * delta stream: Chat Completions sends `id` only on the first delta, so an
 * id-less later delta must keep the captured id instead of deriving a
 * second one, and a started call must never be renamed. The derived
 * fallback is reserved for relays that never send an id at all.
 */
class OpenAIToolCallIdTest {

    @Test
    fun `first delta with real id captures it`() {
        assertEquals("call_abc123", resolveToolCallId(currentId = "", deltaId = "call_abc123", index = 0, started = false))
    }

    @Test
    fun `id-less later delta keeps the captured real id`() {
        // The regression: re-deriving here produced "tool-call-0", splitting
        // ToolUseStart (real id) from ToolInputDelta/ToolCallComplete (derived
        // id) — block lookup missed and tool input persisted as {}.
        assertEquals("call_abc123", resolveToolCallId(currentId = "call_abc123", deltaId = "", index = 0, started = true))
    }

    @Test
    fun `relay without any id derives a stable one on first delta`() {
        assertEquals("tool-call-0", resolveToolCallId(currentId = "", deltaId = "", index = 0, started = false))
    }

    @Test
    fun `derived id stays stable on later id-less deltas`() {
        assertEquals("tool-call-2", resolveToolCallId(currentId = "tool-call-2", deltaId = "", index = 2, started = true))
    }

    @Test
    fun `later non-empty id wins before start like pre-regression`() {
        assertEquals("call_second", resolveToolCallId(currentId = "call_first", deltaId = "call_second", index = 1, started = false))
    }

    @Test
    fun `started call is never renamed even by a late real id`() {
        // Name-first relays: delta 1 derives + starts under tool-call-0, a
        // late real id must not split the identity mid-stream.
        assertEquals("tool-call-0", resolveToolCallId(currentId = "tool-call-0", deltaId = "call_late", index = 0, started = true))
    }

    @Test
    fun `parallel id-less calls derive distinct stable ids`() {
        assertEquals("tool-call-0", resolveToolCallId("", "", 0, started = false))
        assertEquals("tool-call-1", resolveToolCallId("", "", 1, started = false))
    }
}
