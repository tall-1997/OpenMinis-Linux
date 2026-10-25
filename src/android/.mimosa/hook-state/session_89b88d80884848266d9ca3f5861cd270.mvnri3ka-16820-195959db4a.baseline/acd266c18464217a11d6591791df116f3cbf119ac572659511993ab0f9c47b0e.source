package com.openminis.app.provider

import com.openminis.app.data.model.LLMError
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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class OpenAIVendorMediaTest {
    private lateinit var server: MockWebServer
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
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun provider(
        modelId: String,
        kind: VendorMediaKind? = null,
        basePath: String = server.url("/").toString().trimEnd('/'),
    ): OpenAIProvider {
        val p = OpenAIProvider(
            apiKey = "test-key",
            model = LLMModel(modelId, modelId, "test"),
            basePath = basePath,
        )
        p.vendorMediaOverride = kind
        p.videoFirstPollMillis = 1L
        p.videoPollMillis = 1L
        return p
    }

    private fun enqueueMp4() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "video/mp4")
                .setBody(Buffer().write(mp4)),
        )
    }

    @Test
    fun `ark video uses contents generations tasks and downloads video_url`() = runBlocking {
        val fileUrl = server.url("/clip.mp4").toString()
        server.enqueue(MockResponse().setBody("""{"id":"ftask-1","status":"submitted"}"""))
        server.enqueue(MockResponse().setBody("""{"id":"ftask-1","status":"succeeded","video_url":"$fileUrl"}"""))
        enqueueMp4()

        val response = provider("doubao-video-gen-01", VendorMediaKind.ARK)
            .generateVideo("一只小狗在草地上奔跑，写实，4k")
        assertTrue(response.mediaAttachments.single().data.contentEquals(mp4))

        val create = server.takeRequest()
        assertEquals("POST", create.method)
        assertEquals("/api/v3/contents/generations/tasks", create.path)
        assertEquals("Bearer test-key", create.getHeader("Authorization"))
        val body = JSONObject(create.body.readUtf8())
        assertEquals("doubao-video-gen-01", body.getString("model"))
        assertEquals("一只小狗在草地上奔跑，写实，4k", body.getString("prompt"))
        assertEquals(5, body.getInt("duration"))
        assertEquals("720p", body.getString("resolution"))

        val poll = server.takeRequest()
        assertEquals("GET", poll.method)
        assertEquals("/api/v3/contents/generations/tasks/ftask-1", poll.path)
    }

    @Test
    fun `ark seedance posts content array`() = runBlocking {
        val fileUrl = server.url("/clip.mp4").toString()
        server.enqueue(
            MockResponse().setBody("""{"id":"cgt-1","status":"succeeded","content":{"video_url":"$fileUrl"}}"""),
        )
        enqueueMp4()

        provider("doubao-seedance-1-0-pro", VendorMediaKind.ARK).generateVideo("slow dolly in")
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("text", body.getJSONArray("content").getJSONObject(0).getString("type"))
        assertEquals("slow dolly in", body.getJSONArray("content").getJSONObject(0).getString("text"))
        assertTrue(!body.has("duration"))
    }

    @Test
    fun `ark image posts host-root images generations`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[]}"""))
        provider("doubao-seedream-4-0", VendorMediaKind.ARK)
            .generateImage("red kite")
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/v3/images/generations", req.path)
        val body = JSONObject(req.body.readUtf8())
        assertEquals("doubao-seedream-4-0", body.getString("model"))
        assertEquals("red kite", body.getString("prompt"))
    }

    @Test
    fun `zhipu video polls async-result`() = runBlocking {
        val fileUrl = server.url("/clip.mp4").toString()
        server.enqueue(MockResponse().setBody("""{"id":"task-1","task_status":"PROCESSING"}"""))
        server.enqueue(
            MockResponse().setBody(
                """{"id":"task-1","task_status":"SUCCESS","video_result":[{"url":"$fileUrl"}]}""",
            ),
        )
        enqueueMp4()

        val response = provider("cogvideox-3", VendorMediaKind.ZHIPU).generateVideo("waves")
        assertEquals(LLMMediaAttachment.MediaType.VIDEO, response.mediaAttachments.single().type)

        assertEquals("/api/paas/v4/videos/generations", server.takeRequest().path)
        val poll = server.takeRequest()
        assertEquals("GET", poll.method)
        assertEquals("/api/paas/v4/async-result/task-1", poll.path)
    }

    @Test
    fun `zhipu image posts paas v4 images generations`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[]}"""))
        provider("cogview-4", VendorMediaKind.ZHIPU).generateImage("studio backdrop")
        assertEquals("/api/paas/v4/images/generations", server.takeRequest().path)
    }

    @Test
    fun `dashscope video uses async synthesis and task poll`() = runBlocking {
        val fileUrl = server.url("/clip.mp4").toString()
        server.enqueue(
            MockResponse().setBody("""{"output":{"task_id":"t1","task_status":"PENDING"}}"""),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"output":{"task_status":"SUCCEEDED","video_url":"$fileUrl"}}""",
            ),
        )
        enqueueMp4()

        provider("wanx2.1-t2v-turbo", VendorMediaKind.DASHSCOPE).generateVideo("fog over hills")
        val create = server.takeRequest()
        assertEquals("/api/v1/services/aigc/video-generation/video-synthesis", create.path)
        assertEquals("enable", create.getHeader("X-DashScope-Async"))
        val body = JSONObject(create.body.readUtf8())
        assertEquals("fog over hills", body.getJSONObject("input").getString("prompt"))
        assertEquals("/api/v1/tasks/t1", server.takeRequest().path)
    }

    @Test
    fun `minimax video queries task then retrieves file`() = runBlocking {
        val fileUrl = server.url("/clip.mp4").toString()
        server.enqueue(MockResponse().setBody("""{"task_id":"m1"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"Success","file_id":"f1"}"""))
        server.enqueue(MockResponse().setBody("""{"download_url":"$fileUrl"}"""))
        enqueueMp4()

        provider("video-01", VendorMediaKind.MINIMAX).generateVideo("cat walking")
        assertEquals("/v1/video_generation", server.takeRequest().path)
        val poll = server.takeRequest()
        assertTrue(poll.path!!.startsWith("/v1/query/video_generation"))
        assertTrue(poll.path!!.contains("task_id=m1"))
        assertTrue(server.takeRequest().path!!.contains("file_id=f1"))
    }

    @Test
    fun `dashscope qwen-image uses compatible-mode images generations`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[]}"""))
        provider("qwen-image-plus", VendorMediaKind.DASHSCOPE).generateImage("ink wash")
        assertEquals("/compatible-mode/v1/images/generations", server.takeRequest().path)
    }

    @Test
    fun `dashscope wanx image uses native async synthesis`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"output":{"task_id":"img1","task_status":"PENDING"}}"""),
        )
        server.enqueue(
            MockResponse().setBody("""{"output":{"task_status":"SUCCEEDED","results":[]}}"""),
        )
        try {
            provider("wanx-v1", VendorMediaKind.DASHSCOPE).generateImage("lotus pond")
            fail("empty results should error after success")
        } catch (e: LLMError.ProviderError) {
            assertTrue(e.detail.contains("DashScope image completed"))
        }
        val create = server.takeRequest()
        assertEquals("/api/v1/services/aigc/text2image/image-synthesis", create.path)
        assertEquals("enable", create.getHeader("X-DashScope-Async"))
        val body = JSONObject(create.body.readUtf8())
        assertEquals("lotus pond", body.getJSONObject("input").getString("prompt"))
        assertEquals("/api/v1/tasks/img1", server.takeRequest().path)
    }

    @Test
    fun `minimax image posts image_generation`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":{}}"""))
        provider("image-01", VendorMediaKind.MINIMAX).generateImage("a lantern")
        val req = server.takeRequest()
        assertEquals("/v1/image_generation", req.path)
        val body = JSONObject(req.body.readUtf8())
        assertEquals("a lantern", body.getString("prompt"))
        assertEquals("base64", body.getString("response_format"))
    }

    @Test
    fun `deepseek image and video fail without probing openai media endpoints`() = runBlocking {
        val p = provider("deepseek-chat", basePath = "https://api.deepseek.com/v1")
        try {
            p.generateImage("a cat")
            fail("expected image error")
        } catch (e: LLMError.ProviderError) {
            assertTrue(e.detail.contains("DeepSeek"))
        }
        try {
            p.generateVideo("a cat running")
            fail("expected video error")
        } catch (e: LLMError.ProviderError) {
            assertTrue(e.detail.contains("DeepSeek"))
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `hunyuan tencent cloud native is unsupported`() = runBlocking {
        val p = provider("hunyuan-image", basePath = "https://hunyuan.tencentcloudapi.com")
        try {
            p.generateImage("mountain")
            fail("expected image error")
        } catch (e: LLMError.ProviderError) {
            assertTrue(e.detail.contains("TC3"))
        }
        try {
            p.generateVideo("mountain flyover")
            fail("expected video error")
        } catch (e: LLMError.ProviderError) {
            assertTrue(e.detail.contains("TC3"))
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `agnes stays on openai-compat image path`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[]}"""))
        val p = provider("agnes-pro", VendorMediaKind.AGNES, server.url("/v1").toString().trimEnd('/'))
        p.generateImage("watercolor fox")
        assertEquals("/v1/images/generations", server.takeRequest().path)
    }
}
