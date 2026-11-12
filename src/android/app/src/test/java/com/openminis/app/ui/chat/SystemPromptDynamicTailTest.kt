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
     * [T-plan-board-live] The board has no system-prompt slot anymore: the
     * frozen per-run copy duplicated the per-turn rider on the last user
     * message (ChatViewModelPlanContextExt), so the anchor is the last
     * stateful fragment before the runtime context.
     */
    @Test
    fun taskAnchorSitsDirectlyBeforeRuntimeContext() {
        val tail =
            assembleDynamicTail(
                worldBookFragment = "",
                learnedPrefsFragment = null,
                recalledMemoryFragment = null,
                runtimeContext = renderRuntimeContext("d", "t", "l", 1),
                personalityReminder = null,
                taskAnchor = "ANCHOR",
            )
        assertFalse(tail.contains("\n\n\n"))
        assertEquals(
            "\n\nANCHOR\n\nRuntime context:\n- Current date: d (t)\n- Device language: l\n- minis-model-use models available: 1",
            tail,
        )
    }

    /**
     * [GH#357] The daily-memory window must live in this dynamic tail, after
     * recall and before the task anchor. It used to be concatenated into the
     * byte-stable head, so every `memory_write` (which touches today's daily
     * log) invalidated the whole cached prompt prefix.
     */
    @Test
    fun dailyMemoryWindowSitsInTheDynamicTailAfterRecall() {
        val tail =
            assembleDynamicTail(
                worldBookFragment = "",
                learnedPrefsFragment = "LEARNED",
                recalledMemoryFragment = "RECALL",
                dailyMemoryFragment = "DAILY-WINDOW",
                runtimeContext = renderRuntimeContext("d", "t", "l", 1),
                personalityReminder = null,
                taskAnchor = "ANCHOR",
            )
        val learned = tail.indexOf("LEARNED")
        val recall = tail.indexOf("RECALL")
        val daily = tail.indexOf("DAILY-WINDOW")
        val anchor = tail.indexOf("ANCHOR")
        assertTrue("daily window present", daily >= 0)
        assertTrue("learned < recall", learned < recall)
        assertTrue("recall < daily", recall < daily)
        assertTrue("daily < anchor", daily < anchor)
        // No daily content → no stray separator (older callers unchanged).
        val withoutDaily =
            assembleDynamicTail("", "LEARNED", "RECALL", renderRuntimeContext("d", "t", "l", 1), null)
        assertFalse(withoutDaily.contains("\n\n\n"))
    }
}
