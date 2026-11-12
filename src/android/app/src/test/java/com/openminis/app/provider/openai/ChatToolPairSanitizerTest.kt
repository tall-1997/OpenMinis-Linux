package com.openminis.app.provider.openai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GH#222/#293/#352/#382] Wire-level tool pairing repair. These shapes are
 * exactly what OpenAI-compatible providers reject with 400.
 */
class ChatToolPairSanitizerTest {

    private fun assistant(vararg ids: String): JSONObject {
        val calls = JSONArray()
        for (id in ids) {
            calls.put(
                JSONObject()
                    .put("id", id)
                    .put("type", "function")
                    .put("function", JSONObject().put("name", "shell_execute").put("arguments", "{}")),
            )
        }
        return JSONObject().put("role", "assistant").put("content", "").put("tool_calls", calls)
    }

    private fun tool(id: String, content: String = "ok"): JSONObject =
        JSONObject().put("role", "tool").put("tool_call_id", id).put("content", content)

    private fun user(text: String = "hi"): JSONObject =
        JSONObject().put("role", "user").put("content", text)

    private fun roles(messages: JSONArray): List<String> =
        (0 until messages.length()).map { messages.getJSONObject(it).optString("role") }

    @Test
    fun `well-formed pair is unchanged`() {
        val input = JSONArray().put(user()).put(assistant("a")).put(tool("a")).put(assistant("b")).put(tool("b"))
        val report = ChatToolPairSanitizer.sanitize(input)
        assertFalse(report.changed)
        assertEquals(5, report.messages.length())
    }

    @Test
    fun `orphan tool reply is dropped`() {
        // The assistant tool_calls block was compacted away; only its reply
        // survived — the exact #293 "no preceding tool_calls" 400.
        val input = JSONArray().put(user()).put(tool("gone")).put(user("again"))
        val report = ChatToolPairSanitizer.sanitize(input)
        assertEquals(1, report.droppedOrphanResults)
        assertEquals(listOf("user", "user"), roles(report.messages))
    }

    @Test
    fun `dangling tool call gets a synthetic error reply`() {
        // #352: the assistant asked for a tool whose result never landed.
        val input = JSONArray().put(user()).put(assistant("a")).put(user("next turn"))
        val report = ChatToolPairSanitizer.sanitize(input)
        assertEquals(1, report.insertedMissingResults)
        assertEquals(listOf("user", "assistant", "tool", "user"), roles(report.messages))
        assertEquals(
            ChatToolPairSanitizer.MISSING_RESULT_TEXT,
            report.messages.getJSONObject(2).getString("content"),
        )
    }

    @Test
    fun `duplicate tool reply is dropped after the first is consumed`() {
        val input = JSONArray()
            .put(user())
            .put(assistant("a"))
            .put(tool("a"))
            .put(tool("a", "duplicate"))
        val report = ChatToolPairSanitizer.sanitize(input)
        assertEquals(1, report.droppedDuplicateResults)
        assertEquals(3, report.messages.length())
        assertEquals("ok", report.messages.getJSONObject(2).getString("content"))
    }

    @Test
    fun `parallel calls keep only their own replies and close the missing one`() {
        val input = JSONArray()
            .put(user())
            .put(assistant("a", "b"))
            .put(tool("a"))
            .put(assistant("c"))
            .put(tool("c"))
        val report = ChatToolPairSanitizer.sanitize(input)
        assertEquals(1, report.insertedMissingResults)
        // a, b(synthetic), then c and its reply.
        assertEquals(
            listOf("user", "assistant", "tool", "tool", "assistant", "tool"),
            roles(report.messages),
        )
        assertEquals("b", report.messages.getJSONObject(3).getString("tool_call_id"))
    }

    @Test
    fun `image carrier between tool replies does not break the block`() {
        // GH#293/#352: the converter used to interleave a user image message
        // between two tool replies; the caption+image must be emitted after
        // both replies, so the sanitizer sees a contiguous block.
        val input = JSONArray()
            .put(user())
            .put(assistant("a", "b"))
            .put(tool("a"))
            .put(tool("b"))
            .put(user("[Image returned by read_image]"))
        val report = ChatToolPairSanitizer.sanitize(input)
        assertFalse(report.changed)
        assertNotNull(report.messages.getJSONObject(4).optString("content"))
    }

    @Test
    fun `malformed call without id is dropped from tool_calls`() {
        val call = JSONObject()
            .put("id", "")
            .put("type", "function")
            .put("function", JSONObject().put("name", "shell_execute").put("arguments", "{}"))
        val input = JSONArray().put(user()).put(
            JSONObject().put("role", "assistant").put("content", "")
                .put("tool_calls", JSONArray().put(call)),
        )
        val report = ChatToolPairSanitizer.sanitize(input)
        assertEquals(1, report.droppedMalformedCalls)
        assertFalse(report.messages.getJSONObject(1).has("tool_calls"))
    }

    @Test
    fun `user text between tool replies closes the block`() {
        val input = JSONArray()
            .put(user())
            .put(assistant("a"))
            .put(tool("a"))
            .put(user("captioned image"))
            .put(tool("late"))
        val report = ChatToolPairSanitizer.sanitize(input)
        // The late reply has no pending call → orphan; the block was already
        // closed after `a` was answered.
        assertEquals(1, report.droppedOrphanResults)
        assertTrue(report.changed)
    }
}
