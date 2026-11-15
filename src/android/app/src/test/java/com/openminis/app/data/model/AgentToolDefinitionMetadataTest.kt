package com.openminis.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Metadata-compatibility regression for the §4.1 port (pi coding-agent
 * extensions/types.ts ToolDefinition + ToolAnnotations): the new
 * exposure / annotations / promptSnippet fields must default to null, must
 * never leak into the provider wire JSON (Anthropic / Gemini / OpenAI), and
 * the isDestructive()/isReadOnly() helpers must treat "unknown" (null) as
 * false.
 *
 * AgentToolDefinition is runtime-only metadata on this side: there is no Room
 * entity and no fromJson for the class itself (plugin tools build it from
 * their own JSON in LocalToolPluginStore), so the provider wire format below
 * IS the serialization surface these tests pin — a field that ever reaches
 * it changes every provider request byte-for-byte.
 */
class AgentToolDefinitionMetadataTest {

    private fun bareDefinition() = AgentToolDefinition(
        name = "test_tool",
        description = "A test tool.",
        parameters = mapOf("arg" to AgentToolParam("string", "An argument.")),
        required = listOf("arg"),
        propertyOrdering = listOf("arg"),
    )

    // ── default-value compatibility ──

    @Test
    fun metadataFieldsDefaultToNull() {
        val def = bareDefinition()
        assertNull(def.exposure)
        assertNull(def.annotations)
        assertNull(def.promptSnippet)
    }

    @Test
    fun exposureNullIsTheDirectCompatibilityValue() {
        // Old data / old call sites carry no exposure; null must remain the
        // "direct" default so Phase D can distinguish "unset" from explicit.
        assertEquals(null, bareDefinition().exposure)
    }

    @Test
    fun toolAnnotationsFieldsAreIndividuallyNullable() {
        val empty = ToolAnnotations()
        assertNull(empty.readOnlyHint)
        assertNull(empty.destructiveHint)
        assertNull(empty.idempotentHint)
        assertNull(empty.openWorldHint)

        val partial = ToolAnnotations(destructiveHint = true)
        assertNull(partial.readOnlyHint)
        assertTrue(partial.destructiveHint!!)
        assertNull(partial.idempotentHint)
        assertNull(partial.openWorldHint)
    }

    @Test
    fun convenienceHelpersTreatUnknownAsFalse() {
        val unknown = bareDefinition()
        assertFalse(unknown.isDestructive())
        assertFalse(unknown.isReadOnly())

        val destructive = bareDefinition().copy(
            annotations = ToolAnnotations(destructiveHint = true),
        )
        assertTrue(destructive.isDestructive())
        assertFalse(destructive.isReadOnly())

        val readOnly = bareDefinition().copy(
            annotations = ToolAnnotations(readOnlyHint = true),
        )
        assertTrue(readOnly.isReadOnly())
        assertFalse(readOnly.isDestructive())
    }

    // ── wire-format compatibility (the serialization surface) ──

    @Test
    fun anthropicWireJsonIgnoresMetadata() {
        val bare = bareDefinition()
        val withMetadata = bare.copy(
            exposure = "direct",
            annotations = ToolAnnotations(readOnlyHint = true, destructiveHint = false),
            promptSnippet = "- test_tool: does something.",
        )
        assertEquals(bare.toAnthropicJson().toString(), withMetadata.toAnthropicJson().toString())
        val json = withMetadata.toAnthropicJson()
        assertEquals("test_tool", json.getString("name"))
        assertEquals("A test tool.", json.getString("description"))
        assertFalse(json.has("exposure"))
        assertFalse(json.has("annotations"))
        assertFalse(json.has("promptSnippet"))
    }

    @Test
    fun geminiWireJsonIgnoresMetadata() {
        val bare = bareDefinition()
        val withMetadata = bare.copy(
            exposure = "deferred",
            annotations = ToolAnnotations(openWorldHint = true),
            promptSnippet = "- test_tool: does something.",
        )
        assertEquals(bare.toGeminiJson().toString(), withMetadata.toGeminiJson().toString())
        val json = withMetadata.toGeminiJson()
        assertEquals("test_tool", json.getString("name"))
        assertFalse(json.has("exposure"))
        assertFalse(json.has("annotations"))
        assertFalse(json.has("promptSnippet"))
    }

    @Test
    fun openAIWireJsonIgnoresMetadata() {
        val bare = bareDefinition()
        val withMetadata = bare.copy(
            exposure = "hidden",
            annotations = ToolAnnotations(readOnlyHint = false, destructiveHint = true),
            promptSnippet = "- test_tool: does something.",
        )
        assertEquals(bare.toOpenAIJson().toString(), withMetadata.toOpenAIJson().toString())
        val json = withMetadata.toOpenAIJson()
        assertEquals("test_tool", json.getJSONObject("function").getString("name"))
        assertFalse(json.has("exposure"))
        assertFalse(json.has("annotations"))
        assertFalse(json.has("promptSnippet"))
    }
}
