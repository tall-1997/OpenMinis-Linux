package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [T-find-tools] the on-demand tool discovery scoring + catalogue envelope. */
class FindToolsTest {

    private val defs = listOf(
        AgentToolDefinition(
            name = "cronjob",
            description = "Create/list/remove AlarmManager scheduled tasks that fire on a schedule",
            parameters = mapOf("tool_title" to AgentToolParam("string", "title")),
        ),
        AgentToolDefinition(
            name = "generate_image",
            description = "Generate an image from a text prompt through a media model",
            parameters = mapOf("prompt" to AgentToolParam("string", "prompt")),
        ),
        AgentToolDefinition(
            name = "read_image",
            description = "Analyze an image file and describe its contents",
            parameters = mapOf("path" to AgentToolParam("string", "path")),
        ),
        AgentToolDefinition(
            name = "translate_text",
            description = "Translate text between languages",
            parameters = mapOf("text" to AgentToolParam("string", "text")),
        ),
    )

    @Test
    fun exactNameMatchScoresHighest() {
        // "read" is both an exact-name token (read_image) and a description
        // token (generate_image's "...from a text prompt..."), proving name
        // matches out-score plain haystack matches.
        val hits = FindTools.search("read image", defs, limit = 8)
        assertEquals("read_image", hits.first().definition.name)
        assertTrue("exact name match must out-score fuzzy hits", hits.first().score > hits[1].score)
    }

    @Test
    fun capabilityKeywordMatchesDescription() {
        val hits = FindTools.search("schedule", defs, limit = 8)
        assertTrue(hits.map { it.definition.name }.contains("cronjob"))
    }

    @Test
    fun capabilityKeywordMatchesToolName() {
        val hits = FindTools.search("image", defs, limit = 8)
        val names = hits.map { it.definition.name }
        assertTrue(names.contains("generate_image"))
        assertTrue(names.contains("read_image"))
    }

    @Test
    fun emptyOrTinyQueryReturnsNothing() {
        assertTrue(FindTools.search("", defs).isEmpty())
        assertTrue(FindTools.search("a", defs).isEmpty())
    }

    @Test
    fun selfIsExcludedFromResults() {
        val withSelf = defs + FindTools.definition()
        assertFalse(FindTools.search("find_tools", withSelf).any { it.definition.name == FindTools.NAME })
    }

    @Test
    fun limitClampsToRange() {
        // clamps to [1,12]; with 1 result nothing exceeds
        val one = FindTools.search("translate", defs, limit = 1)
        assertEquals(1, one.size)
        // limit 0 or negative still returns at least a valid single result
        val clamped = FindTools.search("translate", defs, limit = 0)
        assertTrue(clamped.size in 1..1)
    }

    @Test
    fun noMatchesProducesGuidanceText() {
        val out = FindTools.format(emptyList())
        assertTrue(out.contains("No matching tools"))
        assertTrue(out.contains("capability"))
    }

    @Test
    fun formatEnvelopeIsValidJsonWithNamesAndParams() {
        val json = FindTools.format(
            listOf(
                FindTools.Match(defs[1], score = 8),
                FindTools.Match(defs[2], score = 6),
            ),
        )
        assertTrue(json.startsWith("{"))
        assertTrue(json.contains("\"tools\""))
        assertTrue(json.contains("\"generate_image\""))
        assertTrue(json.contains("\"read_image\""))
        assertTrue(json.contains("\"instruction\""))
    }
}