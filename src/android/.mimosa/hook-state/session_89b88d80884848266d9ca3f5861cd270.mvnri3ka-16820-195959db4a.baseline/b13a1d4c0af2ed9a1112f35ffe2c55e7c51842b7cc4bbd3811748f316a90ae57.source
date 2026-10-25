package com.openminis.app.provider

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * /v1/models 各家返回窗口参数的字段名不统一。解析要兼容常见写法，
 * 解析不到时返回 null，交给目录与兜底。
 */
class EndpointModelLimitsTest {

    @Test
    fun `reads OpenAI-style context_window and max_output_tokens`() {
        val obj = JSONObject("""{"id":"m","context_window":1000000,"max_output_tokens":64000}""")
        val (ctx, out) = EndpointModelLimits.read(obj)
        assertEquals(1_000_000, ctx)
        assertEquals(64_000, out)
    }

    @Test
    fun `reads OpenRouter-style context_length and top_provider max_completion_tokens`() {
        val obj = JSONObject(
            """{"id":"m","context_length":200000,"top_provider":{"max_completion_tokens":8192}}""",
        )
        val (ctx, out) = EndpointModelLimits.read(obj)
        assertEquals(200_000, ctx)
        assertEquals(8_192, out)
    }

    @Test
    fun `reads nested limit object`() {
        val obj = JSONObject("""{"id":"m","limit":{"context":131072,"output":32768}}""")
        val (ctx, out) = EndpointModelLimits.read(obj)
        assertEquals(131_072, ctx)
        assertEquals(32_768, out)
    }

    @Test
    fun `missing and non-positive values are absent`() {
        val obj = JSONObject("""{"id":"m","context_window":0,"max_output_tokens":-5}""")
        val (ctx, out) = EndpointModelLimits.read(obj)
        assertNull(ctx)
        assertNull(out)
    }
}
