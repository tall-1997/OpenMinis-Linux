package com.openminis.app.tools

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-lanes-inherit-session-rules] The lane inheritance contract: a dispatched
 * worker's system prompt carries the parent session's standing rules under an
 * explicit header; null/blank rules keep the worker prompt byte-identical to
 * the pre-inheritance shape.
 */
class SubAgentInheritedRulesTest {

    @Test
    fun `rules ride under the inherited header`() {
        val line = inheritedRulesLine(
            "[STANDING_RULES · authority:user-authored …]\n所有提交走标准流程",
        )
        assertTrue(
            "the block must name the parent-conversation authority",
            line!!.startsWith("Standing rules inherited from the parent conversation"),
        )
        assertTrue(line.contains("[STANDING_RULES"))
        assertTrue("the block must close with a blank line", line.endsWith("\n\n"))
    }

    @Test
    fun `null and blank rules produce no block`() {
        assertNull(inheritedRulesLine(null))
        assertNull(inheritedRulesLine(""))
        assertNull(inheritedRulesLine("   \n  "))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        val line = inheritedRulesLine("  rule one  \n ")!!
        assertTrue(line.contains("rule one"))
        assertTrue(!line.contains("  rule one  \n"))
    }
}
