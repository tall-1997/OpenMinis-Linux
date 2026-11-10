package com.openminis.app.ui.chat

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
        val keys = ToolPromptFragments.ALL.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `every fragment renders as a bullet line naming its key`() {
        val rendered = ToolPromptFragments.render()
        for (f in ToolPromptFragments.ALL) {
            assertTrue(
                "fragment ${f.key} must render as a bullet naming its tool",
                rendered.contains("- ${f.key}:"),
            )
        }
    }

    @Test
    fun `ui_skill guidance keeps the replay-before-manual order`() {
        val uiSkill = ToolPromptFragments.ALL.first { it.key == "ui_skill" }
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
}
