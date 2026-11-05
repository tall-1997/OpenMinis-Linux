package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-prompt-parts] The registry is the only place that decides what is injected
 * and at which level; these pin the keys, the order and the framing.
 */
class PromptPartsTest {

    private fun part(key: String, level: PromptLevel, body: String?) =
        PromptPart(key, level) { body }

    @Test
    fun theRegistryOrderIsStable() {
        assertEquals(
            listOf("runtime/work_report", "runtime/in_flight", "runtime/plan_board", "runtime/recall"),
            PromptParts.KEYS,
        )
    }

    @Test
    fun driftingFromTheRegistryIsLoud() {
        val drifted = listOf(part("runtime/recall", PromptLevel.EXTERNAL, "x"))
        val error = runCatching { PromptParts.validate(drifted) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("drifted"))
    }

    @Test
    fun emptyPartsAreSkippedEntirely() {
        val parts = PromptParts.KEYS.mapIndexed { index, key ->
            part(key, PromptLevel.TOOL_ENFORCED, if (index == 0) "only this" else null)
        }
        val out = PromptParts.render(parts)
        assertEquals(listOf("only this"), out)
    }

    @Test
    fun externalContentIsFramedAndTheRulePrecedesIt() {
        val parts = PromptParts.KEYS.map { key ->
            if (key == "runtime/recall") {
                part(key, PromptLevel.EXTERNAL, "the parser lives in Foo.kt")
            } else {
                part(key, PromptLevel.TOOL_ENFORCED, null)
            }
        }
        val out = PromptParts.render(parts)
        assertEquals(2, out.size)
        assertEquals(PromptPrecedence.EXTERNAL_RULE, out[0])
        assertTrue(out[1].startsWith("<external_data"))
    }

    @Test
    fun theRuleIsAbsentWithoutExternalMaterial() {
        val parts = listOf(part("runtime/work_report", PromptLevel.TOOL_ENFORCED, "stage: working"))
        val out = PromptParts.render(parts)
        assertEquals(listOf("stage: working"), out)
    }

    @Test
    fun orderIsPreservedNotSorted() {
        val parts = listOf(
            part("runtime/work_report", PromptLevel.TOOL_ENFORCED, "first"),
            part("runtime/recall", PromptLevel.EXTERNAL, "last"),
        )
        val out = PromptParts.render(parts)
        assertEquals(PromptPrecedence.EXTERNAL_RULE, out[0])
        assertEquals("first", out[1])
        assertTrue(out[2].contains("last"))
    }
}
