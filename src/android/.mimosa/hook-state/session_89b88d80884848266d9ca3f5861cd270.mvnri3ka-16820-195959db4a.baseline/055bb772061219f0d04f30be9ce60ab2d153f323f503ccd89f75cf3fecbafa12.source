package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-android-visual-top] The orientation contract the whole timeline depends
 * on: under `reverseLayout=true` with `items(flatItems.asReversed())`,
 * index 0 is the NEWEST row and paints at the visual BOTTOM, so the visual
 * top of the screen is the HIGHEST lazy index. Both Reading-anchor capture
 * and the up-button's first-tap resolution go through these helpers, so a
 * second contradictory selector cannot appear again.
 */
class VisibleTopRowTest {

    /** Visible rows ordered like a real mid-history viewport: index ascends upward. */
    private val rows: List<ChatHistoryWindow.VisibleRow> = listOf(
        ChatHistoryWindow.VisibleRow(index = 4, key = "user:u2", offset = 1_403, size = 120),
        ChatHistoryWindow.VisibleRow(index = 6, key = "mdblock:a2:x:0", offset = 1_530, size = 90),
        ChatHistoryWindow.VisibleRow(index = 9, key = "user:u3", offset = 1_640, size = 120),
        ChatHistoryWindow.VisibleRow(index = 10, key = "__load_older__", offset = 1_700, size = 48),
    )

    @Test
    fun `visual top is the highest non-synthetic index`() {
        val top = ChatHistoryWindow.visibleTopRow(rows)
        assertEquals("user:u3", top?.key)
    }

    @Test
    fun `synthetic rows never anchor`() {
        // The load-older pill sits at the highest index (oldest end) but must
        // not become the anchor.
        val pillOnly = listOf(
            ChatHistoryWindow.VisibleRow(index = 7, key = "__load_older__", offset = 10, size = 48),
        )
        assertNull(ChatHistoryWindow.visibleTopRow(pillOnly))
    }

    @Test
    fun `fully visible user ids respect the viewport bounds`() {
        val ids = ChatHistoryWindow.fullyVisibleUserIds(
            rows,
            viewportStart = 0,
            viewportEnd = 1_500,
        )
        // u2's bubble (offset 1403 + size 120 = 1523) pokes past 1500 → not fully visible.
        assertEquals(emptySet<String>(), ids)
        val ids2 = ChatHistoryWindow.fullyVisibleUserIds(
            rows,
            viewportStart = 1_000,
            viewportEnd = 1_800,
        )
        assertEquals(setOf("u2", "u3"), ids2)
    }

    @Test
    fun `dedupe suffixes never leak into parsed user ids`() {
        val withSuffix = listOf(
            ChatHistoryWindow.VisibleRow(index = 3, key = "user:u1#2", offset = 100, size = 50),
        )
        val ids = ChatHistoryWindow.fullyVisibleUserIds(withSuffix, 0, 1_000)
        assertEquals(setOf("u1"), ids)
    }
}
