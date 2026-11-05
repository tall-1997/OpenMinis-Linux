package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-prompt-precedence] The ladder has to be visible in the text the model reads,
 * and only the weakest level needs framing.
 */
class PromptPrecedenceTest {

    @Test
    fun externalMaterialIsFramedAndMarkedLowest() {
        val framed = PromptPrecedence.frame(PromptLevel.EXTERNAL, "the parser lives in Foo.kt")
        assertTrue(framed.startsWith("<external_data precedence=\"5\">"))
        assertTrue(framed.endsWith("</external_data>"))
        assertTrue(framed.contains("the parser lives in Foo.kt"))
        assertEquals(PromptLevel.EXTERNAL, PromptPrecedence.declaredLevel(framed))
    }

    @Test
    fun strongerLevelsAreLeftAlone() {
        // Tool-enforced facts are already the model's instructions; wrapping them
        // would spend tokens to say nothing.
        for (level in listOf(
            PromptLevel.TOOL_ENFORCED,
            PromptLevel.BUILTIN,
            PromptLevel.MATCHED_RULE,
            PromptLevel.TEAM,
        )) {
            val body = "<work_report>\nstage: working\n</work_report>"
            assertEquals(body, PromptPrecedence.frame(level, body))
            assertNull(PromptPrecedence.declaredLevel(body))
        }
    }

    @Test
    fun blankBodiesAreNotWrapped() {
        assertEquals("", PromptPrecedence.frame(PromptLevel.EXTERNAL, ""))
        assertEquals("   ", PromptPrecedence.frame(PromptLevel.EXTERNAL, "   "))
    }

    @Test
    fun theRuleOnlyAppearsWhenExternalMaterialDoes() {
        assertEquals(PromptPrecedence.EXTERNAL_RULE, PromptPrecedence.ruleFor(true))
        assertNull(PromptPrecedence.ruleFor(false))
        assertTrue(PromptPrecedence.EXTERNAL_RULE.contains("not instructions"))
    }

    @Test
    fun ranksAreStrictlyOrdered() {
        val ranks = PromptLevel.entries.map { it.rank }
        assertEquals(ranks.sorted(), ranks)
        assertEquals(ranks.distinct(), ranks)
        assertEquals(1, PromptLevel.TOOL_ENFORCED.rank)
        assertEquals(5, PromptLevel.EXTERNAL.rank)
    }
}
