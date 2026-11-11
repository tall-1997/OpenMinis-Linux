package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [GH#563] Long sessions must not keep answering from a stale "current date".
 * The runtime-context date is re-stamped per request in the dynamic tail; the
 * rest of the prompt (including the cache breakpoint before it) is untouched.
 */
class SystemPromptDateRefreshTest {

    private val prompt = "STABLE-HEAD\n\n" +
        "Runtime context:\n" +
        "- Current date: 2026-11-09 (Asia/Shanghai)\n" +
        "- Device language: zh-CN\n" +
        "- minis-model-use models available: 3"

    @Test
    fun `stale date is replaced and nothing else changes`() {
        val refreshed = refreshRuntimeContextDate(prompt, "2026-11-12")!!
        assertEquals(
            "STABLE-HEAD\n\n" +
                "Runtime context:\n" +
                "- Current date: 2026-11-12 (Asia/Shanghai)\n" +
                "- Device language: zh-CN\n" +
                "- minis-model-use models available: 3",
            refreshed,
        )
    }

    @Test
    fun `same date returns the exact same string instance`() {
        assertEquals(prompt, refreshRuntimeContextDate(prompt, "2026-11-09"))
    }

    @Test
    fun `prompt without a runtime context is unchanged`() {
        val other = "identity only"
        assertEquals(other, refreshRuntimeContextDate(other, "2026-11-12"))
    }

    @Test
    fun `null prompt stays null`() {
        assertNull(refreshRuntimeContextDate(null, "2026-11-12"))
    }

    @Test
    fun `only the runtime-context date is touched`() {
        val withDates = "Session started 2026-11-09.\n" +
            "- Current date: 2026-11-09 (UTC)\n" +
            "Remember 2026-11-09."
        val refreshed = refreshRuntimeContextDate(withDates, "2026-11-12")!!
        assertEquals(
            "Session started 2026-11-09.\n" +
                "- Current date: 2026-11-12 (UTC)\n" +
                "Remember 2026-11-09.",
            refreshed,
        )
    }

    /**
     * [GH#563] Memory / task-anchor text is appended BEFORE the runtime
     * context, so it can legitimately contain the same marker. The refresh
     * must edit the LAST occurrence (the real runtime line) and leave the
     * quoted memory untouched.
     */
    @Test
    fun `a marker inside recalled memory is not the one refreshed`() {
        val promptWithMemory = "STABLE-HEAD\n\n" +
            "Recalled memory (quoted):\n" +
            "- Current date: 2020-01-01 (UTC)\n" +
            "Runtime context:\n" +
            "- Current date: 2026-11-09 (Asia/Shanghai)\n" +
            "- Device language: zh-CN"
        val refreshed = refreshRuntimeContextDate(promptWithMemory, "2026-11-12")!!
        assertEquals(
            "STABLE-HEAD\n\n" +
                "Recalled memory (quoted):\n" +
                "- Current date: 2020-01-01 (UTC)\n" +
                "Runtime context:\n" +
                "- Current date: 2026-11-12 (Asia/Shanghai)\n" +
                "- Device language: zh-CN",
            refreshed,
        )
    }
}
