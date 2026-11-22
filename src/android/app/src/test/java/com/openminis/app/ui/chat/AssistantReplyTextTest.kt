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

    @Test
    fun `later identical paragraph is not rewritten by the earlier one`() {
        val row = JSONArray()
            .put(text("same"))
            .put(tool("bash"))
            .put(text("<system-reminder>keep</system-reminder>\nsame"))
            .toString()
        val rows = listOf("a" to row)
        val first = AssistantReplyText.replaceVisibleOccurrence(rows, "same", "前段", 0)!!
        val parts = JSONArray(first.second)
        assertEquals("前段", parts.getJSONObject(0).getString("value"))
        assertTrue(parts.getJSONObject(2).getString("value").contains("same"))
        assertTrue(parts.getJSONObject(2).getString("value").contains("<system-reminder>keep</system-reminder>"))

        val second = AssistantReplyText.replaceVisibleOccurrence(rows, "same", "后段", 1)!!
        val later = JSONArray(second.second)
        assertEquals("same", later.getJSONObject(0).getString("value"))
        val value = later.getJSONObject(2).getString("value")
        assertTrue(value.contains("后段"))
        assertTrue(value.contains("<system-reminder>keep</system-reminder>"))
        assertFalse(AssistantReplyText.visible(value).contains("same"))
    }

    @Test
    fun `occurrence past the end does not rewrite a different paragraph`() {
        val row = JSONArray().put(text("same")).toString()
        assertEquals(null, AssistantReplyText.replaceVisibleOccurrence(listOf("a" to row), "same", "x", 1))
    }

    @Test
    fun `text occurrence counts only earlier copies of the same paragraph`() {
        val blocks = listOf(
            AssistantBlock(id = "a", kind = "text", content = "same"),
            AssistantBlock(id = "t", kind = "tool_use", content = "same"),
            AssistantBlock(id = "b", kind = "text", content = "other"),
            AssistantBlock(id = "c", kind = "text", content = "same"),
        )
        assertEquals(0, AssistantReplyText.textOccurrence(blocks, "a", "same"))
        assertEquals(1, AssistantReplyText.textOccurrence(blocks, "c", "same"))
        assertEquals(-1, AssistantReplyText.textOccurrence(blocks, "t", "same"))
    }

    @Test
    fun `trailing newline still writes the translated paragraph back`() {
        val row = JSONArray().put(text("same\n")).put(text("same\n")).toString()
        val blocks = listOf(
            AssistantBlock(id = "a", kind = "text", content = "same\n"),
            AssistantBlock(id = "b", kind = "text", content = "same\n"),
        )
        val occurrence = AssistantReplyText.textOccurrence(blocks, "b", "same\n")
        assertEquals(1, occurrence)
        val rewritten = AssistantReplyText.replaceVisibleOccurrence(
            listOf("row" to row),
            "same\n",
            "后段",
            occurrence,
        )
        val parts = JSONArray(rewritten!!.second)
        assertEquals("same\n", parts.getJSONObject(0).getString("value"))
        assertEquals("后段", parts.getJSONObject(1).getString("value"))
    }

    @Test
    fun `whole reply translation leaves progress in place including a trailing progress part`() {
        val progress = text("Checking the sample.").put("display", "process")
        val report = text("The sample is ready.")
        val tail = text("Preparing the follow-up.").put("display", "process")
        val progressOnlyRow = JSONArray().put(progress).toString()
        val replyRow = JSONArray().put(text("Sample found.")).put(tool("count_items"))
            .put(progress).put(report).put(tail).toString()
        val rows = listOf("progress" to progressOnlyRow, "reply" to replyRow)

        val (id, raw) = AssistantReplyText.rewriteStoredParts(rows, "样本已准备好。").single()
        assertEquals("reply", id)
        val parts = JSONArray(raw)
        assertEquals(5, parts.length())
        assertEquals("", parts.getJSONObject(0).getString("value"))
        assertEquals("toolUse", parts.getJSONObject(1).getString("type"))
        assertEquals(progress.toString(), parts.getJSONObject(2).toString())
        assertEquals("样本已准备好。", parts.getJSONObject(3).getString("value"))
        assertEquals(tail.toString(), parts.getJSONObject(4).toString())
        assertTrue(AssistantReplyText.rewriteStoredParts(rows.take(1), "译文").isEmpty())
    }

    @Test
    fun `paragraph translation skips identical progress in both UI and stored parts`() {
        val line = "Sample ready."
        val blocks = listOf(
            AssistantBlock(id = "p", kind = "text", content = line, isProcessText = true),
            AssistantBlock(id = "a", kind = "text", content = line),
            AssistantBlock(id = "b", kind = "text", content = "$line\n"),
        )
        val progress = text(line).put("display", "process")
        val row = JSONArray().put(progress).put(text(line)).put(text("$line\n")).toString()
        val occurrence = AssistantReplyText.textOccurrence(blocks, "b", line)
        assertEquals(1, occurrence)
        assertEquals(-1, AssistantReplyText.textOccurrence(blocks, "p", line))
        val updated = AssistantReplyText.replaceVisibleOccurrence(listOf("row" to row), line, "样本好了。", occurrence)!!
        val parts = JSONArray(updated.second)
        assertEquals(progress.toString(), parts.getJSONObject(0).toString())
        assertEquals(line, parts.getJSONObject(1).getString("value"))
        assertEquals("样本好了。", parts.getJSONObject(2).getString("value"))
    }

    private fun text(value: String) =
        org.json.JSONObject().put("type", "text").put("value", value)

    private fun tool(name: String) =
        org.json.JSONObject().put("type", "toolUse").put("value", org.json.JSONObject().put("name", name))
}
