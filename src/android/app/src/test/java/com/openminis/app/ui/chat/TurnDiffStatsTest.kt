package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TurnDiffStatsTest {

    @Test
    fun `counts additions and removals across replacement and insertion`() {
        val before = "alpha\nbeta\nsame\n".toByteArray()
        val after = "alpha\nchanged\nsame\ngamma\n".toByteArray()

        assertEquals(FileDiffStat(added = 2, removed = 1), TurnDiffStats.compute(before, true, after))
    }

    @Test
    fun `counts all lines for files created or deleted this turn`() {
        val created = TurnDiffStats.compute(null, false, "one\ntwo\n".toByteArray())
        val deleted = TurnDiffStats.compute("one\ntwo\n".toByteArray(), true, null)

        assertEquals(FileDiffStat(added = 2, removed = 0), created)
        assertEquals(FileDiffStat(added = 0, removed = 2), deleted)
    }

    @Test
    fun `treats CRLF and LF as equivalent and ignores final newline`() {
        val before = "first\r\nsecond\r\n".toByteArray()
        val after = "first\nsecond".toByteArray()

        assertEquals(FileDiffStat(added = 0, removed = 0), TurnDiffStats.compute(before, true, after))
    }

    @Test
    fun `returns unknown for missing preimage binary and oversized content`() {
        val missingPreImage = TurnDiffStats.compute(null, true, "text".toByteArray())
        val binary = TurnDiffStats.compute(byteArrayOf(0), true, byteArrayOf(0))
        val oversized = ByteArray(TurnDiffStats.MAX_BYTES + 1) { 'x'.code.toByte() }

        assertNull(missingPreImage)
        assertNull(binary)
        assertNull(TurnDiffStats.compute(oversized, true, oversized))
        assertNull(TurnDiffStats.compute(byteArrayOf(0xC3.toByte()), true, "a".toByteArray()))
    }

    @Test
    fun `Myers distance counts insertions deletions and replacements`() {
        assertEquals(0, TurnDiffStats.editDistance(listOf("a"), listOf("a"), cap = 10))
        assertEquals(1, TurnDiffStats.editDistance(listOf("a"), listOf("a", "b"), cap = 10))
        assertEquals(1, TurnDiffStats.editDistance(listOf("a", "b"), listOf("a"), cap = 10))
        assertEquals(2, TurnDiffStats.editDistance(listOf("old"), listOf("new"), cap = 10))
        assertNull(TurnDiffStats.editDistance(listOf("old"), listOf("new"), cap = 1))
        assertNull(TurnDiffStats.compute(
            (1..2_100).joinToString("\n", postfix = "\n") { "old-$it" }.toByteArray(),
            true,
            (1..2_100).joinToString("\n", postfix = "\n") { "new-$it" }.toByteArray(),
        ))
    }
}
