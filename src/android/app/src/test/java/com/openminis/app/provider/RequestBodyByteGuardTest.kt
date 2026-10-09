package com.openminis.app.provider

import com.openminis.app.data.model.LLMMessage
import com.openminis.app.harness.context.ContextWindowPolicy
import com.openminis.app.ui.chat.applyContextWindowPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-context-window-policy] 字节守卫与投影截断的宿主侧验收。
 */
class RequestBodyByteGuardTest {

    private fun bodyWithImage(dataChars: Int): JSONObject {
        val block = JSONObject()
            .put("type", "image")
            .put("source", JSONObject().put("type", "base64").put("data", "x".repeat(dataChars)))
        val text = JSONObject().put("type", "text").put("text", "look")
        val message = JSONObject().put("role", "user").put("content", JSONArray().put(text).put(block))
        return JSONObject().put("messages", JSONArray().put(message))
    }

    @Test
    fun `small bodies pass through byte identical`() {
        val body = bodyWithImage(10)
        val once = body.toString()
        assertEquals(once, RequestBodyByteGuard.enforce(JSONObject(once)))
    }

    @Test
    fun `over limit bodies get images stripped with a marker`() {
        val body = bodyWithImage(5 * 1024 * 1024)
        val out = RequestBodyByteGuard.enforce(body)
        assertTrue(out.length < 1_000_000)
        assertTrue(out.contains("张图片因请求体体积限制已从模型上下文省略"))
        assertTrue(!out.contains("base64"))
    }

    @Test
    fun `still over after stripping fails loud instead of shipping a 413`() {
        val body = bodyWithImage(100)
        body.getJSONArray("messages").getJSONObject(0)
            .getJSONArray("content").getJSONObject(0)
            .put("text", "y".repeat(5 * 1024 * 1024))
        val failure = runCatching { RequestBodyByteGuard.enforce(body) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message!!.contains("hard limit"))
    }

    @Test
    fun `projection truncation only touches user content over budget`() {
        val giant = (1..30_000).joinToString(" ") { "w$it" }
        val history = listOf(
            LLMMessage(role = LLMMessage.Role.USER, content = giant),
            LLMMessage(role = LLMMessage.Role.ASSISTANT, content = giant),
        )
        val out = applyContextWindowPolicy(history, 3_000)
        assertTrue("user turn must shrink", out[0].content.length < giant.length)
        assertTrue(out[0].content.contains("消息过长已截断"))
        assertEquals("assistant text must not be touched", giant, out[1].content)
    }

    @Test
    fun `zero budget means no governance`() {
        val history = listOf(LLMMessage(role = LLMMessage.Role.USER, content = "x".repeat(50_000)))
        assertEquals(history, applyContextWindowPolicy(history, 0))
    }

    @Test
    fun `schema reserve is measured from our own tool schemas`() {
        val reserve = com.openminis.app.tools.ToolSchemaResolver.schemas.values
            .sumOf { ContextWindowPolicy.estimateTokens(it.toString()) }
        assertTrue("reserve must be a real measured quantity, got $reserve", reserve in 1_000..50_000)
    }
}
