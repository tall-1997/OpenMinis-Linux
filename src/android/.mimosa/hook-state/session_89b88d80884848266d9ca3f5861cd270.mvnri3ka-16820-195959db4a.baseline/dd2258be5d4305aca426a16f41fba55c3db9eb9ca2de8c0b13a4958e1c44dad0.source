package com.openminis.app.tools

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-subagent-background] Collection semantics for `check_agent`. Reading a
 * finished wave must mark it collected so the agent loop stops nudging about
 * it; reading a wave that is still running must not, or the reports of the
 * remaining lanes would never be chased. Housekeeping (`op=clear`) must not be
 * able to delete a report nobody has read.
 */
class CheckAgentToolTest {

    private val registry = SubAgentBatchRegistry

    private fun seed(
        session: String,
        lanes: Int = 1,
        background: Boolean = true,
    ): SubAgentBatchRegistry.Batch = registry.begin(
        sessionId = session,
        context = null,
        toolId = "tool-1",
        assistantId = "asst-1",
        background = background,
        seeds = (1..lanes).map {
            SubAgentBatchRegistry.LaneSeed(index = it, kind = "worker", title = "lane $it", prompt = "do $it")
        },
    )

    private fun finishAll(session: String, batchId: String, lanes: Int) {
        (1..lanes).forEach {
            registry.markFinished(
                sessionId = session,
                context = null,
                batchId = batchId,
                index = it,
                success = true,
                output = "report $it",
                error = null,
                attempts = 1,
            )
        }
    }

    private fun collectedAt(session: String, batchId: String): Long =
        requireNotNull(registry.get(session, null, batchId)).collectedAt

    @Test
    fun `collecting a finished wave hands back the reports and marks it collected`() = runBlocking {
        val s = "sess-tool-collect"
        val b = seed(s, lanes = 2)
        finishAll(s, b.id, lanes = 2)

        val r = CheckAgentTool.execute("""{"tool_title":"t","op":"collect"}""", s, null)

        assertTrue(r.success)
        assertTrue(r.output, r.output.contains("report 1"))
        assertTrue(r.output, r.output.contains("report 2"))
        assertFalse(r.output, r.output.contains("never collected"))
        assertTrue(collectedAt(s, b.id) > 0L)
        assertTrue(registry.unfinished(s, null).isEmpty())
    }

    @Test
    fun `collecting a wave that is still running leaves it owed`() = runBlocking {
        val s = "sess-tool-partial"
        val b = seed(s, lanes = 2)
        registry.markFinished(s, null, b.id, 1, true, "report 1", null, 1)

        val r = CheckAgentTool.execute("""{"tool_title":"t","op":"collect"}""", s, null)

        assertTrue(r.success)
        assertTrue(r.output, r.output.contains("report 1"))
        assertTrue(r.output, r.output.contains("still in flight"))
        assertEquals(0L, collectedAt(s, b.id))
        assertEquals(1, registry.unfinished(s, null).size)
    }

    @Test
    fun `await that reaches completion marks the wave collected`() = runBlocking {
        val s = "sess-tool-await"
        val b = seed(s, lanes = 1)
        finishAll(s, b.id, lanes = 1)

        val r = CheckAgentTool.execute("""{"tool_title":"t","op":"await","timeout_sec":1}""", s, null)

        assertTrue(r.success)
        assertTrue(r.output, r.output.contains("finished"))
        assertTrue(collectedAt(s, b.id) > 0L)
    }

    @Test
    fun `await that times out keeps the wave owed`() = runBlocking {
        val s = "sess-tool-await-timeout"
        val b = seed(s, lanes = 1)
        registry.markRunning(s, null, b.id, 1, "tracker-x", "gpt-x", 1)

        val r = CheckAgentTool.execute("""{"tool_title":"t","op":"await","timeout_sec":1}""", s, null)

        assertTrue(r.success)
        assertTrue(r.output, r.output.contains("still running"))
        assertEquals(0L, collectedAt(s, b.id))
    }

    @Test
    fun `status is a read-only snapshot and collects nothing`() = runBlocking {
        val s = "sess-tool-status"
        val b = seed(s, lanes = 1)
        finishAll(s, b.id, lanes = 1)

        val r = CheckAgentTool.execute("""{"tool_title":"t","op":"status"}""", s, null)

        assertTrue(r.success)
        assertTrue(r.output, r.output.contains(b.id.take(8)))
        // Looking is not taking: the loop must still nudge for the reports.
        assertEquals(0L, collectedAt(s, b.id))
        assertEquals(1, registry.unfinished(s, null).size)
    }

    @Test
    fun `clear reports the uncollected waves it refused to drop`() = runBlocking {
        val s = "sess-tool-clear"
        val kept = seed(s, lanes = 1)
        finishAll(s, kept.id, lanes = 1)
        val taken = seed(s, lanes = 1)
        finishAll(s, taken.id, lanes = 1)
        registry.markCollected(s, null, taken.id)

        val r = CheckAgentTool.execute("""{"tool_title":"t","op":"clear"}""", s, null)

        assertTrue(r.success)
        assertTrue(r.output, r.output.contains("Cleared 1"))
        assertTrue(r.output, r.output.contains("Kept 1"))
        assertEquals(listOf(kept.id), registry.list(s, null).map { it.id })
    }

    @Test
    fun `list names every recorded wave`() = runBlocking {
        val s = "sess-tool-list"
        val a = seed(s, lanes = 1)
        val b = seed(s, lanes = 1)

        val r = CheckAgentTool.execute("""{"tool_title":"t","op":"list"}""", s, null)

        assertTrue(r.success)
        assertTrue(r.output, r.output.contains("2 dispatch record(s)"))
        assertTrue(r.output, r.output.contains(a.id.take(8)))
        assertTrue(r.output, r.output.contains(b.id.take(8)))
    }

    @Test
    fun `an unknown dispatch id yields a recovery hint, not an empty report`() = runBlocking {
        val s = "sess-tool-missing"
        seed(s, lanes = 1)

        val r = CheckAgentTool.execute(
            """{"tool_title":"t","op":"collect","dispatch_id":"deadbeef"}""",
            s,
            null,
        )

        assertFalse(r.success)
        assertTrue(r.output, r.output.contains("spawn_agent"))
    }

    @Test
    fun `malformed arguments are reported instead of throwing`() = runBlocking {
        val r = CheckAgentTool.execute("not json", "sess-tool-badjson", null)

        assertFalse(r.success)
        assertTrue(r.output, r.output.contains("invalid check_agent arguments"))
    }

    @Test
    fun `a short dispatch id prefix resolves to its wave`() = runBlocking {
        val s = "sess-tool-prefix"
        val b = seed(s, lanes = 1)
        finishAll(s, b.id, lanes = 1)

        val r = CheckAgentTool.execute(
            """{"tool_title":"t","op":"collect","dispatch_id":"${b.id.take(8)}"}""",
            s,
            null,
        )

        assertTrue(r.success)
        assertTrue(r.output, r.output.contains("dispatch ${b.id.take(8)}"))
        assertTrue(collectedAt(s, b.id) > 0L)
    }

    @Test
    fun `the tool definition advertises every operation`() {
        val op = CheckAgentTool.definition().parameters.getValue("op")
        assertEquals(listOf("status", "await", "collect", "list", "clear"), op.enumValues)
        assertEquals(listOf("tool_title"), CheckAgentTool.definition().required)
    }
}
