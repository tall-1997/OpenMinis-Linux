package com.openminis.app.data.memory

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.runBlocking

/**
 * [T-memory-embedding-recall] Pure-JVM coverage of the #27 embedding seam:
 * cosine math, the vector blob round-trip, and the OpenAI-compatible wire
 * format (request shape, response parse, and every failure mode collapsing
 * to "unavailable").
 */
class EmbeddingProviderTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    // ── cosine ─────────────────────────────────────────────────────────────

    @Test
    fun `cosine of parallel vectors is one`() {
        val a = floatArrayOf(1f, 2f, 3f)
        assertEquals(1f, VectorMath.cosine(a, floatArrayOf(2f, 4f, 6f)), 1e-6f)
    }

    @Test
    fun `cosine of orthogonal vectors is zero and opposite is minus one`() {
        assertEquals(0f, VectorMath.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 1e-6f)
        assertEquals(-1f, VectorMath.cosine(floatArrayOf(1f, 0f), floatArrayOf(-1f, 0f)), 1e-6f)
    }

    @Test
    fun `cosine never NaN on degenerate input`() {
        assertEquals(0f, VectorMath.cosine(floatArrayOf(0f, 0f), floatArrayOf(1f, 2f)), 0f)
        assertEquals(0f, VectorMath.cosine(floatArrayOf(1f), floatArrayOf(1f, 2f)), 0f)
        assertEquals(0f, VectorMath.cosine(floatArrayOf(1f), null), 0f)
        assertEquals(0f, VectorMath.cosine(floatArrayOf(), floatArrayOf()), 0f)
    }

    // ── blob round-trip ────────────────────────────────────────────────────

    @Test
    fun `vector blob round-trips exactly`() {
        val v = floatArrayOf(0f, -1.5f, 3.25f, Float.MIN_VALUE, Float.MAX_VALUE, 1234.5678f)
        val decoded = FloatVectorBlob.decode(FloatVectorBlob.encode(v))!!
        assertTrue(v.contentEquals(decoded))
    }

    @Test
    fun `blob rejects non-multiple-of-four lengths`() {
        assertNull(FloatVectorBlob.decode(ByteArray(3)))
        assertNull(FloatVectorBlob.decode(ByteArray(0)))
    }

    @Test
    fun `blob encoding is deterministic and length-bound`() {
        val v = floatArrayOf(1f, 2f)
        assertEquals(8, FloatVectorBlob.encode(v).size)
        assertNotEquals(FloatVectorBlob.encode(v), FloatVectorBlob.encode(floatArrayOf(2f, 1f)))
    }

    // ── provider wire format ───────────────────────────────────────────────

    @Test
    fun `endpoint normalizes base url variants`() {
        fun ep(base: String) =
            OpenAICompatibleEmbeddingProvider(base, "k", "m").endpoint()
        assertEquals("https://api.example.com/v1/embeddings", ep("https://api.example.com"))
        assertEquals("https://api.example.com/v1/embeddings", ep("https://api.example.com/"))
        assertEquals("https://api.example.com/v1/embeddings", ep("https://api.example.com/v1"))
        assertEquals("https://api.example.com/v1/embeddings", ep("https://api.example.com/v1/embeddings"))
    }

    @Test
    fun `request body carries model and input array`() {
        val p = OpenAICompatibleEmbeddingProvider("https://x", "k", "m-1")
        val json = p.buildRequestBody(listOf("a", "b"), "m-1")
        assertTrue(json.contains("\"model\":\"m-1\""))
        assertTrue(json.contains("\"input\":[\"a\",\"b\"]"))
    }

    @Test
    fun `response parse reorders by index and validates dims`() {
        val p = OpenAICompatibleEmbeddingProvider("https://x", "k", "m")
        val json = """{"data":[
            {"index":1,"embedding":[0.4,0.5]},
            {"index":0,"embedding":[0.1,0.2]}
        ]}"""
        val out = p.parseEmbeddingsResponse(json)!!
        assertEquals(2, out.size)
        assertEquals(0.1f, out[0][0], 1e-6f)
        assertEquals(0.4f, out[1][0], 1e-6f)

        // Ragged dims, missing array, empty array, non-finite values: all null.
        assertNull(p.parseEmbeddingsResponse("""{"data":[{"index":0,"embedding":[1]},{"index":1,"embedding":[1,2]}]}"""))
        assertNull(p.parseEmbeddingsResponse("""{"nope":1}"""))
        assertNull(p.parseEmbeddingsResponse("""{"data":[]}"""))
        assertNull(p.parseEmbeddingsResponse("""{"data":[{"index":0,"embedding":[NaN]}]}"""))
        assertNull(p.parseEmbeddingsResponse("not json"))
    }

    @Test
    fun `embed posts the documented shape and returns vectors in order`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"data":[
                    {"index":0,"embedding":[0.5,0.25]},
                    {"index":1,"embedding":[-1.0,2.0]}
                ]}""",
            ).setHeader("Content-Type", "application/json"),
        )
        val p = OpenAICompatibleEmbeddingProvider(
            baseUrl = server.url("/").toString().trimEnd('/'),
            apiKey = "sk-test",
            model = "text-embedding-3-small",
        )
        val out = p.embed(listOf("hello", "world"))!!
        assertEquals(2, out.size)
        assertEquals(0.5f, out[0][0], 1e-6f)
        assertEquals(2.0f, out[1][1], 1e-6f)

        val recorded = server.takeRequest()
        assertEquals("/v1/embeddings", recorded.path)
        assertEquals("Bearer sk-test", recorded.getHeader("Authorization"))
        val sent = recorded.body.readUtf8()
        assertTrue(sent.contains("\"model\":\"text-embedding-3-small\""))
        assertTrue(sent.contains("\"input\":[\"hello\",\"world\"]"))
    }

    @Test
    fun `embed collapses every failure to null`() = runBlocking {
        val p = OpenAICompatibleEmbeddingProvider(
            baseUrl = server.url("/").toString().trimEnd('/'),
            apiKey = "sk-test",
            model = "m",
        )
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        assertNull(p.embed(listOf("x")))

        server.enqueue(MockResponse().setBody("garbage not json"))
        assertNull(p.embed(listOf("x")))

        server.enqueue(MockResponse().setBody("""{"data":[{"index":0,"embedding":[1.0]}]}"""))
        // Two inputs but the reply has one entry: size mismatch -> null.
        assertNull(p.embed(listOf("x", "y")))

        // Empty input short-circuits without an HTTP call.
        assertEquals(0, p.embed(emptyList())!!.size)
        assertEquals(3, server.requestCount)
    }
}
