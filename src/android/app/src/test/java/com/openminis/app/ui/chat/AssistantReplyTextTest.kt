package com.openminis.app.ui.chat

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantReplyTextTest {
    @Test
    fun `rewrite keeps tools and replaces only the last visible paragraph`() {
        val first = JSONArray()
            .put(text("intro"))
            .put(tool("bash"))
            .toString()
        val second = JSONArray()
            .put(text("<system-reminder>keep</system-reminder>\nbody"))
            .toString()
        val rewritten = AssistantReplyText.rewriteStoredParts(
            listOf("a" to first, "b" to second),
            "译文",
        ).toMap()
        val a = JSONArray(rewritten.getValue("a"))
        assertEquals("", a.getJSONObject(0).getString("value"))
        assertEquals("toolUse", a.getJSONObject(1).getString("type"))
        val b = JSONArray(rewritten.getValue("b"))
        val value = b.getJSONObject(0).getString("value")
        assertTrue(value.contains("<system-reminder>keep</system-reminder>"))
        assertTrue(value.contains("译文"))
        assertFalse(value.contains("body"))
        assertEquals("译文", AssistantReplyText.visible(value))
    }

    private fun text(value: String) =
        org.json.JSONObject().put("type", "text").put("value", value)

    private fun tool(name: String) =
        org.json.JSONObject().put("type", "toolUse").put("value", org.json.JSONObject().put("name", name))
}
