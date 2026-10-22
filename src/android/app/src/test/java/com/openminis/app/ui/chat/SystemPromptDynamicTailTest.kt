package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the ordering of the per-turn tail of the system prompt.
 *
 * The regression these guard: WorldBook injection used to be concatenated into
 * the identity section at the very HEAD of the prompt. It is keyword-triggered
 * per turn, so one lorebook hit rewrote the prompt from byte zero and
 * invalidated the whole prefix cache. It must live in the dynamic tail, after
 * everything byte-stable.
 */
class SystemPromptDynamicTailTest {
    @Test
    fun worldBookComesFirstAmongDynamicAndRuntimeLast() {
        val tail =
            assembleDynamicTail(
                worldBookFragment = "\n\nWorld book (injected only because a keyword matched):\n- lore: x",
                learnedPrefsFragment = "LEARNED-PREFS",
                recalledMemoryFragment = "RECALLED-MEMORY",
                runtimeContext = renderRuntimeContext("2026-10-03", "Asia/Shanghai", "zh-CN", 14),
                personalityReminder = "PERSONALITY-REMINDER",
            )
        val wb = tail.indexOf("World book")
        val learned = tail.indexOf("LEARNED-PREFS")
        val recall = tail.indexOf("RECALLED-MEMORY")
        val runtime = tail.indexOf("Runtime context:")
        val reminder = tail.indexOf("PERSONALITY-REMINDER")
        assertTrue("world book must be present", wb >= 0)
        assertTrue("world book before learned prefs", wb < learned)
        assertTrue("learned before recall", learned < recall)
        assertTrue("recall before runtime context", recall < runtime)
        assertTrue("runtime context last but for the reminder", runtime < reminder)
    }

    @Test
    fun absentFragmentsLeaveNoSeparatorsBehind() {
        val tail =
            assembleDynamicTail(
                worldBookFragment = "",
                learnedPrefsFragment = null,
                recalledMemoryFragment = null,
                runtimeContext = renderRuntimeContext("d", "tz", "l", 1),
                personalityReminder = null,
            )
        assertEquals(
            "\n\nRuntime context:\n" +
                "- Current date: d (tz)\n" +
                "- Device language: l\n" +
                "- minis-model-use models available: 1",
            tail,
        )
        assertFalse(tail.contains("\n\n\n"))
    }

    @Test
    fun blankRecallIsDroppedButEmptyStringWorldBookIsNotASection() {
        val withBlankRecall =
            assembleDynamicTail("", "L", "   ", renderRuntimeContext("d", "t", "l", 0), null)
        assertFalse(withBlankRecall.contains("   \n"))
        assertEquals(withBlankRecall, assembleDynamicTail("", "L", null, renderRuntimeContext("d", "t", "l", 0), null))
    }

    @Test
    fun runtimeFieldOrderIsDateThenTzThenLangThenCount() {
        val rt = renderRuntimeContext("2026-10-03", "UTC", "en-US", 3)
        val date = rt.indexOf("2026-10-03")
        val tz = rt.indexOf("(UTC)")
        val lang = rt.indexOf("en-US")
        val count = rt.indexOf("available: 3")
        assertTrue(date in 0 until tz)
        assertTrue(tz < lang)
        assertTrue(lang < count)
    }

    /**
     * [T-plan-mode-prompt-wiring] The plan fragment (mode flag + live board)
     * sits after the task anchor and before the runtime context: it is
     * per-turn session state, so it must live in the dynamic tail, but it is
     * more byte-stable than the date line below it.
     */
    @Test
    fun planFragmentSitsBetweenTaskAnchorAndRuntimeContext() {
        val tail =
            assembleDynamicTail(
                worldBookFragment = "",
                learnedPrefsFragment = null,
                recalledMemoryFragment = null,
                runtimeContext = renderRuntimeContext("d", "t", "l", 1),
                personalityReminder = null,
                taskAnchor = "ANCHOR",
                planFragment = "<plan_mode>PLAN</plan_mode>",
            )
        val anchor = tail.indexOf("ANCHOR")
        val plan = tail.indexOf("<plan_mode>")
        val runtime = tail.indexOf("Runtime context:")
        assertTrue("anchor before plan fragment", anchor in 0 until plan)
        assertTrue("plan fragment before runtime context", plan < runtime)
    }

    @Test
    fun nullPlanFragmentLeavesNoSeparatorBehind() {
        val tail =
            assembleDynamicTail(
                worldBookFragment = "",
                learnedPrefsFragment = null,
                recalledMemoryFragment = null,
                runtimeContext = renderRuntimeContext("d", "t", "l", 1),
                personalityReminder = null,
                taskAnchor = "ANCHOR",
                planFragment = null,
            )
        assertFalse(tail.contains("\n\n\n"))
        assertEquals(
            "\n\nANCHOR\n\nRuntime context:\n- Current date: d (t)\n- Device language: l\n- minis-model-use models available: 1",
            tail,
        )
    }
}
