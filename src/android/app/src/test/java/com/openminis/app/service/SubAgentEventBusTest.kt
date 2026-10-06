package com.openminis.app.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-event-bus] Ring bounds, session isolation, LRU eviction, summaries and
 * the tracker funnel: every lifecycle transition that the roster sees must
 * also land on the bus, and survive the roster removal.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SubAgentEventBusTest {

    private val session = "bus-test-session"

    @Before
    fun setUp() {
        SubAgentEventBus.clearAll()
        SubAgentActivityTracker.clearSession(session)
    }

    @After
    fun tearDown() {
        SubAgentActivityTracker.clearSession(session)
        SubAgentEventBus.clearAll()
    }

    private fun now() = System.currentTimeMillis()

    private fun spawned(run: String, sess: String = session, title: String = "T") = SubAgentEvent.Spawned(
        runId = run, parentSessionId = sess, parentToolId = "pt", title = title,
        role = "worker", kind = "worker", index = 1, total = 2, model = "m", turnCap = 10, atMs = now(),
    )

    private fun turn(run: String, t: Int, sess: String = session) =
        SubAgentEvent.TurnStarted(run, sess, t, 10, now())

    // ------------------------------------------------------------ ring

    @Test
    fun ringIsBoundedAt300() {
        for (i in 1..350) SubAgentEventBus.publish(turn("r1", i))
        val all = SubAgentEventBus.recentFor(session, limit = 400)
        assertEquals(300, all.size)
        assertEquals(51, (all.first() as SubAgentEvent.TurnStarted).turn)
        assertEquals(350, (all.last() as SubAgentEvent.TurnStarted).turn)
    }

    @Test
    fun recentForHonoursLimit() {
        for (i in 1..20) SubAgentEventBus.publish(turn("r1", i))
        val last10 = SubAgentEventBus.recentFor(session, limit = 10)
        assertEquals(10, last10.size)
        assertEquals(11, (last10.first() as SubAgentEvent.TurnStarted).turn)
        assertEquals(20, (last10.last() as SubAgentEvent.TurnStarted).turn)
    }

    @Test
    fun sessionsAreIsolated() {
        SubAgentEventBus.publish(spawned("r1", "s1"))
        SubAgentEventBus.publish(spawned("r2", "s2"))
        assertEquals(1, SubAgentEventBus.recentFor("s1").size)
        assertEquals("r1", SubAgentEventBus.recentFor("s1").single().runId)
        assertTrue(SubAgentEventBus.recentFor("unknown").isEmpty())
    }

    @Test
    fun sessionLruEvictionAt16() {
        for (i in 0..16) SubAgentEventBus.publish(spawned("r$i", "s$i"))
        assertTrue("eldest session evicted", SubAgentEventBus.recentFor("s0").isEmpty())
        assertEquals(1, SubAgentEventBus.recentFor("s16").size)
    }

    @Test
    fun clearSessionDropsRing() {
        SubAgentEventBus.publish(spawned("r1"))
        SubAgentEventBus.clearSession(session)
        assertTrue(SubAgentEventBus.recentFor(session).isEmpty())
        assertNull(SubAgentEventBus.summaryFor("r1"))
    }

    // ------------------------------------------------------------ summary

    @Test
    fun summaryAggregatesRingThenCompletedWins() {
        SubAgentEventBus.publish(spawned("r1", title = "Analyst"))
        SubAgentEventBus.publish(turn("r1", 3))
        SubAgentEventBus.publish(turn("r1", 5))
        SubAgentEventBus.publish(SubAgentEvent.ToolInvoked("r1", session, "grep", "", now()))
        SubAgentEventBus.publish(SubAgentEvent.ToolInvoked("r1", session, "ls", "", now()))
        SubAgentEventBus.publish(SubAgentEvent.RetryScheduled("r1", session, 2, 3, 4_000, "RateLimited", now()))

        val mid = SubAgentEventBus.summaryFor("r1")!!
        assertEquals("Analyst", mid.title)
        assertEquals("worker", mid.kind)
        assertEquals(5, mid.turns) // max TurnStarted while running
        assertEquals(2, mid.toolCalls)
        assertEquals(1, mid.retries)
        assertFalse(mid.finished)
        assertFalse(mid.success)

        SubAgentEventBus.publish(SubAgentEvent.Completed("r1", session, true, 7, 9, now()))
        val done = SubAgentEventBus.summaryFor("r1")!!
        assertTrue(done.success)
        assertTrue(done.finished)
        assertFalse(done.stoppedByUser)
        assertEquals(7, done.turns) // Completed overrides ring-derived
        assertEquals(9, done.toolCalls)
        assertEquals(1, done.retries)
    }

    @Test
    fun summaryMarksUserStop() {
        SubAgentEventBus.publish(spawned("r2"))
        SubAgentEventBus.publish(SubAgentEvent.Stopped("r2", session, now()))
        val s = SubAgentEventBus.summaryFor("r2")!!
        assertTrue(s.stoppedByUser)
        assertTrue(s.finished)
        assertFalse(s.success)
    }

    @Test
    fun summaryUnknownRunIsNull() {
        assertNull(SubAgentEventBus.summaryFor("nope"))
    }

    // ------------------------------------------------------------ flow

    @Test
    fun eventsForFiltersBySession() = runTest {
        val got = mutableListOf<SubAgentEvent>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            SubAgentEventBus.eventsFor(session).collect { got += it }
        }
        advanceUntilIdle()
        SubAgentEventBus.publish(spawned("r1"))
        SubAgentEventBus.publish(spawned("rX", "other-session"))
        advanceUntilIdle()
        assertEquals(1, got.size)
        assertEquals("r1", got[0].runId)
        job.cancel()
    }

    // ------------------------------------------------------------ funnel

    @Test
    fun trackerStartPublishesSpawned() {
        val id = SubAgentActivityTracker.start(
            parentSessionId = session, title = "Analyst", role = "analyst", model = "m",
            index = 1, total = 2, kind = "analyst", turnCap = 8, parentToolId = "pt1",
        )
        val events = SubAgentEventBus.recentFor(session)
        assertEquals(1, events.size)
        val s = events[0] as SubAgentEvent.Spawned
        assertEquals(id, s.runId)
        assertEquals("Analyst", s.title)
        assertEquals("analyst", s.kind)
        assertEquals(1, s.index)
        assertEquals(2, s.total)
        assertEquals(8, s.turnCap)
        assertEquals("pt1", s.parentToolId)
    }

    @Test
    fun trackerProgressFinishFlow() {
        val id = SubAgentActivityTracker.start(parentSessionId = session, title = "T", role = null, model = null)
        // turn 0 is retry text, not a real turn — no event
        SubAgentActivityTracker.updateProgress(id, 0, 10, "retry in 4s")
        assertTrue(SubAgentEventBus.recentFor(session).none { it is SubAgentEvent.TurnStarted })

        SubAgentActivityTracker.updateProgress(id, 3, 10, "grep")
        val t = SubAgentEventBus.recentFor(session).filterIsInstance<SubAgentEvent.TurnStarted>().single()
        assertEquals(3, t.turn)
        assertEquals(10, t.cap)

        SubAgentActivityTracker.beginTool(id, "t1", "grep")
        SubAgentActivityTracker.markToolRunning(id, "t1", "grep", """{"pattern":"x"}""")
        val inv = SubAgentEventBus.recentFor(session).filterIsInstance<SubAgentEvent.ToolInvoked>().single()
        assertEquals("grep", inv.toolName)
        assertTrue(inv.argsPreview.contains("pattern"))

        SubAgentActivityTracker.finishTool(id, "t1", "grep", true, "2 matches")
        val fin = SubAgentEventBus.recentFor(session).filterIsInstance<SubAgentEvent.ToolFinished>().single()
        assertTrue(fin.success)
        assertEquals("grep", fin.toolName)

        SubAgentActivityTracker.finish(id, true)
        val comp = SubAgentEventBus.recentFor(session).filterIsInstance<SubAgentEvent.Completed>().single()
        assertTrue(comp.success)
        assertEquals(3, comp.turnsUsed)
        assertEquals(1, comp.toolCalls)
        assertTrue("roster entry removed", SubAgentActivityTracker.membersFor(session).isEmpty())

        // the whole point: summary survives roster removal
        val sum = SubAgentEventBus.summaryFor(id)!!
        assertTrue(sum.success)
        assertEquals(3, sum.turns)
        assertEquals(1, sum.toolCalls)
    }

    @Test
    fun trackerArgsPreviewTruncatedTo200() {
        val id = SubAgentActivityTracker.start(parentSessionId = session, title = "T", role = null, model = null)
        SubAgentActivityTracker.beginTool(id, "t1", "shell_execute")
        SubAgentActivityTracker.markToolRunning(id, "t1", "shell_execute", "y".repeat(500))
        val inv = SubAgentEventBus.recentFor(session).filterIsInstance<SubAgentEvent.ToolInvoked>().single()
        assertEquals(200, inv.argsPreview.length)
    }

    @Test
    fun trackerStopPublishesStopped() {
        val id = SubAgentActivityTracker.start(parentSessionId = session, title = "T", role = null, model = null)
        val job = Job()
        SubAgentActivityTracker.attachJob(id, job)
        assertTrue(SubAgentActivityTracker.stop(id))
        assertTrue(job.isCancelled)
        assertTrue(SubAgentEventBus.recentFor(session).any { it is SubAgentEvent.Stopped && it.runId == id })
        SubAgentActivityTracker.finish(id, false)
        assertFalse(SubAgentEventBus.summaryFor(id)!!.success)
        assertTrue(SubAgentEventBus.summaryFor(id)!!.stoppedByUser)
    }

    @Test
    fun trackerStopWithoutJobIsNoop() {
        assertFalse(SubAgentActivityTracker.stop("missing-id"))
    }
}
