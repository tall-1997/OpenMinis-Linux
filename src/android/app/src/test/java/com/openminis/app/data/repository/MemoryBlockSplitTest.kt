package com.openminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GH#357] The cache-prefix split: standing rules stay byte-stable, the daily
 * window (which every memory_write changes) goes to the dynamic tail.
 */
class MemoryBlockSplitTest {

    private fun fragment(key: String, text: String, priority: Int = 10) =
        MemoryPromptBudget.Fragment(key = key, text = text, priority = priority, truncatable = true)

    @Test
    fun `standing rules and daily window are separated`() {
        val allocation = MemoryPromptBudget.Allocation(
            included = listOf(
                fragment("project-rules", "PROJECT"),
                fragment("session-global", "SESSION"),
                fragment("app-global", "GLOBAL"),
                fragment(MemoryAssembly.HEADER_KEY, "HEADER"),
                fragment("${MemoryAssembly.DAILY_KEY_PREFIX}2026-11-11", "DAY"),
                fragment(MemoryAssembly.INDEX_KEY, "INDEX"),
            ),
            droppedKeys = emptyList(),
            truncatedKeys = emptyList(),
            usedChars = 100,
        )
        val split = MemoryBlockSplit.split(allocation)
        assertEquals("PROJECT\n\nSESSION\n\nGLOBAL", split.stable)
        assertEquals("HEADER\n\nDAY\n\nINDEX", split.transient)
    }

    @Test
    fun `daily-only allocation leaves no stable block`() {
        val allocation = MemoryPromptBudget.Allocation(
            included = listOf(fragment("${MemoryAssembly.DAILY_KEY_PREFIX}2026-11-11", "DAY")),
            droppedKeys = emptyList(),
            truncatedKeys = emptyList(),
            usedChars = 3,
        )
        val split = MemoryBlockSplit.split(allocation)
        assertNull(split.stable)
        assertEquals("DAY", split.transient)
    }

    @Test
    fun `null allocation yields no blocks`() {
        val split = MemoryBlockSplit.split(null)
        assertNull(split.stable)
        assertNull(split.transient)
    }

    @Test
    fun `key classification is exactly the daily group`() {
        assertTrue(MemoryBlockSplit.isTransientKey("daily-2026-11-11"))
        assertTrue(MemoryBlockSplit.isTransientKey(MemoryAssembly.INDEX_KEY))
        assertTrue(MemoryBlockSplit.isTransientKey(MemoryAssembly.HEADER_KEY))
        assertFalse(MemoryBlockSplit.isTransientKey("app-global"))
        assertFalse(MemoryBlockSplit.isTransientKey("project-rules"))
        assertFalse(MemoryBlockSplit.isTransientKey("session-global"))
    }
}
