package com.openminis.app.ui.chat

import com.openminis.app.tools.AgentPlanStore
import com.openminis.app.tools.AgentReportStore
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-state-hydration] The host restores plan/report state itself; the model
 * never has to call a read-back tool to know where the task stands.
 */
class SessionStateSnapshotTest {

    private val sid = "state-snapshot"

    @Before
    fun reset() {
        AgentPlanStore.clear(sid, null)
        AgentReportStore.clear(sid, null)
    }

    @After
    fun tearDown() = reset()

    @Test
    fun emptyStoresRestoreNothing() {
        assertNull(SessionStateSnapshot.build(sid, null))
    }

    @Test
    fun planAndReportAreRestored() {
        AgentPlanStore.add(sid, null, "wire the injector")
        AgentPlanStore.add(sid, null, "verify compaction")
        AgentReportStore.record(sid, null, "blocked", "waiting on a token", blocker = "expired token")

        val snapshot = SessionStateSnapshot.build(sid, null)!!
        assertTrue(snapshot, snapshot.contains("Plan (0/2 done)"))
        assertTrue(snapshot, snapshot.contains("[pending] wire the injector"))
        assertTrue(snapshot, snapshot.contains("Report:"))
        assertTrue(snapshot, snapshot.contains("waiting on a token"))
        assertTrue(snapshot, snapshot.contains("expired token"))
    }

    @Test
    fun reportQuestionIsRestored() {
        AgentReportStore.record(sid, null, "blocked", "needs a decision", blocker = "x", question = "Ship it?")
        val snapshot = SessionStateSnapshot.build(sid, null)!!
        assertTrue(snapshot, snapshot.contains("question: Ship it?"))
    }
}
