package com.openminis.app.provider.openai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GH#113] A compatibility gateway that streams the tool call inside
 * `delta.content` must not leak that JSON into the assistant reply, while an
 * ordinary JSON answer whose `name` is not an advertised tool must pass through
 * byte-for-byte.
 */
class ContentToolCallSnifferTest {

    private val tools = setOf("shell_execute", "file_read")

    private fun sniffer() = ContentToolCallSniffer(tools)

    private fun texts(emissions: List<ContentToolCallSniffer.Emission>): String =
        emissions.filterIsInstance<ContentToolCallSniffer.Emission.Text>().joinToString("") { it.text }

    private fun calls(emissions: List<ContentToolCallSniffer.Emission>) =
        emissions.filterIsInstance<ContentToolCallSniffer.Emission.Call>()

    @Test
    fun `content-embedded call is recovered instead of leaking as text`() {
        val s = sniffer()
        val deltas = listOf(
            """{"name":"shell_exe""",
            """cute","arguments":{"command":"ls"}}""",
        )
        val out = deltas.flatMap { s.feed(it) }
        val call = calls(out).single()
        assertEquals("shell_execute", call.name)
        assertEquals("ls", call.args.optString("command", ""))
        assertEquals("", texts(out))
    }

    @Test
    fun `openai tool_calls envelope inside content is recovered`() {
        val s = sniffer()
        val out = s.feed("""{"tool_calls":[{"function":{"name":"file_read","arguments":{"path":"/tmp/a"}}}]}""")
        val call = calls(out).single()
        assertEquals("file_read", call.name)
        assertEquals("/tmp/a", call.args.optString("path", ""))
    }

    @Test
    fun `ordinary json answer with a non-tool name is preserved`() {
        val s = sniffer()
        val payload = """{"name":"Alice","age":30}"""
        val out = s.feed(payload)
        assertEquals(payload, texts(out))
        assertTrue(calls(out).isEmpty())
    }

    @Test
    fun `prose is streamed immediately and never buffered`() {
        val s = sniffer()
        assertEquals("Sure, ", texts(s.feed("Sure, ")))
        assertEquals("here is the answer", texts(s.feed("here is the answer")))
        assertEquals("", texts(s.finish()))
    }

    @Test
    fun `truncated envelope is released at stream end`() {
        val s = sniffer()
        assertTrue(s.feed("""{"name":"shell_exec""").isEmpty())
        assertEquals("""{"name":"shell_exec""", texts(s.finish()))
    }

    @Test
    fun `json fragment mid-reply is not sniffed`() {
        val s = sniffer()
        assertEquals("Result: ", texts(s.feed("Result: ")))
        val mid = s.feed("""{"name":"shell_execute","arguments":{}}""")
        // Already streaming text → the fragment must be shown verbatim.
        assertEquals("""{"name":"shell_execute","arguments":{}}""", texts(mid))
        assertTrue(calls(mid).isEmpty())
    }

    @Test
    fun `no advertised tools means nothing is sniffed`() {
        val s = ContentToolCallSniffer(emptySet())
        val payload = """{"name":"shell_execute","arguments":{"command":"ls"}}"""
        assertEquals(payload, texts(s.feed(payload)))
    }

    @Test
    fun `bare call with stringified arguments parses`() {
        val s = sniffer()
        val out = s.feed("""{"name":"shell_execute","arguments":"{\"command\":\"pwd\"}"}""")
        assertEquals("pwd", calls(out).single().args.optString("command", ""))
    }

    @Test
    fun `non-tool json is flushed as text`() {
        val s = sniffer()
        val out = s.feed("""{"name":42}""")
        assertEquals("""{"name":42}""", texts(out))
    }

    @Test
    fun `name must match an advertised tool exactly`() {
        val s = sniffer()
        val payload = """{"name":"shell_exec","arguments":{}}"""
        assertEquals(payload, texts(s.feed(payload)))
    }
}
