package com.openminis.app.provider

import com.openminis.app.data.model.LLMMediaAttachment
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.openai.OpenAIProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenAIProviderVideoTest {
    private lateinit var server: MockWebServer
    private lateinit var provider: OpenAIProvider

    private val mp4: ByteArray = ByteArray(32).also {
        it[4] = 'f'.code.toByte()
        it[5] = 't'.code.toByte()
        it[6] = 'y'.code.toByte()
        it[7] = 'p'.code.toByte()
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        provider = OpenAIProvider(
            apiKey = "test-key",
            model = LLMModel("sora-2", "Sora 2", "OpenAI"),
            basePath = server.url("/").toString().trimEnd('/'),
        )
        provider.videoFirstPollMillis = 1L
        provider.videoPollMillis = 1L
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `generateVideo posts to videos then downloads url`() = runBlocking {
        val fileUrl = server.url("/clip.mp4").toString()
        server.enqueue(
            MockResponse().setBody("""{"data":[{"url":"$fileUrl"}]}"""),
        )
        enqueueMp4()

        val response = provider.generateVideo("a cat walking")
        val att = response.mediaAttachments.single()
        assertEquals(LLMMediaAttachment.MediaType.VIDEO, att.type)
        assertEquals("video/mp4", att.mimeType)
        assertTrue(att.data.contentEquals(mp4))

        val create = server.takeRequest()
        assertEquals("POST", create.method)
        assertEquals("/videos", create.path)
        val body = JSONObject(create.body.readUtf8())
        assertEquals("sora-2", body.getString("model"))
        assertEquals("a cat walking", body.getString("prompt"))
        assertEquals("Bearer test-key", create.getHeader("Authorization"))
    }

    @Test
    fun `generateVideo falls back to v1 videos on 404`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"message":"missing"}}"""))
        val fileUrl = server.url("/clip.mp4").toString()
        server.enqueue(MockResponse().setBody("""{"data":[{"url":"$fileUrl"}]}"""))
        enqueueMp4()

        val response = provider.generateVideo("waves")
        assertEquals(1, response.mediaAttachments.size)

        assertEquals("/videos", server.takeRequest().path)
        assertEquals("/v1/videos", server.takeRequest().path)
    }

    @Test
    fun `generateVideo polls then downloads content`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"id":"vid_1","status":"queued"}"""))
        server.enqueue(MockResponse().setBody("""{"id":"vid_1","status":"in_progress"}"""))
        server.enqueue(MockResponse().setBody("""{"id":"vid_1","status":"completed"}"""))
        enqueueMp4()

        val response = provider.generateVideo("night city")
        assertTrue(response.mediaAttachments.single().data.contentEquals(mp4))

        assertEquals("POST", server.takeRequest().method)
        val poll1 = server.takeRequest()
        assertEquals("GET", poll1.method)
        assertEquals("/videos/vid_1", poll1.path)
        assertEquals("/videos/vid_1", server.takeRequest().path)
        assertEquals("/videos/vid_1/content", server.takeRequest().path)
    }

    @Test
    fun `generateVideo omits mode unless the caller set one`() = runBlocking {
        enqueueJson("""{"data":[{"url":"${server.url("/clip.mp4")}"}]}""")
        enqueueMp4()

        provider.generateVideo("no mode")
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertFalse(body.has("mode"))
        assertNull(provider.videoMode)
        assertNull(provider.videoModeSent)
    }

    @Test
    fun `generateVideo sends mode once and does not carry it forward`() = runBlocking {
        provider.videoMode = "pro"
        enqueueJson("""{"data":[{"url":"${server.url("/clip.mp4")}"}]}""")
        enqueueMp4()
        provider.generateVideo("pro clip")
        val first = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("pro", first.getString("mode"))
        assertNull(provider.videoMode)
        assertEquals("pro", provider.videoModeSent)
        server.takeRequest()

        enqueueJson("""{"data":[{"url":"${server.url("/clip.mp4")}"}]}""")
        enqueueMp4()
        provider.generateVideo("plain clip")
        val second = JSONObject(server.takeRequest().body.readUtf8())
        assertFalse(second.has("mode"))
        assertNull(provider.videoModeSent)
    }

    @Test
    fun `generateVideo retries with std only when the provider requires mode`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody("""{"error":{"message":"mode is required"}}"""),
        )
        enqueueJson("""{"data":[{"url":"${server.url("/clip.mp4")}"}]}""")
        enqueueMp4()

        provider.generateVideo("needs mode")
        val rejected = JSONObject(server.takeRequest().body.readUtf8())
        assertFalse(rejected.has("mode"))
        val retried = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("std", retried.getString("mode"))
        assertNull(provider.videoMode)
        assertEquals("std", provider.videoModeSent)
    }

    private fun enqueueJson(body: String) {
        server.enqueue(MockResponse().setBody(body))
    }

    private fun enqueueMp4() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "video/mp4")
                .setBody(Buffer().write(mp4)),
        )
    }
}
