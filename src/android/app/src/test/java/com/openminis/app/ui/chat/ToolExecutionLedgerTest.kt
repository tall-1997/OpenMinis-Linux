package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-tool-execution-ledger] The single-execution contract: one call executes
 * once and owns exactly one result. The key is (id, name, args) because a
 * provider may reuse a numbered id for a different call in a later turn.
 *
 * Both the serial lane and the spawn fan-out go through [executeOnce], so a
 * replayed spawn call must NEVER re-run its side effect. These tests cover
 * replay, cross-key identity, concurrent duplicates, and failure retry — the
 * regression that #330's post-awaitAll reuse left open.
 */
class ToolExecutionLedgerTest {

    private fun part(id: String, content: String = "ok") =
        AgentContentPart.ToolResult(id = id, name = "shell_execute", content = content)

    @Test
    fun `a completed call is reused and its side effect runs once`() = runTest {
        val ledger = ToolExecutionLedger()
        val part = part("call_1")
        var executions = 0
        val first = ledger.executeOnce("call_1", "shell_execute", "sha-a") {
            executions++
            part
        }
        var reused = false
        val second = ledger.executeOnce(
            "call_1", "shell_execute", "sha-a",
            onReuse = { reused = true },
        ) {
            executions++
            part("call_1", "second")
        }
        assertSame(part, first)
        assertSame(part, second)
        assertEquals(1, executions)
        assertTrue(reused)
        assertEquals(1, ledger.size())
    }

    @Test
    fun `an unrecorded call is not reused`() = runTest {
        val ledger = ToolExecutionLedger()
        assertNull(ledger.reuse("call_missing", "shell_execute", "sha-a"))
        assertNull(ledger.reuse("", "shell_execute", "sha-a"))
    }

    @Test
    fun `the same id with different name or arguments is a different call`() = runTest {
        val ledger = ToolExecutionLedger()
        ledger.executeOnce("call_0", "shell_execute", "sha-old") { part("call_0", "old") }
        var otherExecuted = false
        val fresh = ledger.executeOnce("call_0", "shell_execute", "sha-new") {
            otherExecuted = true
            part("call_0", "new")
        }
        assertTrue("a new args hash must execute, not reuse a stale result", otherExecuted)
        assertEquals("new", fresh.content)
        assertNull(
            "a provider that numbers calls per response must not get a stale result",
            ledger.reuse("call_0", "read_file", "sha-old"),
        )
        assertEquals(2, ledger.size())
    }

    @Test
    fun `a replay cannot overwrite the first result`() = runTest {
        val ledger = ToolExecutionLedger()
        ledger.executeOnce("call_2", "shell_execute", "sha-x") { part("call_2", "first") }
        val replay = ledger.executeOnce("call_2", "shell_execute", "sha-x") {
            part("call_2", "second")
        }
        assertEquals("first", replay.content)
        assertEquals(1, ledger.size())
    }

    @Test
    fun `concurrent duplicates join one in-flight execution`() = runTest {
        val ledger = ToolExecutionLedger()
        val part = part("call_3")
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        var executions = 0
        // The owner claims the key and suspends inside produce(), holding the
        // InFlight window open; the duplicate arrives while it is in flight.
        val owner = async {
            ledger.executeOnce("call_3", "shell_execute", "sha-x") {
                executions++
                started.complete(Unit)
                gate.await()
                part
            }
        }
        started.await()
        val duplicate = async {
            ledger.executeOnce("call_3", "shell_execute", "sha-x") {
                executions++
                part("call_3", "second")
            }
        }
        gate.complete(Unit)
        assertSame(part, owner.await())
        assertSame(part, duplicate.await())
        assertEquals(1, executions)
    }

    @Test
    fun `a failed execution leaves the key claimable for retry`() = runTest {
        val ledger = ToolExecutionLedger()
        val failed = runCatching {
            ledger.executeOnce("call_4", "shell_execute", "sha-x") {
                throw RuntimeException("disk on fire")
            }
        }
        assertTrue(failed.isFailure)
        assertNull(ledger.reuse("call_4", "shell_execute", "sha-x"))
        val retried = ledger.executeOnce("call_4", "shell_execute", "sha-x") {
            part("call_4", "retry")
        }
        assertEquals("retry", retried.content)
    }

    @Test
    fun `a replay settles its UI card in a terminal status`() {
        // A replay never enters the producer, so its phase-1 PENDING card has
        // no other path to a terminal state. The mapping is shared by both
        // lanes and mirrors finalizePlannedCall's success/failure split.
        assertEquals(
            ToolBlockStatus.SUCCESS,
            settledReplayStatus(part("call_ok", "ok")),
        )
        assertEquals(
            ToolBlockStatus.FAILED,
            settledReplayStatus(
                AgentContentPart.ToolResult(
                    id = "call_err", name = "shell_execute",
                    content = "boom", isError = true,
                ),
            ),
        )
    }

    @Test
    fun `onReuse runs for completed-key replay and in-flight join`() = runTest {
        val ledger = ToolExecutionLedger()
        val part = part("call_5")
        ledger.executeOnce("call_5", "shell_execute", "sha-x") { part }
        var completedReuseCalls = 0
        ledger.executeOnce(
            "call_5", "shell_execute", "sha-x",
            onReuse = { completedReuseCalls++ },
        ) { part("call_5", "second") }
        assertEquals(1, completedReuseCalls)

        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        var joinReuseCalls = 0
        val owner = async {
            ledger.executeOnce("call_6", "shell_execute", "sha-x") {
                started.complete(Unit)
                gate.await()
                part("call_6")
            }
        }
        started.await()
        val joiner = async {
            ledger.executeOnce(
                "call_6", "shell_execute", "sha-x",
                onReuse = { joinReuseCalls++ },
            ) { part("call_6", "second") }
        }
        gate.complete(Unit)
        owner.await()
        joiner.await()
        assertEquals("in-flight duplicates must also settle their UI card", 1, joinReuseCalls)
    }
}
