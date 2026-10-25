package com.openminis.app.provider

import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.provider.openai.OpenAIProvider
import com.openminis.app.provider.openai.looksLikeSse
import com.openminis.app.provider.openai.peekPrefix
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.PushbackInputStream

/**
 * [T-stream-content-type-sniff] A relay that streams SSE under a
 * `Content-Type: application/json` header must still stream.
 *
 * The reported failure: `rawStreamMessage` decided "this is not SSE, so the
 * gateway ignored stream=true and returned one JSON object" from the
 * Content-Type header alone, then handed the SSE text to `JSONObject()`. The
 * tokenizer's first value is the bare word `data`, so the user saw
 *
 *     Value data of type java.lang.String cannot be converted to JSONObject
 *
 * escaping the agent loop as a raw JSONException. Measured 6/6 against a public
 * gateway: HTTP 200, `content-type: application/json`, body `data: {…}`.
 *
 * Header mislabelling is not exotic — any proxy that hides or rewrites the
 * upstream header produces it — so the decision is made from the bytes, with
 * the header kept only as a corroborating signal.
 */
class StreamContentTypeSniffTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: OpenAIProvider

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        provider = OpenAIProvider(
            apiKey = "test-key",
            model = LLMModel.gpt4oMini,
            basePath = server.url("/").toString().trimEnd('/'),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val sseBody = buildString {
        appendLine("data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}")
        appendLine()
        appendLine("data: {\"choices\":[{\"delta\":{\"content\":\" world\"}}]}")
        appendLine()
        appendLine("data: [DONE]")
        appendLine()
    }

    private fun stream() = runBlocking {
        provider.streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 1024).toList()
    }

    // ── end to end ─────────────────────────────────────────────────────

    @Test
    fun `SSE served as application-json still streams instead of throwing`() = runBlocking {
        server.enqueue(MockResponse().setBody(sseBody).setHeader("Content-Type", "application/json"))

        val chunks = stream()

        assertTrue("must emit Started", chunks.any { it is LLMStreamChunk.Started })
        val texts = chunks.filterIsInstance<LLMStreamChunk.Text>().map { it.text }
        assertEquals(listOf("Hello", " world"), texts)
        assertTrue("must emit Finished", chunks.any { it is LLMStreamChunk.Finished })
    }

    @Test
    fun `SSE served with no content-type at all still streams`() = runBlocking {
        // MockWebServer always sends a Content-Type, so overwrite with an empty
        // one to stand in for a proxy that strips it.
        server.enqueue(MockResponse().setBody(sseBody).setHeader("Content-Type", ""))

        val texts = stream().filterIsInstance<LLMStreamChunk.Text>().map { it.text }
        assertEquals(listOf("Hello", " world"), texts)
    }

    @Test
    fun `correctly labelled SSE is unchanged`() = runBlocking {
        server.enqueue(MockResponse().setBody(sseBody).setHeader("Content-Type", "text/event-stream"))

        val texts = stream().filterIsInstance<LLMStreamChunk.Text>().map { it.text }
        assertEquals(listOf("Hello", " world"), texts)
    }

    @Test
    fun `a real JSON body under application-json still takes the JSON branch`() = runBlocking {
        // This is why the branch exists at all: gateways that ignore stream=true
        // and answer with one Chat Completions object. Sniffing the body must
        // not break it.
        server.enqueue(
            MockResponse()
                .setBody("""{"choices":[{"message":{"content":"Hi"},"finish_reason":"stop"}],"usage":{"prompt_tokens":3,"completion_tokens":1}}""")
                .setHeader("Content-Type", "application/json"),
        )

        val chunks = stream()

        assertEquals(listOf("Hi"), chunks.filterIsInstance<LLMStreamChunk.Text>().map { it.text })
        assertEquals(1, chunks.filterIsInstance<LLMStreamChunk.Usage>().size)
        assertTrue(chunks.any { it is LLMStreamChunk.Finished })
    }

    @Test
    fun `an empty body reports a decoding error, not a raw JSONException`() = runBlocking {
        server.enqueue(MockResponse().setBody("").setHeader("Content-Type", "application/json"))

        val e = try {
            stream()
            null
        } catch (t: Throwable) {
            t
        }
        assertTrue("expected an LLMError, got $e", e is LLMError)
        assertTrue("expected DecodingError, got $e", e is LLMError.DecodingError)
        assertTrue(
            "message should name the mismatch, got: ${e?.message}",
            e?.message?.contains("expected a JSON object body") == true,
        )
    }

    // ── the pure classifier ────────────────────────────────────────────

    @Test
    fun `looksLikeSse recognises every SSE field name`() {
        for (line in listOf("data:", "event:", "id:", "retry:")) {
            assertTrue("$line must read as SSE", looksLikeSse("$line {}"))
        }
    }

    @Test
    fun `looksLikeSse recognises a comment keep-alive line`() {
        assertTrue(looksLikeSse(": ping"))
    }

    @Test
    fun `looksLikeSse tolerates leading blank lines`() {
        // Relays often flush a newline before the first event.
        assertTrue(looksLikeSse("\n\n  data: {}"))
        assertTrue(looksLikeSse("\r\ndata: {}"))
    }

    @Test
    fun `looksLikeSse rejects JSON and prose`() {
        assertFalse(looksLikeSse("{\"choices\":[]}"))
        assertFalse(looksLikeSse("[1,2,3]"))
        assertFalse(looksLikeSse(""))
        assertFalse(looksLikeSse("<html>gateway error</html>"))
        // A bare word that merely starts with the same letters is not a field.
        assertFalse(looksLikeSse("database dump"))
    }

    // ── the peek must not consume ──────────────────────────────────────

    @Test
    fun `peekPrefix pushes every byte back`() {
        val body = sseBody.toByteArray()
        val pb = PushbackInputStream(ByteArrayInputStream(body), 256)

        peekPrefix(pb)

        assertEquals("no byte may be lost", body.decodeToString(), pb.readBytes().decodeToString())
    }

    @Test
    fun `peekPrefix pushes every byte back for a JSON body too`() {
        val body = """{"choices":[{"message":{"content":"Hi"}}]}""".toByteArray()
        val pb = PushbackInputStream(ByteArrayInputStream(body), 256)

        peekPrefix(pb)

        assertEquals(body.decodeToString(), pb.readBytes().decodeToString())
    }

    @Test
    fun `a dribbling socket cannot fool the sniff`() {
        // TCP may hand over "dat" of "data: {…}". Classifying on that partial
        // read would wrongly conclude "not SSE", so peekPrefix keeps reading
        // until the prefix can actually be decided.
        val body = "data: {\"choices\":[]}\n\n".toByteArray()
        for (chunk in intArrayOf(1, 2, 3, 4, 7, 64)) {
            val pb = PushbackInputStream(DribbleStream(body, chunk), 256)
            assertTrue("chunk=$chunk must still classify as SSE", looksLikeSse(peekPrefix(pb)))
            assertEquals("chunk=$chunk lost bytes", body.decodeToString(), pb.readBytes().decodeToString())
        }
    }

    @Test
    fun `an empty stream peeks as empty and reads as empty`() {
        val pb = PushbackInputStream(ByteArrayInputStream(ByteArray(0)), 256)
        assertEquals("", peekPrefix(pb))
        assertEquals(0, pb.readBytes().size)
    }

    /** Delivers at most [chunk] bytes per bulk read, like a fragmented socket. */
    private class DribbleStream(private val data: ByteArray, private val chunk: Int) : InputStream() {
        private var pos = 0
        override fun read(): Int = if (pos >= data.size) -1 else data[pos++].toInt() and 0xFF
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= data.size) return -1
            val n = minOf(len, chunk, data.size - pos)
            System.arraycopy(data, pos, b, off, n)
            pos += n
            return n
        }
    }
}
