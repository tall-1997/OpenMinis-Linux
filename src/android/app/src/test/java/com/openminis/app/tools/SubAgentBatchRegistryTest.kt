package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-subagent-background] `unfinished` is the set the agent loop nudges about
 * before a turn ends, so every test here pins one way background work could be
 * lost — or, just as bad, nag the user forever after it was already picked up.
 * Runs memory-only (context = null).
 */
class SubAgentBatchRegistryTest {

    private val registry = SubAgentBatchRegistry

    private fun seed(session: String, background: Boolean, lanes: Int = 2): SubAgentBatchRegistry.Batch =
        registry.begin(
            sessionId = session,
            context = null,
            toolId = "tool-1",
            assistantId = "asst-1",
            background = background,
            seeds = (1..lanes).map {
                SubAgentBatchRegistry.LaneSeed(
                    index = it,
                    kind = "worker",
                    title = "lane $it",
                    prompt = "do $it",
                )
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

    @Test
    fun `unfinished reports a background wave with lanes in flight`() {
        val s = "sess-reg-inflight"
        val b = seed(s, background = true, lanes = 2)
        registry.markRunning(s, null, b.id, 1, null, "gpt-x", 1)

        val pending = registry.unfinished(s, null)

        assertEquals(listOf(b.id), pending.map { it.id })
        // lane 1 running + lane 2 still pending: both owe a result.
        assertEquals(2, pending.single().running)
    }

    @Test
    fun `unfinished ignores inline waves whose reports were returned synchronously`() {
        val s = "sess-reg-inline"
        val inline = seed(s, background = false, lanes = 1)
        // Never even marked running — an inline wave still counts as unfinished
        // by lane state, but its report went straight back to the caller.
        assertEquals(emptyList<SubAgentBatchRegistry.Batch>(), registry.unfinished(s, null))

        finishAll(s, inline.id, lanes = 1)
        assertEquals(emptyList<SubAgentBatchRegistry.Batch>(), registry.unfinished(s, null))
    }

    @Test
    fun `unfinished keeps a finished background wave until someone collects it`() {
        val s = "sess-reg-uncollected"
        val b = seed(s, background = true, lanes = 1)
        finishAll(s, b.id, lanes = 1)

        val pending = registry.unfinished(s, null)

        // This is the bug the feature exists for: the work IS done, the reports
        // exist, and nothing would ever tell the model to go read them.
        assertEquals(listOf(b.id), pending.map { it.id })
        assertTrue(pending.single().complete)
        assertEquals(0L, pending.single().collectedAt)
    }

    @Test
    fun `markCollected clears the wave and does not overwrite the first stamp`() {
        val s = "sess-reg-idem"
        val b = seed(s, background = true, lanes = 1)
        finishAll(s, b.id, lanes = 1)

        registry.markCollected(s, null, b.id)
        val first = requireNotNull(registry.get(s, null, b.id)).collectedAt
        assertTrue(first > 0L)
        assertTrue(registry.unfinished(s, null).isEmpty())

        Thread.sleep(3)
        registry.markCollected(s, null, b.id)
        assertEquals(first, requireNotNull(registry.get(s, null, b.id)).collectedAt)
    }

    @Test
    fun `markCollected on an unknown id is a no-op`() {
        val s = "sess-reg-unknown"
        seed(s, background = true, lanes = 1)
        registry.markCollected(s, null, "no-such-batch")
        assertEquals(1, registry.list(s, null).size)
    }

    @Test
    fun `unfinished reconciles a wave left behind by a previous process`() {
        val s = "sess-reg-restarted"
        val b = seed(s, background = true, lanes = 1)
        registry.markRunning(s, null, b.id, 1, "tracker-1", "gpt-x", 1)

        // Pretend this batch was written by a process that has since died.
        val real = registry.processStartedAt
        registry.processStartedAt = System.currentTimeMillis() + 60_000
        try {
            val pending = registry.unfinished(s, null)

            // The tracker died with the process: lane becomes `stopped`, the
            // wave becomes complete, and it still owes a collect so the model
            // reports the interruption instead of losing it.
            val after = pending.single()
            assertEquals(0, after.running)
            assertTrue(after.complete)
            assertEquals(SubAgentBatchRegistry.STATE_STOPPED, after.lanes.single().state)
            assertEquals(0L, after.collectedAt)
        } finally {
            registry.processStartedAt = real
        }
    }

    @Test
    fun `unfinished does not kill a wave this process is still running`() {
        val s = "sess-reg-live"
        val b = seed(s, background = true, lanes = 2)
        registry.markRunning(s, null, b.id, 1, "tracker-1", "gpt-x", 1)

        // unfinished() reconciles on read; that must be a no-op for a batch
        // this process created, or a single status poll would declare a live
        // wave dead (and a not-yet-launched lane would never survive it).
        val pending = registry.unfinished(s, null)

        assertEquals(2, pending.single().running)
        assertEquals(SubAgentBatchRegistry.STATE_RUNNING, pending.single().lanes[0].state)
        assertEquals(SubAgentBatchRegistry.STATE_PENDING, pending.single().lanes[1].state)
        assertEquals(0L, pending.single().finishedAt)
    }

    @Test
    fun `clearFinished keeps a report nobody collected and says how many`() {
        val s = "sess-reg-clear-keep"
        val kept = seed(s, background = true, lanes = 1)
        finishAll(s, kept.id, lanes = 1)
        val dropped = seed(s, background = true, lanes = 1)
        finishAll(s, dropped.id, lanes = 1)
        registry.markCollected(s, null, dropped.id)

        val (removed, keptUncollected) = registry.clearFinished(s, null)

        assertEquals(1, removed)
        assertEquals(1, keptUncollected)
        assertEquals(listOf(kept.id), registry.list(s, null).map { it.id })
    }

    @Test
    fun `clearFinished with includeUncollected drops every finished wave`() {
        val s = "sess-reg-clear-force"
        val b = seed(s, background = true, lanes = 1)
        finishAll(s, b.id, lanes = 1)

        val (removed, keptUncollected) = registry.clearFinished(s, null, includeUncollected = true)

        assertEquals(1, removed)
        assertEquals(0, keptUncollected)
        assertTrue(registry.list(s, null).isEmpty())
    }

    @Test
    fun `clearFinished leaves an in-flight wave alone`() {
        val s = "sess-reg-clear-running"
        val b = seed(s, background = true, lanes = 1)
        registry.markRunning(s, null, b.id, 1, null, "gpt-x", 1)

        val (removed, _) = registry.clearFinished(s, null)

        assertEquals(0, removed)
        assertEquals(listOf(b.id), registry.list(s, null).map { it.id })
    }

    @Test
    fun `encode and decode preserve the collected marker across a restart`() {
        val s = "sess-reg-roundtrip"
        val b = seed(s, background = true, lanes = 1)
        finishAll(s, b.id, lanes = 1)
        registry.markCollected(s, null, b.id)

        val back = registry.decode(registry.encode(registry.list(s, null)))

        assertEquals(1, back.size)
        val restored = back.single()
        assertEquals(b.id, restored.id)
        assertTrue(restored.background)
        assertTrue(restored.collectedAt > 0L)
    }

    @Test
    fun `renderPending separates in-flight waves from uncollected ones`() {
        val flying = seed("sess-reg-render-a", background = true, lanes = 2)
        val done = seed("sess-reg-render-b", background = true, lanes = 1)
        finishAll("sess-reg-render-b", done.id, lanes = 1)
        // Re-read: `done` is the dispatch-time snapshot, still all-pending.
        val doneNow = requireNotNull(registry.get("sess-reg-render-b", null, done.id))

        val text = registry.renderPending(listOf(flying, doneNow))

        assertTrue(text, text.contains("still in flight"))
        assertTrue(text, text.contains("nobody collected"))
        assertTrue(text, text.contains(flying.id.take(8)))
        assertTrue(text, text.contains(done.id.take(8)))
    }

    @Test
    fun `renderPending of nothing is empty so the loop stays silent`() {
        assertEquals("", registry.renderPending(emptyList()))
    }

    @Test
    fun `unfinished on a blank session never touches the registry`() {
        seed("sess-reg-blank", background = true, lanes = 1)
        assertTrue(registry.unfinished("", null).isEmpty())
    }
}
