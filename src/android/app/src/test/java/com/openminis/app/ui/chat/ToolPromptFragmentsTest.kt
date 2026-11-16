package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentToolDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the [ToolPromptFragments] registry: every registered fragment reaches
 * the rendered prompt, keys stay unique, and the ui_skill fragment keeps its
 * replay-before-manual guidance (the exact prose whose absence made the
 * tool invisible to the model — see AgentTools' [T-ui-skill-prompt-fragment-dead]
 * history note).
 */
class ToolPromptFragmentsTest {

    @Test
    fun `keys are unique`() {
        val keys = ToolPromptFragments.allFragments.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `every fragment renders as a bullet line naming its key`() {
        val rendered = ToolPromptFragments.render()
        for (f in ToolPromptFragments.allFragments) {
            assertTrue(
                "fragment ${f.key} must render as a bullet naming its tool",
                rendered.contains("- ${f.key}:"),
            )
        }
    }

    @Test
    fun `ui_skill guidance keeps the replay-before-manual order`() {
        val uiSkill = ToolPromptFragments.allFragments.first { it.key == "ui_skill" }
        val matchIdx = uiSkill.bullet.indexOf("action=match")
        val manualIdx = uiSkill.bullet.indexOf("manual ui_read+ui_action")
        assertTrue(matchIdx in 0 until manualIdx)
    }

    @Test
    fun `excluded keys are dropped from the render`() {
        val rendered = ToolPromptFragments.render(excluded = setOf("ui_skill"))
        assertFalse(rendered.contains("ui_skill"))
    }

    @Test
    fun `render is stable across calls`() {
        assertEquals(ToolPromptFragments.render(), ToolPromptFragments.render())
    }

    // ── §4.1 definition-side aggregation ──

    @Test
    fun `renderFromDefinitions emits definition snippets and registry fallback`() {
        val definitions = listOf(
            AgentToolDefinition(
                name = "future_tool",
                description = "A tool carrying its own bullet.",
                parameters = emptyMap(),
                promptSnippet = "- future_tool: does the new thing.",
            ),
        )
        val rendered = ToolPromptFragments.renderFromDefinitions(definitions)
        assertTrue(rendered.contains("- future_tool: does the new thing."))
        // Registry tools without a definition snippet stay rendered.
        assertTrue(rendered.contains("- ui_skill:"))
    }

    @Test
    fun `renderFromDefinitions does not double-render a migrated fragment`() {
        // ui_skill moved onto the definition: the registry copy must drop out.
        val definitions = listOf(
            AgentToolDefinition(
                name = "ui_skill",
                description = "The migrated definition.",
                parameters = emptyMap(),
                promptSnippet = "- ui_skill: migrated bullet.",
            ),
        )
        val rendered = ToolPromptFragments.renderFromDefinitions(definitions)
        assertTrue(rendered.contains("migrated bullet"))
        assertEquals(1, rendered.split("- ui_skill:").size - 1)
    }

    @Test
    fun `renderFromDefinitions honours the excluded set`() {
        val definitions = listOf(
            AgentToolDefinition(
                name = "future_tool",
                description = "Gated off this turn.",
                parameters = emptyMap(),
                promptSnippet = "- future_tool: hidden now.",
            ),
        )
        val rendered = ToolPromptFragments.renderFromDefinitions(definitions, excluded = setOf("future_tool"))
        assertFalse(rendered.contains("future_tool"))
        // Registry fragment excluded by key too.
        val registryExcluded = ToolPromptFragments.renderFromDefinitions(emptyList(), excluded = setOf("ui_skill"))
        assertFalse(registryExcluded.contains("ui_skill"))
    }

    @Test
    fun `renderFromDefinitions with no snippets falls back to the registry`() {
        val definitions = listOf(
            AgentToolDefinition(name = "plain_tool", description = "No snippet.", parameters = emptyMap()),
        )
        val rendered = ToolPromptFragments.renderFromDefinitions(definitions)
        assertEquals(ToolPromptFragments.render(), rendered)
    }
}
