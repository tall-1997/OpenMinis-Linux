package com.openminis.app.provider

import com.openminis.app.harness.agent.StallResume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [T-stall-resume] continuation-note construction for mid-stream stalls. */
class StallResumeTest {

    @Test
    fun blankPartialProducesEmptyNote() {
        assertEquals("", StallResume.note("   "))
        assertEquals("", StallResume.note(""))
    }

    @Test
    fun noteWrapsPartialWithInstruction() {
        val note = StallResume.note("The unit tests pass, but the integration")
        assertTrue(note.startsWith("<stall-resume>"))
        assertTrue(note.endsWith("</stall-resume>"))
        assertTrue(note.contains("Continue from exactly after that point"))
        assertTrue(note.contains("Do not restate, quote, or re-introduce any"))
        assertTrue(note.contains("The unit tests pass, but the integration"))
    }

    @Test
    fun noteHasNoQuoteFencing() {
        // [T-stall-resume-seed-shrink] the fenced block read like text to
        // reproduce; the new format embeds the tail without any quoting.
        val note = StallResume.note("some partial text that was streaming")
        assertFalse(note.contains("\"\"\""))
        assertFalse(note.contains("Do NOT repeat"))
    }

    @Test
    fun noteKeepsOnlyTrailingTailWhenOversized() {
        val long = "x".repeat(20_000)
        val note = StallResume.note(long, maxChars = 300)
        // the instruction itself is fixed overhead; the seed must be capped
        assertTrue("note must be bounded, was ${note.length}", note.length < 900)
        assertTrue(note.endsWith("</stall-resume>"))
        // leading content was dropped (only the tail survives)
        assertFalse(note.contains("x".repeat(500)))
        assertTrue(note.contains("x".repeat(200)))
    }

    @Test
    fun noteTrimsThePartial() {
        val note = StallResume.note("  hello  ", maxChars = 100)
        assertTrue(note.contains("hello"))
        assertFalse(note.contains("  hello  "))
    }

    @Test
    fun tailMatchesWhatNoteEmbeds() {
        // [T-stall-echo-strip] the stripper must align against the exact
        // bytes the note carries — tail() is the single source of that span.
        val partial = "streamed text that stalled mid-sent"
        assertTrue(StallResume.note(partial).contains(StallResume.tail(partial)))
        assertEquals(partial.trim(), StallResume.tail(partial))
        assertEquals(
            partial.trim().takeLast(10),
            StallResume.tail(partial, maxChars = 10),
        )
    }

    @Test
    fun tailRespectsDefaultSeedChars() {
        val long = "y".repeat(20_000)
        assertEquals(StallResume.SEED_CHARS, StallResume.tail(long).length)
        assertTrue(StallResume.SEED_CHARS <= 400)
    }
}
