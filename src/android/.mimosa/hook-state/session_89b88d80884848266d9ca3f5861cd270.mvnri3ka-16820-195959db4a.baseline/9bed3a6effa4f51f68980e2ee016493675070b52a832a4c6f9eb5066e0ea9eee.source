package com.openminis.app.provider

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VendorMediaTest {
    @Test
    fun `detects first-party hosts`() {
        assertEquals(
            VendorMediaKind.ARK,
            VendorMedia.detect("https://ark.cn-beijing.volces.com/api/v3", "doubao-video-gen-01"),
        )
        assertEquals(
            VendorMediaKind.ZHIPU,
            VendorMedia.detect("https://open.bigmodel.cn/api/paas/v4", "cogvideox-3"),
        )
        assertEquals(
            VendorMediaKind.DASHSCOPE,
            VendorMedia.detect("https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-image"),
        )
        assertEquals(
            VendorMediaKind.DEEPSEEK,
            VendorMedia.detect("https://api.deepseek.com/v1", "deepseek-chat"),
        )
        assertEquals(
            VendorMediaKind.HUNYUAN_TC3,
            VendorMedia.detect("https://hunyuan.tencentcloudapi.com", "hunyuan-image"),
        )
        assertEquals(
            VendorMediaKind.HUNYUAN_OPENAI,
            VendorMedia.detect("https://api.hunyuan.cloud.tencent.com/v1", "hunyuan-turbo"),
        )
        assertEquals(
            VendorMediaKind.AGNES,
            VendorMedia.detect("https://api.agnes-ai.com/v1", "agnes-pro"),
        )
        assertEquals(
            VendorMediaKind.AGNES,
            VendorMedia.detect("https://www.agnes-ai.cn/v1", "agnes-pro"),
        )
        assertEquals(
            VendorMediaKind.MINIMAX,
            VendorMedia.detect("https://api.minimax.chat/v1", "video-01"),
        )
        assertEquals(
            VendorMediaKind.KLING,
            VendorMedia.detect("https://api.klingai.com/v1", "kling-v2"),
        )
    }

    @Test
    fun `relays stay openai-compat even with vendor model ids`() {
        assertEquals(
            VendorMediaKind.OPENAI_COMPAT,
            VendorMedia.detect("https://openrouter.ai/api/v1", "bytedance/seedance-1-0"),
        )
        assertEquals(
            VendorMediaKind.OPENAI_COMPAT,
            VendorMedia.detect("https://api.openai.com/v1", "gpt-image-1"),
        )
        assertEquals(
            VendorMediaKind.OPENAI_COMPAT,
            VendorMedia.detect("http://127.0.0.1:8080/v1", "doubao-video-gen-01"),
        )
    }

    @Test
    fun `unsupported messages cover deepseek hunyuan-tc3 kling`() {
        assertTrue(
            VendorMedia.unsupportedMessage(VendorMediaKind.DEEPSEEK, "image")!!
                .contains("DeepSeek"),
        )
        assertTrue(
            VendorMedia.unsupportedMessage(VendorMediaKind.HUNYUAN_TC3, "video")!!
                .contains("TC3"),
        )
        assertTrue(
            VendorMedia.unsupportedMessage(VendorMediaKind.KLING, "video")!!
                .contains("JWT"),
        )
        assertNull(VendorMedia.unsupportedMessage(VendorMediaKind.ARK, "video"))
        assertNull(VendorMedia.unsupportedMessage(VendorMediaKind.AGNES, "image"))
    }

    @Test
    fun `parses ark and zhipu video envelopes`() {
        val ark = JSONObject("""{"id":"ftask-1","status":"succeeded","video_url":"https://cdn.example/a.mp4"}""")
        assertEquals("ftask-1", VendorMedia.taskId(ark))
        assertEquals("https://cdn.example/a.mp4", VendorMedia.extractHttpVideoUrl(ark))
        assertTrue(VendorMedia.isSuccessStatus(VendorMedia.taskStatus(ark)))

        val nested = JSONObject("""{"id":"cgt-1","status":"succeeded","content":{"video_url":"https://cdn.example/b.mp4"}}""")
        assertEquals("https://cdn.example/b.mp4", VendorMedia.extractHttpVideoUrl(nested))

        val zhipu = JSONObject(
            """{"id":"task-1","task_status":"SUCCESS","video_result":[{"url":"https://cdn.example/c.mp4"}]}""",
        )
        assertEquals("task-1", VendorMedia.taskId(zhipu))
        assertEquals("https://cdn.example/c.mp4", VendorMedia.extractHttpVideoUrl(zhipu))
        assertTrue(VendorMedia.isSuccessStatus(VendorMedia.taskStatus(zhipu)))
    }
}
