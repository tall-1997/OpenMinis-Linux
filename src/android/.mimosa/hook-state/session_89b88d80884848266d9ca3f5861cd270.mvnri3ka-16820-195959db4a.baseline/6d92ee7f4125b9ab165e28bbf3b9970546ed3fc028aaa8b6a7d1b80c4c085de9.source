package com.openminis.app.provider.openai

import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.openai.HttpBody
import com.openminis.app.provider.openai.RequestBodyGate
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Pins the transport-layer contract for [HttpBody] and
 * [OpenAIProvider.rawPassthroughRequest].
 *
 * These are the tests that keep two specific regressions from coming back:
 *
 *  1. **Content-Type must be derived, not assumed.** The old code set
 *     `Content-Type: application/json` unconditionally. A multipart body sent
 *     through that path has its `boundary` suppressed by the manually-set
 *     header, and the server cannot parse the body at all — the request
 *     arrives looking well-formed and fails with a provider-side 400 that says
 *     nothing about the real cause.
 *
 *  2. **Backward compatibility is byte-identical.** Switching the signature
 *     from `JSONObject?` to `HttpBody?` must not change a single header or body
 *     byte for existing callers — including the GET case, where the old code
 *     still set `application/json`.
 *
 * A third concern is the size ceiling, which Android had no equivalent of
 * before [RequestBodyGate].
 */
class HttpBodyPassthroughTest {

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

