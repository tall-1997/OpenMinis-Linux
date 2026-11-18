package com.openminis.app.provider

import com.openminis.app.data.model.LLMError
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [T-fullbodyjson-bom-and-empty] Relays occasionally answer with a BOM-prefixed
 * or empty body (user report: token.zlshag.xyz on glm/deepseek models —
 * "expected a JSON object body … but got: " with a BLANK tail, because the
 * U+FEFF excerpt renders as nothing). JSONObject() rejects BOMs (not JSON
 * whitespace) and throws "end of input at character 0" on empty strings; both
 * surfaced as opaque DecodingErrors. These tests pin the three fixes:
 * BOM stripping, empty-body messaging, and excerpt visibility.
 */
class FullBodyJsonTest {

    // -- BOM stripping -------------------------------------------------------

    @Test
    fun `parses body with UTF-8 BOM prefix`() = runTest {
        val json = FullBodyJson.parse("\uFEFF{\"choices\":[]}", "test body")
        assertEquals(0, json.getJSONArray("choices").length())
    }

    @Test
    fun `parses body with repeated BOM and whitespace prefix`() = runTest {
        val json = FullBodyJson.parse("\uFEFF \uFEFF\r\n{\"ok\":true}", "test body")
        assertEquals(true, json.getBoolean("ok"))
    }

    @Test
    fun `stripBomAndTrim leaves clean JSON untouched`() {
        val raw = "{\"a\":1}"
        assertEquals(raw, FullBodyJson.stripBomAndTrim(raw))
    }

    @Test
    fun `stripBomAndTrim handles reverse-BOM and padding`() {
        assertEquals("{\"a\":1}", FullBodyJson.stripBomAndTrim("\uFFFE  {\"a\":1}\n\t"))
    }

    // -- empty / whitespace-only bodies --------------------------------------

    @Test
    fun `empty body names the fact instead of org-json internals`() = runTest {
        try {
            FullBodyJson.parse("", "chat-completions body (Content-Type: application/json)")
            fail("expected DecodingError")
        } catch (e: LLMError.DecodingError) {
            val msg = e.message.orEmpty()
            assertTrue(msg, msg.contains("empty body (0 bytes after trim)"))
            assertTrue(msg, msg.contains("chat-completions body"))
        }
    }

    @Test
    fun `whitespace-only body reports real byte count`() = runTest {
        try {
            FullBodyJson.parse("   \r\n  ", "test label")
            fail("expected DecodingError")
        } catch (e: LLMError.DecodingError) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("empty body (6 bytes after trim)"))
        }
    }

    // -- excerpt visibility ---------------------------------------------------

    @Test
    fun `non-json body keeps a visible excerpt in the message`() = runTest {
        try {
            FullBodyJson.parse("<html>gateway</html>", "chat-completions body")
            fail("expected DecodingError")
        } catch (e: LLMError.DecodingError) {
            val msg = e.message.orEmpty()
            assertTrue(msg, msg.contains("but got: <html>gateway</html>"))
        }
    }

    @Test
    fun `BOM-only body reports empty-body fact not blank tail`() = runTest {
        try {
            FullBodyJson.parse("\uFEFF", "test label")
            fail("expected DecodingError")
        } catch (e: LLMError.DecodingError) {
            val msg = e.message.orEmpty()
            // The old path rendered "but got: ?" where U+FEFF is invisible —
            // the relay bug report. Now the message states the fact.
            assertTrue(msg, msg.contains("empty body"))
        }
    }

    // -- happy paths still parse through org.json ----------------------------

    @Test
    fun `array top-level still rejected with excerpt`() = runTest {
        try {
            FullBodyJson.parse("[1,2,3]", "test label")
            fail("JSONObject() accepts only objects")
        } catch (e: LLMError.DecodingError) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("but got: [1,2,3]"))
        }
    }

    @Test
    fun `valid nested body parses`() = runTest {
        val json = FullBodyJson.parse(
            "{\"choices\":[{\"message\":{\"content\":\"hi\"}}],\"usage\":{\"total_tokens\":5}}",
            "test body",
        )
        assertEquals("hi", json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content"))
        assertEquals(5, json.getJSONObject("usage").getInt("total_tokens"))
    }
}
