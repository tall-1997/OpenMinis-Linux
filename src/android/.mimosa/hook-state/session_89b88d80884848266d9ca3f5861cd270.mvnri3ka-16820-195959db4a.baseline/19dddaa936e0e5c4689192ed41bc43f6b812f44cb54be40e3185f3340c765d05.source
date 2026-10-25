package com.openminis.app.provider

import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.openai.OpenAIProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenAIImageGenerationTest {
    private lateinit var server: MockWebServer
    private lateinit var provider: OpenAIProvider

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        provider = OpenAIProvider(
            apiKey = "test-key",
            model = LLMModel(
                id = "gpt-image-1",
                displayName = "Image model",
                provider = "OpenAI",
                outputModalities = listOf("image"),
            ),
            basePath = server.url("/v1").toString().trimEnd('/'),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `generateImage posts OpenAI images request`() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"data\":[]}"))

        val response = provider.generateImage(
            prompt = "a red kite over a quiet lake",
            size = "1024x1024",
        )

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/images/generations", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("gpt-image-1"))
        assertTrue(body.contains("a red kite over a quiet lake"))
        assertTrue(body.contains("1024x1024"))
        assertTrue(body.contains("b64_json"))
        assertTrue(response.mediaAttachments.isEmpty())
    }
}
