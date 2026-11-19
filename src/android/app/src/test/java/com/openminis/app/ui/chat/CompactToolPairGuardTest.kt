package com.openminis.app.ui.chat

import com.openminis.app.data.CompactSliceBoundaries
import com.openminis.app.data.preserveToolPairsAtBoundaries
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactToolPairGuardTest {
    private fun text(value: String) = LLMMessage(LLMMessage.Role.USER, value)

    private fun uses(vararg ids: String) = LLMMessage(
        LLMMessage.Role.ASSISTANT, "",
        contentParts = ids.map { AgentContentPart.ToolUse.fromInputJson(it, "shell_execute", "{}") },
    )

    private fun result(id: String, name: String = "shell_execute") = LLMMessage(
        LLMMessage.Role.USER, "",
        contentParts = listOf(AgentContentPart.ToolResult(id, name, "out")),
    )

    @Test
    fun `nonadjacent parallel results use the normalized call ID and close only at the last carrier`() {
        val history = listOf(text("old"), uses("a|fc_a", "b"), text("note"), result("b"), result("a", "shell"), text("next"))
        val boundaries = CompactSliceBoundaries(history)
        assertEquals(listOf(0, 1, 5, 6), (0..history.size).filter(boundaries::isClosed))
        assertEquals(1, boundaries.interiorSplit())
        assertEquals(1 to 0, preserveToolPairsAtBoundaries(history, 1, 1))
        assertEquals(1 to 5, preserveToolPairsAtBoundaries(history, 4, 5))
    }

    @Test
    fun `duplicate IDs pair in order but mismatched names and result-before-use do not pair`() {
        val history = listOf(result("a"), uses("a"), uses("a"), result("a", "unrelated"), result("a"), result("a"))
        val boundaries = CompactSliceBoundaries(history)
        assertEquals(listOf(0, 1, 6), (0..history.size).filter(boundaries::isClosed))
    }

    @Test
    fun `bounded page with a pending result leaves the call active`() {
        val page = listOf(text("first"), uses("a", "b"), result("a"))
        val boundaries = CompactSliceBoundaries(page, protectPending = true)
        assertEquals(1, boundaries.atOrBefore(page.size))
        assertFalse(boundaries.isClosed(page.size))
    }

    @Test
    fun `truncated stage retries at a closed boundary and keeps previous summary in the first segment`() = runBlocking {
        val history = listOf(text("initial intent"), uses("a", "b"), result("a"), result("b"))
        val sent = mutableListOf<String>()
        val summary = summarizeCompactStage(history, "previous decision", Int.MAX_VALUE, callBudget = 3) { input, depth ->
            sent += input.messages.single().content
            when (sent.size) {
                1 -> {
                    assertEquals(0, depth)
                    throw CompactSummaryTermination("length", "test")
                }
                2 -> "previous decision and initial intent"
                else -> "complete tool round"
            }
        }
        assertEquals("previous decision and initial intent\n\ncomplete tool round", summary)
        assertEquals(3, sent.size)
        assertTrue(sent[0].contains("previous decision"))
        assertTrue(sent[1].contains("previous decision"))
        assertTrue(sent[1].contains("initial intent"))
        assertFalse(sent[1].contains("[tool shell_execute]"))
        assertFalse(sent[2].contains("previous decision"))
        assertFalse(sent[2].contains("initial intent"))
        assertTrue(sent[2].contains("[tool shell_execute]"))
        assertEquals(2, Regex("\\[result shell_execute:").findAll(sent[2]).count())
    }

    @Test
    fun `unsplittable round takes one whole request and does not retry following truncation`() = runBlocking {
        val round = listOf(uses("a", "b"), result("a"), result("b"))
        var calls = 0
        val result = runCatching {
            summarizeCompactStage(round, "previous decision", tokenBudget = 0, callBudget = 6) { input, depth ->
                calls++
                assertEquals(0, depth)
                val sent = input.messages.single().content
                assertTrue(sent.contains("previous decision"))
                assertTrue(sent.contains("[tool shell_execute]"))
                assertEquals(2, Regex("\\[result shell_execute:").findAll(sent).count())
                throw CompactSummaryTermination("length", "test")
            }
        }
        assertTrue(result.exceptionOrNull() is CompactSummaryTermination)
        assertEquals(1, calls)
    }

    @Test
    fun `second segment failure cannot turn the first summary into covered rows`() = runBlocking {
        val history = listOf(text("initial intent"), uses("a"), result("a"))
        var calls = 0
        val result = runCatching {
            runCompactPasses(history, "previous decision", stageCount = 1) { input, previous, _, _ ->
                summarizeCompactStage(input, previous, tokenBudget = 0, callBudget = 2) { _, _ ->
                    calls++
                    if (calls == 1) "A plausible but incomplete summary of the initial intent. ".repeat(5)
                    else throw CompactSummaryTermination("length", "test")
                }
            }
        }
        assertEquals(2, calls)
        assertTrue(result.exceptionOrNull() is CompactSummaryTermination)
    }

    private fun retryHistory() = List(7) { text("roadmap $it") } +
        listOf(uses("a", "b"), result("a"), result("b")) + List(4) { text("unused $it") }

    @Test
    fun `reduced retry retreats rather than claiming a result after its cut`() {
        val history = retryHistory()
        assertEquals(history.take(7), reducedCompactRetryInput(history))
        assertNull(reducedCompactRetryInput(List(6) { uses("$it") } + List(6) { result("$it") }))
    }

    @Test
    fun `full and reduced fallback passes carry previous summary and report only the successful input`() = runBlocking {
        val history = retryHistory()
        val seen = mutableListOf<Triple<Int, Int, Boolean>>()
        val accepted = "The user asked about the roadmap. ".repeat(5)
        val outcome = runCompactPasses(history, "earlier decision", 2) { input, previous, index, reduced ->
            assertEquals("earlier decision", previous)
            assertEquals(history.take(input.size), input)
            seen += Triple(input.size, index, reduced)
            if (reduced && index == 1) accepted else error("unavailable")
        }
        assertEquals(listOf(Triple(14, 0, false), Triple(14, 1, false), Triple(7, 0, true), Triple(7, 1, true)), seen)
        assertEquals(7, outcome.coveredMessages)
        assertEquals(accepted.trim(), outcome.summary)
    }

    @Test
    fun `all failed or too-short replies throw instead of returning a summary`() = runBlocking {
        var attempts = 0
        val failure = IllegalStateException("provider failed")
        val result = runCatching {
            runCompactPasses(retryHistory(), "prior", 2) { _, _, _, _ ->
                attempts++
                if (attempts < 4) "truncated" else throw failure
            }
        }
        assertSame(failure, result.exceptionOrNull())
        assertEquals(4, attempts)
    }

    @Test
    fun `cancelled pass neither retries nor reports coverage`() = runBlocking {
        var attempts = 0
        val cancellation = CancellationException("cancelled")
        val result = runCatching {
            runCompactPasses(retryHistory(), null, 2) { _, _, _, _ ->
                attempts++
                throw cancellation
            }
        }
        assertSame(cancellation, result.exceptionOrNull())
        assertEquals(1, attempts)
        assertTrue(result.isFailure)
    }
}
