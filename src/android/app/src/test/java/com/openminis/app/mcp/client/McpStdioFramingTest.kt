package com.openminis.app.mcp.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * [T-mcp-stdio-frames] Contract tests for the stdio framing layer — the port
 * of pi `mcp/src/transports/lines.ts`: chunk assembly, the message cap on
 * complete lines AND on accumulated bytes, CRLF tolerance, blank-line
 * skipping, the bounded stderr tail and the incomplete-message report at EOF.
 *
 * Assertions are structural (field reads, never JSON string compares) so the
 * tests cannot depend on org.json's HashMap iteration order.
 */
class McpStdioFramingTest {

    private class Recorder {
        val messages = mutableListOf<JSONObject>()
        val errors = mutableListOf<String>()
    }

    private fun reader(
        maxMessageBytes: Int = 16,
        maxStderrBytes: Int = 64,
    ): Pair<McpJsonRpcLineReader, Recorder> {
        val rec = Recorder()
        val reader = McpJsonRpcLineReader(
            maxMessageBytes = maxMessageBytes,
            maxStderrBytes = maxStderrBytes,
            onMessage = { obj -> rec.messages.add(obj) },
            onError = { message -> rec.errors.add(message) },
        )
        return reader to rec
    }

    private fun feed(reader: McpJsonRpcLineReader, text: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        reader.readStdout(bytes, bytes.size)
    }

    @Test
    fun `one complete line in one chunk is one message`() {
        val (reader, rec) = reader(maxMessageBytes = 64)
        feed(reader, "{\"id\":1,\"result\":{}}\n")
        assertEquals(1, rec.messages.size)
        assertEquals(1, rec.messages[0].getInt("id"))
        assertEquals(0, rec.errors.size)
    }

    @Test
    fun `a line split across chunks is assembled exactly once`() {
        val (reader, rec) = reader(maxMessageBytes = 64)
        val bytes = "{\"id\":2,\"result\":{\"tools\":[]}}\n".toByteArray(StandardCharsets.UTF_8)
        // Deliberate byte-level splits, the way a real pipe delivers them.
        var offset = 0
        for (end in listOf(3, 11, bytes.size)) {
            val chunk = bytes.copyOfRange(offset, end)
            reader.readStdout(chunk, chunk.size)
            offset = end
        }
        assertEquals(1, rec.messages.size)
        assertEquals(2, rec.messages[0].getInt("id"))
        assertEquals(0, rec.messages[0].getJSONObject("result").getJSONArray("tools").length())
        assertEquals(0, rec.errors.size)
    }

    @Test
    fun `multiple lines in one chunk produce multiple messages`() {
        val (reader, rec) = reader(maxMessageBytes = 64)
        feed(reader, "{\"a\":1}\n{\"b\":2}\n")
        assertEquals(2, rec.messages.size)
        assertEquals(1, rec.messages[0].getInt("a"))
        assertEquals(2, rec.messages[1].getInt("b"))
    }

    @Test
    fun `crlf writers are tolerated and blank lines skipped`() {
        val (reader, rec) = reader(maxMessageBytes = 64)
        feed(reader, "\r\n{\"a\":1}\r\n\n")
        assertEquals(1, rec.messages.size)
        assertEquals(1, rec.messages[0].getInt("a"))
        assertEquals(0, rec.errors.size)
    }

    @Test
    fun `a complete line over the cap is dropped, the connection survives`() {
        val (reader, rec) = reader(maxMessageBytes = 16)
        feed(reader, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\n") // 32 bytes, cap 16
        assertEquals(0, rec.messages.size)
        assertEquals(listOf("MCP stdio message exceeds 16 bytes"), rec.errors)
        // The next message still arrives — pi drops the frame, not the stream.
        feed(reader, "{\"ok\":1}\n")
        assertEquals(1, rec.messages.size)
        assertEquals(1, rec.messages[0].getInt("ok"))
    }

    @Test
    fun `an accumulated message over the cap is dropped at the running total`() {
        val (reader, rec) = reader(maxMessageBytes = 16)
        // 10 bytes with no newline, then 10 more: 20 > 16 → dropped BEFORE the
        // newline ever arrives (the old readLine() path grew unbounded here).
        val chunk = "aaaaaaaaaa".toByteArray(StandardCharsets.UTF_8)
        reader.readStdout(chunk, chunk.size)
        assertEquals(0, rec.errors.size)
        reader.readStdout(chunk, chunk.size)
        assertEquals(listOf("MCP stdio message exceeds 16 bytes"), rec.errors)
        // Framing recovers: the next complete line is delivered.
        feed(reader, "{\"ok\":1}\n")
        assertEquals(1, rec.messages.size)
    }

    @Test
    fun `stderr tail is bounded to the cap`() {
        val (reader, _) = reader(maxStderrBytes = 10)
        val chunk = "abcdefghijklmnopqrstuvwxyz".toByteArray(StandardCharsets.UTF_8)
        reader.readStderr(chunk, chunk.size)
        reader.readStderr(chunk, chunk.size)
        assertEquals(10, reader.stderrSnapshot().length)
        assertTrue(reader.stderrSnapshot().endsWith("yz"))
    }

    @Test
    fun `eof with an incomplete message reports it`() {
        val (reader, rec) = reader(maxMessageBytes = 64)
        feed(reader, "{\"partial\":")
        reader.end()
        assertEquals(listOf("MCP stdio server closed with an incomplete JSON-RPC message"), rec.errors)
        assertEquals(0, rec.messages.size)
    }

    @Test
    fun `eof on a clean stream is silent`() {
        val (reader, rec) = reader(maxMessageBytes = 64)
        feed(reader, "{\"a\":1}\n")
        reader.end()
        assertEquals(0, rec.errors.size)
    }

    @Test
    fun `unparseable lines report an error and the stream continues`() {
        val (reader, rec) = reader(maxMessageBytes = 64)
        feed(reader, "not json at all\n{\"ok\":1}\n")
        assertEquals(1, rec.messages.size)
        assertEquals(1, rec.errors.size)
        assertTrue(rec.errors.single().startsWith("MCP stdio message is not valid JSON:"))
    }
}
