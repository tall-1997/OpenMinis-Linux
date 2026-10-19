package com.openminis.app.scheduled

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-scheduled-default-follow-up] Source guard for the wiring, not the
 * decision: [ScheduledTargetDelivery] is unit-tested, but it only helps if
 * every creation surface keeps feeding it the chat it was called from — and
 * keeps telling the caller where the fires will land. The bug this fixes was
 * exactly a call site quietly disagreeing with the others.
 */
class ScheduledTargetWiringTest {

    @Test
    fun `the CLI resolves the target against the calling session and announces it`() {
        val src = ProductionSources.read("sandbox/offload/ScheduledTaskOffloadHandler.kt")
        assertTrue("create must receive request.sessionId", src.contains("handleCreate(args, request.sessionId)"))
        assertTrue("create must go through the shared default", src.contains("resolveTarget(args, callerSessionId)"))
        assertTrue("a defaulted target whose session is gone falls back to new", src.contains("defaulted -> ScheduledTargetMode.NewSession to true"))
        assertTrue("the reply must say where fires land", src.contains(".put(\"delivery\", ScheduledTargetDelivery.sentence(destination, defaulted))"))
        assertFalse("the old unconditional default is gone", src.contains("private fun parseTarget("))
    }

    @Test
    fun `the cronjob tool fires back into the chat that created the task`() {
        val src = ProductionSources.read("tools/CronJobTool.kt")
        assertTrue(src.contains("ScheduledTargetDelivery.resolveTarget(explicitTarget, null, sessionId)"))
        assertTrue(src.contains("sessionId?.let { ScheduledTargetMode.AppendToSession(it) }"))
        assertTrue(src.contains("targetMode = targetMode"))
        assertTrue("the reply must say where fires land", src.contains("put(\"delivery\", ScheduledTargetDelivery.sentence(destination, defaulted))"))
        assertTrue("list must surface the stored target", src.contains("put(\"target\", t.targetMode.encode())"))
    }

    @Test
    fun `the dispatcher hands the tool the live session`() {
        val src = ProductionSources.read("ui/chat/ChatViewModelExecuteToolExt.kt")
        assertTrue(src.contains("CronJobTool.execute(argsJson, context, activeSessionId)"))
    }
}
