package com.openminis.app.tools

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMResponse
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.LLMProvider
import com.openminis.app.sandbox.SessionWorkspace
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

class SubAgentReportSpillTest {
    @Test
    fun reportExactlyAtTheSharedThresholdIsUntouched() {
        val prefix = "## Report\n"
        val body = "x".repeat(ToolOutputSpill.LIMIT - prefix.length)
        assertEquals(prefix + body, SubAgentRunner.composeOutput(body, ""))
    }

    @Test
    fun overCapWithoutSessionPreservesHeadAndTailWithoutClaimingAFile() {
        val report = "START" + "m".repeat(ToolOutputSpill.LIMIT) + "FINAL"
        val out = SubAgentRunner.composeOutput(report, "")
        assertTrue(out.contains("## Report\nSTART"))
        assertTrue(out.endsWith("FINAL"))
        assertTrue(out.contains("chars omitted"))
        assertTrue(out.contains("could not be saved or verified"))
        assertFalse(out.contains("re-run"))
        assertTrue(out.length < 3_500)
        assertNull(ToolOutputSpill.parseGuestPath(out))
    }

    @Test
    fun legacyReportCanBeOpenedButItsPrefixCannotBypassTheSpillLimit() {
        val path = "/var/minis/workspace/tool-spill/subagent-report-0123456789abcdef.txt"
        val legacy = oldReportEnvelope(path)
        assertEquals(path, ToolOutputSpill.parseGuestPath(legacy, storedToolName = "spawn_agent"))
        assertNull(ToolOutputSpill.parseGuestPath(legacy, storedToolName = "mcp"))
        assertNull(ToolOutputSpill.parseGuestPath(legacy))
        assertNull(ToolOutputSpill.parseGuestPath(legacy.replace("## Report\n", "## Report\n!"), "spawn_agent"))
        assertEquals(SpillPlan.WRITE, ToolOutputSpill.planFor(legacy, hasSession = true, sourcePath = null))
        assertEquals(SpillPlan.CAPPED, ToolOutputSpill.planFor(legacy, hasSession = false, sourcePath = null))
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

    @Test
    fun wholeRequestPressureIncludesAdvertisedSchemaAndSystem() {
        val history = history("x".repeat(13_000), *Array(4) { "ok" })
        val legacyMirrors = history.map { it.copy(content = "mirror".repeat(32_000)) }
        assertTrue(SubAgentHistoryCompactor.compact(legacyMirrors) === legacyMirrors)
        val out = SubAgentHistoryCompactor.compact(
            legacyMirrors,
            systemPrompt = "p".repeat(64_000),
            tools = listOf(AgentToolDefinition("lookup", "d".repeat(48_000), emptyMap())),
        )
        val oldest = out.first().contentParts.single() as AgentContentPart.ToolResult
        assertTrue(oldest.content.contains("sub-agent history compaction"))
        assertTrue(out.drop(1).zip(legacyMirrors.drop(1)).all { (after, before) -> after === before })
    }
}

/** Runs the actual report return path, then the same policy and reader used by the parent chat. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SubAgentReportAdapterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context by lazy {
        object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getFilesDir(): File = tmp.root
        }
    }
    private val sessionId = "report-session"
    private val lines = (1..300).map { "Finding $it: " + "\u672C\uD83D\uDE00".repeat(85) }
    private val report = lines.joinToString("\n")

    @Test
    fun completeReportHasOneVerifiedSpillAfterTheParentExitAndCanBePaged() = runBlocking {
        val result = runReport()
        assertTrue(result.success)
        val preview = parentOutput(result)
        assertEquals(result.output, preview)
        assertEquals(preview, SubAgentRunner.cardStep(preview))
        assertTrue(preview.startsWith("[tool-output-spill] sub_agent_report"))
        assertTrue(preview.contains("file_read (offset/lines)"))
        assertTrue(preview.contains(lines.first()))
        assertTrue(preview.endsWith(lines.last()))
        assertTrue(preview.length < 3_500)
        assertEquals(preview, preview.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))

        val path = requireNotNull(ToolOutputSpill.parseGuestPath(preview))
        val file = requireNotNull(ToolOutputSpill.hostFile(context, sessionId, path))
        assertEquals("## Report\n$report", file.readText())
        assertNull(ToolOutputSpill.hostFile(context, "different-session", path))
        val args = JSONObject().put("path", path).put("offset", 151).put("lines", 3).toString()
        val page = FileReadTool.execute(args, sessionId, context)
        assertTrue(page.output, page.success)
        val pageOutput = ToolOutputPolicy.apply(
            page.output, "page-call", context, sessionId, "file_read",
            ToolOutputSpill.sourcePathForTool("file_read", args),
        )
        assertEquals(lines.subList(149, 152).joinToString("\n"), pageOutput.substringAfter('\n'))
        assertEquals(listOf(file.name), file.parentFile!!.list()!!.toList())
    }

    @Test
    fun cannotWriteReportKeepsBusinessSuccessAndDoesNotAdvertiseAFile() = runBlocking {
        val workspace = SessionWorkspace.hostDir(context.filesDir, sessionId, "workspace")
        assertTrue(workspace.mkdirs())
        val occupied = File(workspace, "tool-spill").apply { writeText("occupied") }
        val result = runReport()
        assertTrue(result.success)
        assertEquals(result.output, parentOutput(result))
        assertTrue(result.output.contains("could not be saved or verified"))
        assertTrue(result.output.endsWith(lines.last()))
        assertTrue(result.output.length < 3_500)
        assertNull(ToolOutputSpill.parseGuestPath(result.output))
        assertEquals("occupied", occupied.readText())
    }

    @Test
    fun failedLaneRetainsItsDiagnosticAndReadablePartialReport() = runBlocking {
        val result = runReport(failAfterTool = true)
        assertFalse(result.success)
        val output = parentOutput(result)
        assertEquals(result.output, output)
        assertTrue(output.endsWith("Sub-agent failed: provider disconnected"))
        val path = requireNotNull(ToolOutputSpill.parseGuestPath(output))
        val file = requireNotNull(ToolOutputSpill.hostFile(context, sessionId, path))
        assertEquals(
            "## Trace\n- turn 1: file_read `/var/minis/workspace/sample.txt`\n  → sample\n\n" +
                "## Report\n$report\n\nSub-agent failed: provider disconnected",
            file.readText(),
        )
    }

    @Test
    fun legacyDetailCandidateDoesNotMakeAMissingOrOtherSessionFileReadable() {
        val path = "/var/minis/workspace/tool-spill/subagent-report-0123456789abcdef.txt"
        val candidate = requireNotNull(ToolOutputSpill.parseGuestPath(oldReportEnvelope(path), "spawn_agent"))
        assertNull(ToolOutputSpill.hostFile(context, sessionId, candidate))
        val dir = File(SessionWorkspace.hostDir(context.filesDir, sessionId, "workspace"), "tool-spill")
        assertTrue(dir.mkdirs())
        val file = File(dir, path.substringAfterLast('/')).apply { writeText("## Report\n$report") }
        assertEquals(file, ToolOutputSpill.hostFile(context, sessionId, candidate))
        assertNull(ToolOutputSpill.hostFile(context, "different-session", candidate))
    }

    private suspend fun runReport(failAfterTool: Boolean = false): ToolExecutionResult = SubAgentRunner.run(
        provider = ReportProvider(report, failAfterTool),
        modelDisplayName = "report fixture",
        userPrompt = "Summarize the fixture",
        role = null,
        skillsHint = null,
        tools = if (failAfterTool) listOf(FileReadTool.definition()) else emptyList(),
        maxTokens = 4_096,
        executeTool = { name, _, _, _ ->
            assertTrue(failAfterTool)
            assertEquals("file_read", name)
            ToolExecutionResult("sample", true)
        },
        maxTurns = 3,
        sessionId = sessionId,
        roleContext = if (failAfterTool) null else context,
        reportContext = context,
        thinkingLevel = ThinkingLevel.OFF,
        firstEventTimeoutMs = 5_000,
        streamIdleTimeoutMs = 10_000,
    )

    private fun parentOutput(result: ToolExecutionResult): String = ToolOutputSpill.maybeSpill(
        context, sessionId, "spawn_agent", "parent-finalize",
        ToolOutputPolicy.apply(result.output, "parent-dispatch", context, sessionId, "spawn_agent"),
    )

    private class ReportProvider(private val report: String, private val failAfterTool: Boolean) : LLMProvider {
        override val name = "report fixture"
        override var model = LLMModel.gpt4oMini
        private var turn = 0

        override suspend fun sendMessageClamped(
            messages: List<LLMMessage>,
            systemPrompt: String?,
            maxTokens: Int,
            temperature: Double?,
            imageParts: List<LLMMessage.ImagePart>,
            tools: List<AgentToolDefinition>,
            thinkingLevel: ThinkingLevel,
            extraHeaders: Map<String, String>,
        ): LLMResponse = error("streaming only")

        override fun streamMessageClamped(
            messages: List<LLMMessage>,
            systemPrompt: String?,
            maxTokens: Int,
            temperature: Double?,
            imageParts: List<LLMMessage.ImagePart>,
            tools: List<AgentToolDefinition>,
            thinkingLevel: ThinkingLevel,
            systemStablePrefixLen: Int,
            extraHeaders: Map<String, String>,
        ): Flow<LLMStreamChunk> = flow {
            if (++turn != 1) error("provider disconnected")
            emit(LLMStreamChunk.Text(report))
            if (failAfterTool) emit(LLMStreamChunk.ToolCallComplete(
                "fixture-read", "file_read",
                JSONObject().put("tool_title", "Read sample file").put("path", "/var/minis/workspace/sample.txt"),
            ))
            emit(LLMStreamChunk.Finished(if (failAfterTool) "tool_calls" else "stop"))
        }
    }
}

private fun oldReportEnvelope(path: String): String {
    val head = "## Report\n".padEnd(8_000, 'H')
    val tail = "T".repeat(55_840)
    return "[tool-output-spill] sub_agent_report produced 80000 chars.\n$head" +
        "\n\n…(truncated — full report at $path)…\n\n$tail"
}
