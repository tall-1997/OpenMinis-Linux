package com.openminis.app.tools

import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-lane-awareness] In-flight lanes are rendered into the request so compaction
 * cannot make the model forget work it already handed out — the structural form
 * of the prototype's "check ticket summaries after compaction" rule.
 */
class SubAgentInFlightRenderTest {

    private val sid = "chat-inflight"

    @Before
    fun reset() = finishAll()

    @After
    fun tearDown() = finishAll()

    private fun finishAll() {
        SubAgentBatchRegistry.list(sid, null).forEach { batch ->
            batch.lanes.filter { !it.finished }.forEach { lane ->
                SubAgentBatchRegistry.markFinished(
                    sid, null, batch.id, lane.index,
                    success = true, output = "cleanup", error = null, attempts = 1,
                )
            }
        }
    }

    private fun begin(vararg lanes: Triple<String, String, String>): SubAgentBatchRegistry.Batch =
        SubAgentBatchRegistry.begin(
            sid, null, toolId = "t", assistantId = "a", background = true,
            seeds = lanes.mapIndexed { index, (kind, title, prompt) ->
                SubAgentBatchRegistry.LaneSeed(index = index, kind = kind, title = title, prompt = prompt)
            },
        )

    @Test
    fun nothingInFlightRendersNothing() {
        assertNull(SubAgentBatchRegistry.renderInFlight(sid, null))
    }

    @Test
    fun aPendingLaneIsRenderedWithItsBrief() {
        begin(Triple("explore", "recon", "read the parser entrypoint"))
        val text = SubAgentBatchRegistry.renderInFlight(sid, null)!!
        assertTrue(text.contains("<in_flight>"))
        assertTrue(text.contains("explore"))
        assertTrue(text.contains("recon"))
        assertTrue(text.contains("read the parser entrypoint"))
        assertTrue(text.contains("check_agent"))
    }

    @Test
    fun finishedLanesDropOut() {
        val batch = begin(
            Triple("explore", "a", "first brief"),
            Triple("worker", "b", "second brief"),
        )
        SubAgentBatchRegistry.markFinished(
            sid, null, batch.id, 0, success = true, output = "done", error = null, attempts = 1,
        )
        val text = SubAgentBatchRegistry.renderInFlight(sid, null)!!
        assertTrue(text.contains("second brief"))
        assertTrue(!text.contains("first brief"))
    }

    @Test
    fun theListIsCapped() {
        val many = (1..10).map { Triple("explore", "lane $it", "brief number $it") }.toTypedArray()
        begin(*many)
        val text = SubAgentBatchRegistry.renderInFlight(sid, null)!!
        assertTrue(text.contains("brief number 1"))
        assertTrue(text.contains("and 2 more"))
        assertTrue(!text.contains("brief number 10"))
    }

    @Test
    fun longBriefsAreClipped() {
        begin(Triple("worker", "w", "x".repeat(1_000)))
        val text = SubAgentBatchRegistry.renderInFlight(sid, null, maxBriefChars = 50)!!
        assertTrue(text.contains("x".repeat(50)))
        assertTrue(!text.contains("x".repeat(51)))
    }
}
