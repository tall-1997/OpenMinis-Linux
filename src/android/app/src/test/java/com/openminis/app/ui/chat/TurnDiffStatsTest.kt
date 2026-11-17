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

    // ── [T-turn-card-diff-detail] line-level diff for the card detail view ──

    private fun kinds(lines: List<DiffLine>): List<DiffLine.Kind> = lines.map { it.kind }

    private fun textsOf(kind: DiffLine.Kind, lines: List<DiffLine>): List<String> =
        lines.filter { it.kind == kind }.map { it.text }

    @Test
    fun `diffLines renders replacement as remove-then-add with context`() {
        val before = "alpha\nbeta\nsame\n".toByteArray()
        val after = "alpha\nchanged\nsame\ngamma\n".toByteArray()
        val diff = TurnDiffStats.diffLines(before, true, after)!!

        assertEquals(
            listOf(DiffLine.Kind.CONTEXT, DiffLine.Kind.REMOVED, DiffLine.Kind.ADDED, DiffLine.Kind.CONTEXT, DiffLine.Kind.ADDED),
            kinds(diff),
        )
        assertEquals(listOf("alpha", "same"), textsOf(DiffLine.Kind.CONTEXT, diff))
        assertEquals(listOf("beta"), textsOf(DiffLine.Kind.REMOVED, diff))
        assertEquals(listOf("changed", "gamma"), textsOf(DiffLine.Kind.ADDED, diff))
    }

    @Test
    fun `diffLines handles creation and deletion`() {
        val created = TurnDiffStats.diffLines(null, false, "one\ntwo\n".toByteArray())!!
        assertEquals(listOf(DiffLine.Kind.ADDED, DiffLine.Kind.ADDED), kinds(created))

        val deleted = TurnDiffStats.diffLines("one\ntwo\n".toByteArray(), true, null)!!
        assertEquals(listOf(DiffLine.Kind.REMOVED, DiffLine.Kind.REMOVED), kinds(deleted))
    }

    @Test
    fun `diffLines returns null on the same guards as compute`() {
        assertNull(TurnDiffStats.diffLines(null, true, "text".toByteArray()))
        assertNull(TurnDiffStats.diffLines(byteArrayOf(0), true, byteArrayOf(0)))
        val oversized = ByteArray(TurnDiffStats.MAX_BYTES + 1) { 'x'.code.toByte() }
        assertNull(TurnDiffStats.diffLines(oversized, true, oversized))
    }

    @Test
    fun `diffLines stat consistency — added and removed counts match compute`() {
        val before = "a\nb\nc\nd\n".toByteArray()
        val after = "a\nx\ny\nd\ne\n".toByteArray()
        val diff = TurnDiffStats.diffLines(before, true, after)!!
        val stat = TurnDiffStats.compute(before, true, after)!!
        assertEquals(stat.added, diff.count { it.kind == DiffLine.Kind.ADDED })
        assertEquals(stat.removed, diff.count { it.kind == DiffLine.Kind.REMOVED })
    }

    @Test
    fun `diffLines matches a DP oracle on randomized sequence pairs`() {
        // Deterministic LCG so a failure reproduces.
        var seed = 0x5DEECE66DL
        fun rnd(bound: Int): Int {
            seed = (seed * 0x41C64E6D + 0x607B) and 0xFFFFFFFFFFFFL
            return ((seed ushr 17) % bound.toLong()).toInt()
        }
        val alphabet = listOf("a", "b", "c", "d")
        // [T-turn-card-diff-detail] The oracle must be the INSERTION/DELETION
        // distance (n + m − 2·LCS), NOT Levenshtein: Myers' edit script has no
        // replace move, so when a substitution is cheaper (cost 1) than
        // delete+insert (cost 2) the two disagree — the first oracle version
        // used Levenshtein and "failed" on perfectly-correct scripts.
        fun dp(old: List<String>, new: List<String>): Int {
            val n = old.size; val m = new.size
            val t = Array(n + 1) { IntArray(m + 1) }
            for (i in 1..n) for (j in 1..m) {
                t[i][j] = if (old[i - 1] == new[j - 1]) t[i - 1][j - 1] + 1
                else maxOf(t[i - 1][j], t[i][j - 1])
            }
            return n + m - 2 * t[n][m]
        }
        repeat(200) {
            val n = rnd(12); val m = rnd(12)
            val old = (0 until n).map { alphabet[rnd(alphabet.size)] }
            val new = (0 until m).map { alphabet[rnd(alphabet.size)] }
            // [T-turn-card-diff-detail] joinToString's postfix attaches EVEN on
            // an empty list, turning old=[] into "\n" — a one-EMPTY-line file
            // (n=1), not a created file. An empty list must map to null
            // (created this turn) so the oracle tests the intended semantics.
            val diff = TurnDiffStats.diffLines(
                old.joinToString("\n", postfix = "\n").toByteArray().takeIf { n > 0 },
                n > 0,
                new.joinToString("\n", postfix = "\n").toByteArray().takeIf { m > 0 },
            )!!
            // The emitted edit-script must be VALID: removing ADDED lines and
            // inserting REMOVED lines (in place) must yield exactly `new`.
            val reconstructed = buildList {
                for (l in diff) when (l.kind) {
                    DiffLine.Kind.ADDED -> add(l.text)
                    DiffLine.Kind.CONTEXT -> add(l.text)
                    DiffLine.Kind.REMOVED -> {}
                }
            }
            assertEquals("old=$old new=$new", new, reconstructed)
            // And the edit count must equal the true edit distance.
            val edits = diff.count { it.kind != DiffLine.Kind.CONTEXT }
            assertEquals("old=$old new=$new", dp(old, new), edits)
        }
    }
}
