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
            listOf("runtime/work_report", "runtime/in_flight", "runtime/jobs", "runtime/plan_board", "runtime/recall"),
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

    /**
     * [T-work-report-staleness] Regression guard: the work-report block must
     * stay TOOL_ENFORCED (its question/blocker gating stops the run for a
     * person), and must NOT gain the external-evidence frame — its staleness
     * is handled inside the block itself (age + caveat + WORKING expiry), not
     * by demoting it to "mere evidence".
     */
    @Test
    fun workReportStaysToolEnforcedAndUnframed() {
        assertEquals(PromptLevel.TOOL_ENFORCED, PromptParts.levelFor("runtime/work_report"))
        val out = PromptParts.render(
            listOf(part("runtime/work_report", PromptParts.levelFor("runtime/work_report"), "stage: working")),
        )
        assertEquals(1, out.size)
        assertTrue(!out[0].contains("<external_data"))
    }

    @Test
    fun theRegistryLevelTableMatchesKeys() {
        // Every key must resolve to a level (getValue throws on drift) and
        // recall stays the only EXTERNAL part.
        PromptParts.KEYS.forEach { key -> PromptParts.levelFor(key) }
        val external = PromptParts.KEYS.filter { PromptParts.levelFor(it) == PromptLevel.EXTERNAL }
        assertEquals(listOf("runtime/recall"), external)
    }
}
