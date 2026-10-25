package com.openminis.app.data.display

import com.openminis.app.data.body.BudgetDecision
import com.openminis.app.data.body.PreviewBudget
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayPartsTest {
    @Test
    fun `projected stub and oversized preview need the real body`() {
        val stub = """[{"type":"text","text":"[body kept on disk]"}]"""
        assertTrue(DisplayParts.needsHydration("abc", 9000, stub))
        assertTrue(DisplayParts.needsHydration(null, 9000, stub))
        assertFalse(DisplayParts.needsHydration(null, 100, """[{"type":"text","value":"hi"}]"""))
    }

    @Test
    fun `stub text key becomes the value the bubble reads`() {
        val shown = JSONArray(DisplayParts.shrink("""[{"type":"text","text":"hello"}]"""))
        assertEquals("hello", shown.getJSONObject(0).getString("value"))
    }

    @Test
    fun `shrink keeps a complete array and the tail of a huge tool result`() {
        val huge = "x".repeat(DisplayParts.TOOL_KEEP_CHARS + 50)
        val raw = """[{"type":"toolResult","value":{"output":"$huge"}}]"""
        val shown = JSONArray(DisplayParts.shrink(raw))
        val output = shown.getJSONObject(0).getJSONObject("value").getString("output")
        assertTrue(output.endsWith("x".repeat(DisplayParts.TOOL_KEEP_CHARS)))
        assertTrue(output.length < huge.length)
    }

    @Test
    fun `broken json becomes a visible note instead of an empty bubble`() {
        val shown = JSONArray(DisplayParts.shrink("{not-json"))
        assertEquals("text", shown.getJSONObject(0).getString("type"))
        assertTrue(shown.getJSONObject(0).getString("value").isNotBlank())
    }

    @Test
    fun `budget keeps the boundary row then stops`() {
        assertEquals(
            BudgetDecision.TAKE_AND_STOP,
            PreviewBudget.decide(used = 0, rowBytes = 2_000_000, budget = 1024, alreadyTaken = 0),
        )
        assertEquals(
            BudgetDecision.STOP,
            PreviewBudget.decide(used = 100, rowBytes = 2_000_000, budget = 1024, alreadyTaken = 1),
        )
    }
}
