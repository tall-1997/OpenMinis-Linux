package com.openminis.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentActivityTrackerTest {
    @Test
    fun startAndFinishTrackMembers() {
        SubAgentActivityTracker.clearSession("s1")
        val id = SubAgentActivityTracker.start("s1", "Research", "worker", "gpt")
        assertEquals(1, SubAgentActivityTracker.membersFor("s1").size)
        // finish() removes the entry rather than parking it in a terminal
        // state — a stuck-on chip is exactly what this replaced.
        SubAgentActivityTracker.finish(id, true)
        assertTrue(SubAgentActivityTracker.membersFor("s1").isEmpty())
        SubAgentActivityTracker.clearSession("s1")
        assertTrue(SubAgentActivityTracker.membersFor("s1").isEmpty())
    }

    @Test
    fun updateStepShowsOnRunningMember() {
        SubAgentActivityTracker.clearSession("s2")
        val id = SubAgentActivityTracker.start("s2", "Research", "worker", "gpt")
        SubAgentActivityTracker.updateStep(id, "▶ shell_execute ls -la")
        assertEquals("▶ shell_execute ls -la", SubAgentActivityTracker.membersFor("s2").first().lastStep)
        SubAgentActivityTracker.clearSession("s2")
    }

    @Test
    fun updateProgressTracksTurnAndTool() {
        SubAgentActivityTracker.clearSession("s3")
        val id = SubAgentActivityTracker.start(
            parentSessionId = "s3",
            title = "slice",
            role = "coder",
            model = "gpt",
            index = 2,
            total = 3,
            kind = "worker",
            turnCap = 40,
        )
        SubAgentActivityTracker.updateProgress(id, 4, 40, "file_read")
        val m = SubAgentActivityTracker.membersFor("s3").first()
        assertEquals(2, m.index)
        assertEquals(3, m.total)
        assertEquals("worker", m.kind)
        assertEquals(4, m.turnIndex)
        assertEquals(40, m.turnCap)
        assertEquals("file_read", m.currentTool)
        assertEquals("turn 4/40 · file_read", m.lastStep)
        // The error is not retained on the roster either: it goes out on the
        // tool result. finish() takes it for call-site readability only.
        SubAgentActivityTracker.finish(id, false, "boom")
        assertTrue(SubAgentActivityTracker.membersFor("s3").isEmpty())
        SubAgentActivityTracker.clearSession("s3")
    }

    @Test
    fun appendLogAndCombinedTranscriptCoverBatchAndSingle() {
        SubAgentActivityTracker.clearSession("s4")
        val a = SubAgentActivityTracker.start(
            parentSessionId = "s4",
            title = "A",
            role = "worker",
            model = "gpt",
            index = 1,
            total = 2,
            kind = "explore",
        )
        val b = SubAgentActivityTracker.start(
            parentSessionId = "s4",
            title = "B",
            role = "worker",
            model = "gpt",
            index = 2,
            total = 2,
            kind = "worker",
        )
        SubAgentActivityTracker.appendLog(a, "turn 1/40 · file_read · src/Foo.kt")
        SubAgentActivityTracker.appendLog(b, "turn 1/40 · grep · TODO")
        val combined = SubAgentActivityTracker.combinedTranscript("s4")
        assertTrue(combined.contains("子代理 1/2"))
        assertTrue(combined.contains("file_read"))
        assertTrue(combined.contains("子代理 2/2"))
        assertTrue(combined.contains("grep"))
        assertEquals("turn 1/40 · file_read · src/Foo.kt", SubAgentActivityTracker.membersFor("s4").first { it.id == a }.transcript)
        SubAgentActivityTracker.clearSession("s4")
        SubAgentActivityTracker.clearSession("s5")
        val one = SubAgentActivityTracker.start("s5", "solo", "worker", "gpt")
        SubAgentActivityTracker.appendLog(one, "turn 2/10 · done")
        val solo = SubAgentActivityTracker.combinedTranscript("s5")
        assertEquals("turn 2/10 · done", solo)
        assertFalse(solo.contains("## "))
        SubAgentActivityTracker.clearSession("s5")
    }

    @Test
    fun liveStepsKeepThinkingTextAndToolApart() {
        SubAgentActivityTracker.clearSession("s6")
        val id = SubAgentActivityTracker.start("s6", "修扫描", "工程师", "gpt", index = 1, total = 1)
        SubAgentActivityTracker.setPhase(id, "思考中")
        SubAgentActivityTracker.appendStream(id, "t1-think", "thinking", "先看")
        SubAgentActivityTracker.appendStream(id, "t1-think", "thinking", "调用栈")
        SubAgentActivityTracker.beginTool(id, "call-1", "file_read")
        SubAgentActivityTracker.markToolRunning(id, "call-1", "file_read", """{"path":"src/Foo.kt"}""")
        SubAgentActivityTracker.finishTool(id, "call-1", "file_read", true, "class Foo")
        val m = SubAgentActivityTracker.membersFor("s6").first()
        assertEquals("思考中", m.phase)
        assertEquals(1, m.steps.count { it.kind == "thinking" })
        assertEquals("先看调用栈", m.steps.first { it.kind == "thinking" }.body)
        val tool = m.steps.first { it.kind == "tool" }
        assertEquals("success", tool.status)
        assertEquals("file_read", tool.toolName)
        assertTrue(tool.body.contains("Foo"))
        SubAgentActivityTracker.clearSession("s6")
    }
}
