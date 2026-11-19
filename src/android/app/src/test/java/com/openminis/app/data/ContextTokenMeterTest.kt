package com.openminis.app.data

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.ToolAnnotations
import com.openminis.app.provider.ToolJsonRepair
import com.openminis.app.provider.api.ChatRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextTokenMeterTest {
    private val meter = ContextTokenMeter

    @Test
    fun `structured parts take precedence over history mirrors`() {
        val bytes = byteArrayOf(1, 2)
        val message = LLMMessage(
            role = LLMMessage.Role.USER,
            content = "mirror".repeat(500),
            contentParts = listOf(
                AgentContentPart.Text("actual"),
                AgentContentPart.ImageData(bytes, "image/png"),
                AgentContentPart.ToolResult("r1", "read_image", "pixels", imageData = byteArrayOf(3), imageMimeType = "image/png"),
            ),
            imageParts = listOf(LLMMessage.ImagePart(bytes.copyOf(), "image/png")),
            audioParts = listOf(LLMMessage.AudioPart("wav", "a".repeat(8_000))),
        )
        val expected = meter.rawTextTokens("actual") + meter.rawTextTokens("pixels") + 2 * meter.IMAGE_TOKENS
        val stripped = message.copy(content = "", imageParts = emptyList(), audioParts = emptyList(), dbMessageId = "storage-only")
        assertEquals(expected, meter.rawMessageTokens(message))
        assertEquals(meter.requestFingerprint(listOf(stripped)), meter.requestFingerprint(listOf(message)))
    }

    @Test
    fun `legacy attachments and separate request images have a media cost`() {
        val image = LLMMessage.ImagePart(byteArrayOf(1), "image/png")
        val audio = LLMMessage.AudioPart("wav", "a".repeat(8_000))
        val legacy = LLMMessage(LLMMessage.Role.USER, "abcd", imageParts = listOf(image), audioParts = listOf(audio))
        val expected = 1 + meter.IMAGE_TOKENS + 8
        assertEquals(expected, meter.rawMessageTokens(legacy))
        assertEquals(expected, meter.rawRequestTokens(listOf(legacy), imageParts = listOf(image)))

        val structured = legacy.copy(contentParts = listOf(AgentContentPart.Text("abcd"), AgentContentPart.ImageData(image.data, image.mimeType)))
        val extra = image.copy(data = byteArrayOf(2))
        assertEquals(1 + 2 * meter.IMAGE_TOKENS, meter.rawRequestTokens(listOf(structured), imageParts = listOf(image, extra)))
    }

    @Test
    fun `whole request measures system and nested advertised schema but not registry metadata`() {
        val params = linkedMapOf("options" to AgentToolParam("object", "nested"))
        val tool = AgentToolDefinition("lookup", "Find item", params, required = listOf("options"))
        val req = ChatRequest(messages = listOf(LLMMessage(LLMMessage.Role.USER, "数一数")), maxTokens = 100)
        val request = req.copy(systemPrompt = "已有规则", tools = listOf(tool))
        val withoutTools = meter.rawRequestTokens(req.copy(systemPrompt = request.systemPrompt))
        val raw = meter.rawRequestTokens(request)
        val digest = meter.requestFingerprint(request)
        assertEquals(meter.rawMessageTokens(req.messages.single()) + meter.rawTextTokens("已有规则"), withoutTools)
        assertTrue(raw > withoutTools)
        assertEquals(raw, meter.rawRequestTokens(request.messages, request.systemPrompt, request.tools))
        assertEquals(digest, meter.requestFingerprint(request.messages, request.systemPrompt, request.tools))
        assertEquals(digest, meter.requestFingerprint(request.copy(maxTokens = 200, tools = listOf(tool.copy(
            exposure = "hidden", annotations = ToolAnnotations(readOnlyHint = true), promptSnippet = "not a schema field",
        )))))
        params["options"] = AgentToolParam("object", "nested", properties = mapOf(
            "filters" to AgentToolParam("array", "筛选", items = AgentToolParam("string", "分类", enumValues = listOf("甲", "乙"))),
        ), required = listOf("filters"))
        assertTrue(meter.rawRequestTokens(request) > raw)
        assertNotEquals(digest, meter.requestFingerprint(request))
    }

    @Test
    fun `fingerprint changes for same size text and media replacement`() {
        val bytes = byteArrayOf(1, 2, 3)
        val image = AgentContentPart.ImageData(bytes, "image/png")
        val message = LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(AgentContentPart.Text("left"), image))
        val original = meter.requestFingerprint(listOf(message), systemPrompt = "rules")
        val changedText = message.copy(contentParts = listOf(AgentContentPart.Text("next"), image))
        assertEquals(meter.rawMessageTokens(message), meter.rawMessageTokens(changedText))
        assertNotEquals(original, meter.requestFingerprint(listOf(changedText), systemPrompt = "rules"))
        assertNotEquals(original, meter.requestFingerprint(listOf(message), systemPrompt = "scope"))
        bytes[1] = 7
        assertNotEquals(original, meter.requestFingerprint(listOf(message), systemPrompt = "rules"))
    }

    @Test
    fun `live input reacts to changes through a nested object and array`() {
        val args = JSONObject("""{"items":[{"text":"abc"}]}""")
        val part = AgentContentPart.ToolUse("c1", "lookup", args)
        val message = LLMMessage(LLMMessage.Role.ASSISTANT, "", contentParts = listOf(part))
        val raw = meter.rawMessageTokens(message)
        val fingerprint = meter.requestFingerprint(listOf(message))
        val nested = args.getJSONArray("items").getJSONObject(0)
        nested.put("text", "xxx")
        assertEquals(raw, meter.rawMessageTokens(message))
        assertNotEquals(fingerprint, meter.requestFingerprint(listOf(message)))
        nested.put("text", "字".repeat(1_000))
        assertTrue(meter.rawMessageTokens(message) > raw)
        assertNull(part.inputJsonSnapshot)
    }

    @Test
    fun `published input is the repaired JSON and cannot drift after publication`() {
        val definition = AgentToolDefinition("lookup", "", mapOf("path" to AgentToolParam("string", "")), required = listOf("path"))
        val args = JSONObject("""{"file_path":"/a","items":[{"value":"original"}]}""")
        val live = AgentContentPart.ToolUse("c1", "lookup", args)
        assertTrue(live.inputJson.contains("file_path"))
        assertNull(live.inputJsonSnapshot)
        assertTrue(ToolJsonRepair.repair("lookup", args, null, listOf(definition)).isNotEmpty())
        val repaired = args.toString()
        val part = AgentContentPart.ToolUse.fromInputJson("c1", "lookup", repaired, thoughtSignature = "sig")
        val message = LLMMessage(LLMMessage.Role.ASSISTANT, "", contentParts = listOf(part))
        val fingerprint = meter.requestFingerprint(listOf(message))
        val raw = meter.rawMessageTokens(message)

        args.getJSONArray("items").getJSONObject(0).put("value", "new original")
        part.input.getJSONArray("items").getJSONObject(0).put("value", "new clone")
        assertEquals("/a", part.input.getString("path"))
        assertEquals("original", part.input.getJSONArray("items").getJSONObject(0).getString("value"))
        assertSame(repaired, part.inputJson)
        assertSame(repaired, part.copy(id = "wire_c1").inputJsonSnapshot)
        assertEquals("sig", part.copy(id = "wire_c1").thoughtSignature)
        val replaced = part.copy(input = args)
        assertNull(replaced.inputJsonSnapshot)
        assertSame(args, replaced.input)
        assertEquals(fingerprint, meter.requestFingerprint(listOf(message)))
        assertEquals(raw, meter.rawMessageTokens(message))
    }
}
