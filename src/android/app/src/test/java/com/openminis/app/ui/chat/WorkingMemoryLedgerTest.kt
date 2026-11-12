package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-working-memory-ledger] One-line-per-round records, same-dispatch-and-
 * result folding (×N — repetition is visible as a count, not N lines), and
 * one-shot delivery (evict-then-deliver: the note rides the transcript after
 * the eviction it compensates, never re-delivered).
 */
class WorkingMemoryLedgerTest {

    private fun led() = WorkingMemoryLedger()

    @Test
    fun `undelivered renders one line per round`() {
        val l = led()
        l.append(1, "shell_execute", "aaaa1111", "build ok")
        l.append(2, "file_edit", "bbbb2222", "written")
        val note = l.renderUndelivered()!!
        assertTrue(note.contains("turn 1: shell_execute(aaaa1111) → build ok"))
        assertTrue(note.contains("turn 2: file_edit(bbbb2222) → written"))
    }

    @Test
    fun `same dispatch and result folds into xN`() {
        val l = led()
        repeat(9) { l.append(1, "shell_execute", "aaaa1111", "same") }
        val note = l.renderUndelivered()!!
        assertTrue(note.contains("same result ×9"))
        val body = note.lines().drop(1).filter { it.isNotBlank() }
        assertEquals(1, body.size) // one folded line for the whole streak
    }

    @Test
    fun `delivery is one-shot`() {
        val l = led()
        l.append(1, "file_read", "cccc3333", "contents")
        val first = l.renderUndelivered()!!
        assertEquals(1, first.lines().drop(1).filter { it.isNotBlank() }.size)
        assertNull("already delivered — must not repeat", l.renderUndelivered())
        l.append(2, "file_edit", "dddd4444", "written")
        val second = l.renderUndelivered()!!
        assertTrue(second.contains("turn 2"))
        assertFalse(second.contains("turn 1"))
    }

    @Test
    fun `note carries the working-memory header`() {
        val l = led()
        l.append(5, "shell_execute", "eeee5555", "exit 101")
        assertTrue(l.renderUndelivered()!!.startsWith("[working memory"))
    }

    @Test
    fun `old entries fold when the ring overflows`() {
        val l = led()
        val oldestTurn = 1
        for (i in 1..(WorkingMemoryLedger.MAX_ENTRIES + 50)) {
            l.append(i, "tool_$i", "sha$i", "out $i")
        }
        // Entries beyond the ring fold into a marker line; the original
        // per-round lines of the folded range are gone (fold keeps the count).
        val note = l.renderUndelivered()!!
        assertTrue(note.contains("earlier tool rounds folded"))
        assertFalse(note.contains("tool_1(")) // original line folded away
    }
}
