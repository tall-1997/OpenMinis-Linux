package com.openminis.app.quick

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [E-batch-quick-invocation] The screen-text traversal used by the floating
 * ball's double-tap capture: document order, dedup, own-package skipping and
 * the 4000-character cap. Pure JVM over the walker's node facade.
 */
class ScreenTextWalkerTest {

    private fun node(
        pkg: String?,
        text: String?,
        desc: String? = null,
        children: List<ScreenTextWalker.Node> = emptyList(),
    ): ScreenTextWalker.Node = object : ScreenTextWalker.Node {
        override val packageName: String? = pkg
        override val text: String? = text
        override val contentDescription: String? = desc
        override val children: List<ScreenTextWalker.Node> = children
    }

    @Test
    fun `collects fragments in document order joined by newlines`() {
        val tree = node(
            "com.app", null,
            children = listOf(
                node("com.app", "First"),
                node("com.app", "Second", desc = "Second button"),
                node("com.app", null, children = listOf(node("com.app", "Third"))),
            ),
        )
        assertEquals("First\nSecond\nSecond button\nThird", ScreenTextWalker().collect(listOf(tree)))
    }

    @Test
    fun `skips blank fragments and deduplicates repeats`() {
        val tree = node(
            "com.app", "Hello",
            children = listOf(
                node("com.app", "  "),
                node("com.app", "Hello"),           // same string, nested → dropped
                node("com.app", "", desc = "Hello"), // same via contentDescription → dropped
                node("com.app", "World"),
            ),
        )
        assertEquals("Hello\nWorld", ScreenTextWalker().collect(listOf(tree)))
    }

    @Test
    fun `own package fragments never leak into the capture`() {
        val tree = node(
            "com.openminis.app", "Minis panel label",
            children = listOf(
                node("com.other.app", "User content"),
                node("com.openminis.app", "Floating ball"),
                node("com.other.app", "More content"),
            ),
        )
        assertEquals(
            "User content\nMore content",
            ScreenTextWalker().collect(listOf(tree), ownPackage = "com.openminis.app"),
        )
    }

    @Test
    fun `character cap is exact and cuts the overflowing fragment`() {
        val walker = ScreenTextWalker(maxChars = 10)
        val tree = node(
            "com.app", "abcdefghij",
            children = listOf(node("com.app", "0123456789XYZ")),
        )
        // First fragment fills the budget exactly; the walk stops there.
        assertEquals("abcdefghij", walker.collect(listOf(tree)))

        val walker2 = ScreenTextWalker(maxChars = 15)
        // 10 + separator + 4 chars of the second fragment = exactly 15.
        assertEquals("abcdefghij\n0123", walker2.collect(listOf(tree)))
    }

    @Test
    fun `deep trees beyond the cap are not fully traversed`() {
        // 50 levels deep, each node one char. Cap of 10 must stop the walk
        // (separators count toward the budget, so well under 50 fragments).
        var leaf = node("com.app", "9")
        for (i in 0 until 49) {
            leaf = node("com.app", i.toString(), children = listOf(leaf))
        }
        val out = ScreenTextWalker(maxChars = 10).collect(listOf(leaf))
        assertEquals(10, out.length)
        // The whole 50-fragment chain did not fit: traversal stopped early.
        assertTrue(out.split("\n").size < 50)
    }

    @Test
    fun `empty forest yields empty string`() {
        assertEquals("", ScreenTextWalker().collect(emptyList()))
    }
}