    private fun enqueueOk() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
    }

    // ── Content-Type derivation ───────────────────────────────────────────

    @Test
    fun `json body keeps the historical content-type and bytes`() = runBlocking {
        enqueueOk()
        val body = JSONObject().put("model", "gpt-4o-mini").put("stream", false)

        provider.rawPassthroughRequest(
            endpoint = "/chat/completions",
            method = "POST",
            headers = emptyMap(),
            body = HttpBody.Json(body),
        )

        val recorded = server.takeRequest()
        assertEquals("application/json", recorded.getHeader("Content-Type"))
        // Byte-identical to the old `bodyObject.toString()` path.
        assertEquals(body.toString(), recorded.body.readUtf8())
    }

    @Test
    fun `a null body still sends application json like before`() = runBlocking {
        enqueueOk()

        provider.rawPassthroughRequest(
            endpoint = "/chat/completions",
            method = "POST",
            headers = emptyMap(),
            body = null,
        )

        val recorded = server.takeRequest()
        // The old code set this header unconditionally, even with no body.
        // Preserved so existing requests are unchanged.
        assertEquals("application/json", recorded.getHeader("Content-Type"))
        assertEquals(0, recorded.bodySize)
    }

    @Test
    fun `GET still sends application json and no body`() = runBlocking {
        enqueueOk()
        // A body supplied alongside GET must still be dropped, and the header
        // still set — both preserved from the original guard.
        provider.rawPassthroughRequest(
            endpoint = "/models",
            method = "GET",
            headers = emptyMap(),
            body = HttpBody.Json(JSONObject().put("ignored", true)),
        )

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("application/json", recorded.getHeader("Content-Type"))
        assertEquals(0, recorded.bodySize)
    }

    @Test
    fun `empty body sends application json and no payload`() = runBlocking {
        enqueueOk()

        provider.rawPassthroughRequest(
            endpoint = "/chat/completions",
            method = "POST",
            headers = emptyMap(),
            body = HttpBody.Empty,
        )

        val recorded = server.takeRequest()
        assertEquals("application/json", recorded.getHeader("Content-Type"))
        assertEquals(0, recorded.bodySize)
    }

    @Test
    fun `bytes body carries its own media type`() = runBlocking {
        enqueueOk()

        provider.rawPassthroughRequest(
            endpoint = "/upload",
            method = "POST",
            headers = emptyMap(),
            body = HttpBody.Bytes(byteArrayOf(1, 2, 3), "application/octet-stream"),
        )

        val recorded = server.takeRequest()
        assertEquals("application/octet-stream", recorded.getHeader("Content-Type"))
        assertEquals(3, recorded.bodySize)
    }

    // ── Multipart: the boundary belongs to the transport ──────────────────

    @Test
    fun `multipart lets okhttp own the boundary`() = runBlocking {
        enqueueOk()

        provider.rawPassthroughRequest(
            endpoint = "/images/edits",
            method = "POST",
            headers = emptyMap(),
            body = HttpBody.Multipart(
                listOf(
                    HttpBody.Part.Field("model", "step-image-edit-2"),
                    HttpBody.Part.Field("prompt", "把裙子拉到腰际"),
                    HttpBody.Part.FilePart(
                        name = "image",
                        filename = "eni.png",
                        mediaType = "image/png",
                        data = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47),
                    ),
                ),
            ),
        )

        val recorded = server.takeRequest()
        val contentType = recorded.getHeader("Content-Type")
        assertNotNull("multipart must declare its type", contentType)
        assertTrue(
            "Content-Type must be multipart/form-data with a boundary, was: $contentType",
            contentType!!.startsWith("multipart/form-data; boundary="),
        )
        // The boundary must actually appear in the body. A manually-set
        // Content-Type is the bug that produces a type without a usable
        // boundary — assert it is really there, not just claimed.
        val boundary = contentType.substringAfter("boundary=").trim().removePrefix("\"")
        assertTrue("boundary must be non-empty", boundary.isNotEmpty())
        val hay = recorded.body.readByteArray()
        val raw = String(hay, Charsets.ISO_8859_1)
        assertTrue("body must use the declared boundary", raw.contains("--$boundary"))

        // Parts arrive in the order the caller wrote them: servers that read a
        // repeated field name (image[]) depend on it.
        assertTrue(raw.contains("name=\"model\""))
        assertTrue(raw.contains("name=\"prompt\""))
        assertTrue(raw.contains("name=\"image\""))
        assertTrue(raw.contains("filename=\"eni.png\""))
        assertTrue(raw.contains("Content-Type: image/png"))
        assertTrue(raw.indexOf("name=\"model\"") < raw.indexOf("name=\"prompt\""))
        assertTrue(raw.indexOf("name=\"prompt\"") < raw.indexOf("name=\"image\""))

        // The file payload survives intact (PNG magic bytes).
        val payload = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        assertTrue(
            "file bytes must round-trip",
            hay.asList().windowed(payload.size).any { it.toByteArray().contentEquals(payload) },
        )
    }

    @Test
    fun `multipart does not let a user header clobber the boundary`() = runBlocking {
        enqueueOk()
        // A caller who passes Content-Type explicitly must not win: that is
        // exactly the shape that suppresses OkHttp's boundary.
        provider.rawPassthroughRequest(
            endpoint = "/images/edits",
            method = "POST",
            headers = mapOf("Content-Type" to "application/json"),
            body = HttpBody.Multipart(listOf(HttpBody.Part.Field("a", "b"))),
        )

        val recorded = server.takeRequest()
        val contentType = recorded.getHeader("Content-Type")
        assertTrue(
            "user Content-Type must not override the multipart boundary, was: $contentType",
            contentType!!.startsWith("multipart/form-data; boundary="),
        )
    }

    // ── Size gate ─────────────────────────────────────────────────────────

    @Test
    fun `a body at the ceiling is refused before it is built`() {
        // A 33 MB JSON body: over the 32 MB ceiling, so nothing should be
        // allocated or sent.
        val big = JSONObject().put("blob", "x".repeat(33 * 1024 * 1024))
        try {
            RequestBodyGate.check(HttpBody.Json(big), "test")
            fail("expected a ProviderError for an over-ceiling body")
        } catch (e: LLMError.ProviderError) {
            assertTrue(
                "message should name the limit, was: ${e.detail}",
                e.detail.contains("limit"),
            )
        }
    }

    @Test
    fun `a single large image is under the ceiling and allowed`() {
        // A 12-megapixel PNG is ~15 MB. Refusing it would break the exact use
        // case multipart exists for, so the ceiling must sit above it.
        val image = ByteArray(15 * 1024 * 1024)
        RequestBodyGate.check(
            HttpBody.Multipart(listOf(HttpBody.Part.FilePart("image", "a.png", "image/png", image))),
            "test",
        )
    }

    @Test
    fun `multipart is measured as resident bytes not three times them`() {
        // JSON's 3x multiplier models serialization peak. Multipart parts are
        // already in memory, so asking 3x of a 20 MB body would refuse a
        // request the device can actually make.
        assertEquals(1, HttpBody.Multipart(emptyList()).serializationMultiplier)
        assertEquals(3, HttpBody.Json(JSONObject()).serializationMultiplier)
        assertEquals(1, HttpBody.Bytes(ByteArray(10), "image/png").serializationMultiplier)
    }

    @Test
    fun `multipart estimate covers every part`() {
        val body = HttpBody.Multipart(
            listOf(
                HttpBody.Part.Field("prompt", "hello"),
                HttpBody.Part.FilePart("image", "a.png", "image/png", ByteArray(1024)),
            ),
        )
        // 512 bytes of framing + 5 + 1024.
        assertEquals(512L + 5L + 1024L, body.estimatedBytes)
    }

    // ── Equality (ByteArray needs structural, not identity) ───────────────

    @Test
    fun `bytes and file parts compare structurally`() {
        assertEquals(HttpBody.Bytes(byteArrayOf(1, 2), "image/png"), HttpBody.Bytes(byteArrayOf(1, 2), "image/png"))
        assertFalse(HttpBody.Bytes(byteArrayOf(1, 2), "image/png") == HttpBody.Bytes(byteArrayOf(1, 3), "image/png"))
        assertEquals(
            HttpBody.Part.FilePart("image", "a.png", "image/png", byteArrayOf(9)),
            HttpBody.Part.FilePart("image", "a.png", "image/png", byteArrayOf(9)),
        )
    }

    @Test
    fun `multipart body has no content type of its own`() {
        // The contract that makes the boundary work: null means "the transport
        // fills this in".
        assertNull(HttpBody.Multipart(emptyList()).contentType())
        assertEquals("application/json", HttpBody.Empty.contentType().toString())
    }
}
