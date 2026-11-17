package com.openminis.app.tools

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-subagent-report-spill] / [T-subagent-history-spill] Two related caps in
 * the sub-agent pipeline, both pinned here as pure-JVM decisions:
 *
 *  - the composed report's 64k cap used to be a TAIL clip ("…(truncated)\n" +
 *    takeLast) — the head vanished silently; now the head is preserved too,
 *    the note is explicit (spill path when the full report was persisted,
 *    exact omitted count otherwise), and the budget keeps the composed body
 *    spillable by the dispatch layer;
 *  - the history compactor's stubs used to have NO recovery line; they now
 *    mirror [ToolResultAging]'s recoverable-stub pattern (embedded spill path
 *    reused, oversized pathless results spilled via the hook or told to
 *    re-run).
 */
class SubAgentReportSpillTest {

    /**
     * 90k composed body whose "X" marker sits strictly between the preserved
     * head (8k) and tail (~56k) windows, so the omitted-middle assertions are
     * unambiguous.
     */
    private fun overCapReport(): String =
        "H".repeat(20_000) + "X".repeat(400) + "M".repeat(49_600) + "T".repeat(20_000)

    // -- composeOutput / truncate ([T-subagent-report-spill]) ----------------

    @Test
    fun underTheCapTheComposedOutputIsUntouched() {
        var spillCalls = 0
        val out = SubAgentRunner.composeOutput("body", "trace-1") {
            spillCalls++
            null
        }
        assertTrue(out.contains("## Trace"))
        assertTrue(out.contains("## Report"))
        assertFalse(out.contains("(truncated"))
        assertEquals(0, spillCalls)
    }

    @Test
    fun overCapPreservesHeadAndTailAndReportsTheOmittedCount() {
        val out = SubAgentRunner.composeOutput(overCapReport(), "")
        assertTrue(out.contains("## Report"))
        // Head preserved (the old takeLast() lost it entirely).
        assertTrue(out.contains("H".repeat(32)))
        // Tail preserved (reports are tail-heavy).
        assertTrue(out.contains("T".repeat(32)))
        // Middle elided and accounted.
        assertFalse(out.contains("X".repeat(100)))
        assertTrue(out.contains("(truncated — "))
        assertTrue(out.contains(" chars omitted between the preserved head and tail excerpts)"))
        // Budget kept: the dispatch layer's session spill still sees a bounded body.
        assertTrue(out.length <= SubAgentRunner.MAX_REPORT_CHARS)
    }

    @Test
    fun overCapWithASpillHookNamesTheFullReportPath() {
        var spilled: String? = null
        val out = SubAgentRunner.composeOutput(
            overCapReport(),
            "",
            spillFullReport = { full ->
                spilled = full
                "/var/minis/workspace/tool-spill/subagent-report-abc123.txt"
            },
        )
        assertTrue(
            out.contains("(truncated — full report at /var/minis/workspace/tool-spill/subagent-report-abc123.txt)"),
        )
        // The hook receives the COMPOSED body (what the note describes).
        assertNotNull(spilled)
        assertTrue(spilled!!.startsWith("## Report"))
        assertEquals(overCapReport().length + "## Report\n".length, spilled!!.length)
        // No omitted-count note when the full report is recoverable.
        assertFalse(out.contains("chars omitted between the preserved head and tail excerpts"))
    }

    // -- SubAgentHistoryCompactor ([T-subagent-history-spill]) ---------------

    /** One old tool-result message; `compact` stubs everything past the newest 4. */
    private fun history(vararg contents: String): List<LLMMessage> =
        contents.mapIndexed { i, c ->
            LLMMessage(
                role = LLMMessage.Role.USER,
                content = "",
                contentParts = listOf(AgentContentPart.ToolResult(id = "t$i", name = "file_read", content = c)),
            )
        }

    /**
     * Four fresh results large enough that any [history] built with them
     * exceeds the compactor's ~120k total budget (compaction is a no-op
     * below it).
     */
    private val fresh: Array<String> =
        (1..4).map { "y".repeat(27_000) }.toTypedArray()

    @Test
    fun stubReusesAnEmbeddedSpillPath() {
        // Five oversized results: total > TOTAL_BUDGET, oldest one stubbed.
        val spillPath = "/var/minis/workspace/tool-spill/call-1.txt"
        val embedded = "x".repeat(24_000) +
            "\nFull output saved to $spillPath — use file_read if you need more.\n"
        val out = SubAgentHistoryCompactor.compact(history(embedded, *fresh))
        val stubbed = out.first().contentParts.first() as AgentContentPart.ToolResult
        assertTrue(stubbed.content.length < embedded.length)
        assertTrue(stubbed.content.contains("sub-agent history compaction"))
        // The recoverable-stub pattern: the path survives the fold.
        assertTrue(stubbed.content.contains("full output: $spillPath"))
    }

    @Test
    fun oversizedPathlessStubIsSpilledViaTheHook() {
        var spilledName: String? = null
        var spilledContent: String? = null
        val old = "z".repeat(25_000)
        val out = SubAgentHistoryCompactor.compact(history(old, *fresh)) { name, content ->
            spilledName = name
            spilledContent = content
            "/var/minis/workspace/tool-spill/history-abc.txt"
        }
        val stubbed = out.first().contentParts.first() as AgentContentPart.ToolResult
        assertTrue(stubbed.content.contains("full output: /var/minis/workspace/tool-spill/history-abc.txt"))
        assertEquals("file_read", spilledName)
        assertEquals(old, spilledContent)
    }

    @Test
    fun oversizedPathlessStubWithoutAHookSaysReRun() {
        val old = "z".repeat(25_000)
        val out = SubAgentHistoryCompactor.compact(history(old, *fresh))
        val stubbed = out.first().contentParts.first() as AgentContentPart.ToolResult
        assertTrue(stubbed.content.contains("full content: re-run this tool"))
        assertFalse(stubbed.content.contains("full output:"))
    }

    @Test
    fun stubUnderTheSpillLimitKeepsTheRereadWordingOnly() {
        // Stubbed (> PER_RESULT_CAP) but below the ~16k recovery threshold:
        // the existing re-read note is the recovery path; no re-run line.
        val old = "z".repeat(13_000)
        val out = SubAgentHistoryCompactor.compact(history(old, *fresh))
        val stubbed = out.first().contentParts.first() as AgentContentPart.ToolResult
        assertTrue(stubbed.content.contains("re-read the file if you need it back"))
        assertFalse(stubbed.content.contains("re-run this tool"))
        assertFalse(stubbed.content.contains("full output:"))
    }
}
