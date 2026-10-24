package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the Supervisor-Worker chat DONE line protocol.
 *
 * The protocol: a supervisor review turn ends the session only by a
 * line-anchored `DONE: <summary>` line. A summary that references no
 * acceptance criterion is a rubber stamp and gets one strike.
 */
class SupervisorChatDoneTest {

    @Test
    fun lineAnchoredDoneIsParsedWithSummary() {
        val verdict = SupervisorChat.parseDone(
            "The worker fixed the crash and tests pass.\nDONE: crash fixed, acceptance criterion 'no crash on launch' met",
            listOf("no crash on launch", "unit tests green"),
        )
        assertNotNull(verdict)
        assertEquals("crash fixed, acceptance criterion 'no crash on launch' met", verdict!!.summary)
    }

    @Test
    fun doneMidParagraphDoesNotTerminate() {
        val verdict = SupervisorChat.parseDone(
            "I think we are almost DONE: just one more fix needed.",
            listOf("no crash on launch"),
        )
        assertNull(verdict)
    }

    @Test
    fun noDoneLineReturnsNull() {
        val verdict = SupervisorChat.parseDone(
            "Worker output looks wrong on line 3, send a correction.",
            listOf("no crash on launch"),
        )
        assertNull(verdict)
    }

    @Test
    fun rubberStampWhenNoAcceptanceTermReferenced() {
        val verdict = SupervisorChat.parseDone(
            "DONE: all good, ship it",
            listOf("no crash on launch", "unit tests green"),
        )
        assertNotNull(verdict)
        assertTrue(SupervisorChat.isRubberStamp(verdict!!, listOf("no crash on launch", "unit tests green")))
    }

    @Test
    fun evidenceReferencingAcceptanceTermIsNotRubberStamp() {
        val verdict = SupervisorChat.parseDone(
            "DONE: launch crash gone, tests green",
            listOf("no crash on launch", "unit tests green"),
        )
        assertNotNull(verdict)
        assertFalse(SupervisorChat.isRubberStamp(verdict!!, listOf("no crash on launch", "unit tests green")))
    }

    @Test
    fun shortAcceptanceWordsAreIgnoredForMatching() {
        // Words under 4 chars ("fix", "the") must not count as evidence.
        val verdict = SupervisorChat.parseDone(
            "DONE: fix the thing",
            listOf("fix the crash on launch"),
        )
        assertNotNull(verdict)
        assertTrue(SupervisorChat.isRubberStamp(verdict!!, listOf("fix the crash on launch")))
    }

    @Test
    fun caseInsensitiveEvidenceMatching() {
        val verdict = SupervisorChat.parseDone(
            "DONE: NO CRASH ON LAUNCH verified",
            listOf("no crash on launch"),
        )
        assertNotNull(verdict)
        assertFalse(SupervisorChat.isRubberStamp(verdict!!, listOf("no crash on launch")))
    }
}
