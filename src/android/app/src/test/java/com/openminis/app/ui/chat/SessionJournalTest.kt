package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [T-session-journal] The shadow log's contract:
 *  - append-only, one JSON object per line, never throws;
 *  - an unmatched compaction start is recoverable as `interrupted`;
 *  - request records carry a per-message digest so two projections can be
 *    diffed without copying transcript text into the journal.
 */
class SessionJournalTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("session-journal").toFile()
        SessionJournal.resetSeqForTest()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun msg(role: LLMMessage.Role, text: String) =
        LLMMessage(role = role, content = text)

    @Test
    fun appendsOneJsonLinePerEvent() {
        SessionJournal.record(dir, "s1", SessionJournal.TURN_START, mapOf("turn" to 0))
        SessionJournal.record(dir, "s1", SessionJournal.TURN_END, mapOf("turn" to 0))
        val lines = dir.walkTopDown().filter { it.isFile }.first().readLines()
            .filter { it.isNotBlank() }
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("\"kind\":\"turn/start\""))
        assertTrue(lines[1].contains("\"kind\":\"turn/end\""))
    }

    @Test
    fun sequenceIsMonotonicAcrossKinds() {
        SessionJournal.record(dir, "s1", SessionJournal.TURN_START)
        SessionJournal.record(dir, "s1", SessionJournal.STEP_START)
        val events = SessionJournal.tail(dir, "s1")
        assertEquals(2, events.size)
        assertTrue(events[1].optLong("seq") > events[0].optLong("seq"))
    }

    @Test
    fun aMissingDirectoryIsNotFatal() {
        // Must never throw into the loop: a null/blank session is a no-op.
        SessionJournal.record(null, "s1", SessionJournal.TURN_START)
        SessionJournal.record(dir, "", SessionJournal.TURN_START)
        assertTrue(SessionJournal.tail(dir, "s1").isEmpty())
    }

    @Test
    fun interruptedCompactionIsDetectedUntilItCloses() {
        val sid = "s-compact"
        SessionJournal.record(
            dir, sid, SessionJournal.COMPACTION_START,
            mapOf("compactionId" to "c1"),
        )
        assertEquals(1, SessionJournal.interruptedCompactions(dir, sid).size)

        SessionJournal.record(
            dir, sid, SessionJournal.COMPACTION_END,
            mapOf("compactionId" to "c1"),
        )
        assertTrue(SessionJournal.interruptedCompactions(dir, sid).isEmpty())
    }

    @Test
    fun abandonedCompactionIsNotReportedAsInterrupted() {
        val sid = "s-abandon"
        SessionJournal.record(
            dir, sid, SessionJournal.COMPACTION_START, mapOf("compactionId" to "c2"),
        )
        SessionJournal.record(
            dir, sid, SessionJournal.COMPACTION_INTERRUPTED, mapOf("compactionId" to "c2"),
        )
        assertTrue(SessionJournal.interruptedCompactions(dir, sid).isEmpty())
    }

    @Test
    fun requestRecordCarriesDigestsNotTranscriptText() {
        val sid = "s-request"
        val history = listOf(
            msg(LLMMessage.Role.USER, "do the thing"),
            LLMMessage(
                role = LLMMessage.Role.ASSISTANT,
                content = "",
                contentParts = listOf(AgentContentPart.ToolUse("c1", "shell_execute", org.json.JSONObject("{}"))),
            ),
        )
        SessionJournal.recordRequest(
            filesDir = dir,
            sessionId = sid,
            turn = 3,
            messages = history,
            estimatedTokens = 120,
            providerTokens = 100,
            summaryChars = 42,
            markerId = "m1",
            droppedRounds = 2,
        )
        val event = SessionJournal.tail(dir, sid).single()
        assertEquals(2, event.getInt("messages"))
        assertEquals(3, event.getInt("turn"))
        assertEquals(2, event.getInt("droppedRounds"))
        val digests = event.getString("digests")
        assertTrue(digests.split(",").size == 2)
        // The journal must not become a second transcript copy.
        assertFalse(event.toString().contains("do the thing"))
    }

    @Test
    fun digestDistinguishesRoleAndPartShape() {
        val user = msg(LLMMessage.Role.USER, "abc")
        val assistant = msg(LLMMessage.Role.ASSISTANT, "abc")
        val withTool = LLMMessage(
            role = LLMMessage.Role.USER,
            content = "",
            contentParts = listOf(AgentContentPart.ToolResult("c1", "t", "abc")),
        )
        assertFalse(SessionJournal.digestOf(user) == SessionJournal.digestOf(assistant))
        assertFalse(SessionJournal.digestOf(user) == SessionJournal.digestOf(withTool))
    }

    @Test
    fun sessionsDoNotShareAFile() {
        SessionJournal.record(dir, "a", SessionJournal.TURN_START)
        SessionJournal.record(dir, "b", SessionJournal.TURN_START)
        assertEquals(1, SessionJournal.tail(dir, "a").size)
        assertEquals(1, SessionJournal.tail(dir, "b").size)
        assertFalse(
            SessionJournal.file(dir, "a").absolutePath ==
                SessionJournal.file(dir, "b").absolutePath,
        )
    }

    @Test
    fun unsafeSessionIdsCannotEscapeTheDirectory() {
        val f = SessionJournal.file(dir, "../../etc/passwd")
        assertEquals(dir.resolve(SessionJournal.DIR).absolutePath, f.parentFile.absolutePath)
        assertFalse(f.name.contains("/"))
    }
}
