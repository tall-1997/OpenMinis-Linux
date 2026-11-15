package com.openminis.app.tools

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [pi-port §2.7] CodemodeTool: the direct-exposure entry tool whose nested
 * tool calls never enter the conversation — only the script's return value
 * becomes the toolResult the model sees.
 */
class CodemodeToolTest {

    /** Records every nested call; answers canned outputs (executor convention). */
    private class RecordingInvoke(
        private val answers: (name: String, argsJson: String) -> ToolExecutionResult,
    ) {
        val calls = mutableListOf<Pair<String, String>>()

        suspend fun invoke(name: String, argsJson: String): ToolExecutionResult {
            calls += name to argsJson
            return answers(name, argsJson)
        }
    }

    private fun execute(
        code: String,
        invoke: RecordingInvoke,
        extraArgs: String = "",
        callableNames: Set<String>? = null,
    ): ToolExecutionResult = runBlocking {
        CodemodeTool.execute(
            """{"tool_title":"T","code":${org.json.JSONObject.quote(code)}$extraArgs}""",
            invoke::invoke,
            callableNames,
        )
    }

    // ── definition ──

    @Test
    fun definitionIsDirectExposureAndSelfDescribing() {
        val def = CodemodeTool.definition()
        assertEquals(CodemodeTool.NAME, def.name)
        assertEquals(ToolExposureGate.DIRECT, def.exposure)
        // The schema face must teach the context-economy contract.
        assertTrue(def.description.contains("return only its final value"))
        assertTrue(def.description.contains("OUT of the conversation"))
    }

    @Test
    fun gateExcludesTheEntryToolFromTheCodemodeCallableSet() {
        // Self-recursion: a script re-entering execute_code_js parks one
        // Dispatchers.IO thread per nesting level in the bridge's runBlocking
        // (~64 levels deadlock the pool, review P1-8). The entry tool stays
        // declared (model-visible) but is NOT script-callable.
        val registry = AgentTools.makeAgentTools(includeLongTail = true)
        assertFalse("the codemode entry must be excluded from the script-callable set",
            CodemodeTool.NAME in ToolExposureGate.callableNamesForCodemode(registry))
    }

    // ── execution ──

    @Test
    fun arithmeticReturnIsTheWholeOutput() = runBlocking {
        val result = execute("return 1 + 1;", RecordingInvoke { _, _ -> ToolExecutionResult("unused", true) })
        assertTrue(result.success)
        assertEquals("2", result.output)
    }

    @Test
    fun twoToolCallsLeaveOnlyTheScriptReturnInTheResult() = runBlocking {
        val invoke = RecordingInvoke { name, _ ->
            when (name) {
                "file_read" -> ToolExecutionResult("SECRET_FILE_BODY_THAT_MUST_NOT_LEAK", true)
                "list_dir" -> ToolExecutionResult("SECRET_DIR_BODY_THAT_MUST_NOT_LEAK", true)
                else -> ToolExecutionResult("ERROR: unknown", false)
            }
        }
        val result = execute(
            """
            var a = tools.file_read({"path": "/a"});
            var b = tools.list_dir({"path": "/dir"});
            return a.length + b.length;
            """.trimIndent(),
            invoke,
        )
        // The script ran both nested calls through the executor...
        assertEquals(listOf("file_read" to """{"path":"/a"}""", "list_dir" to """{"path":"/dir"}"""), invoke.calls)
        // ...but ONLY its computed return value reached the toolResult
        // (35 + 34 = the two canned bodies' lengths).
        assertEquals("69", result.output)
        assertFalse(result.output.contains("SECRET_FILE_BODY"))
        assertFalse(result.output.contains("SECRET_DIR_BODY"))
    }

    @Test
    fun failedNestedToolIsScriptVisibleNotAToolFailure() = runBlocking {
        val invoke = RecordingInvoke { _, _ -> ToolExecutionResult("boom reason", false) }
        val result = execute("return tools.file_read({'path': '/x'});", invoke)
        // The executor's failure crossed INTO the script (its decision), and
        // the script's return is a SUCCESSFUL tool result.
        assertTrue(result.success)
        assertTrue(result.output.startsWith("ERROR:"))
        assertTrue(result.output.contains("boom reason"))
    }

    @Test
    fun defaultCallableSetComesFromTheExposureGate() = runBlocking {
        val invoke = RecordingInvoke { _, _ -> ToolExecutionResult("ok", true) }
        val result = execute(
            "return (Object.keys(tools).indexOf('file_read') >= 0) + ':' + (Object.keys(tools).indexOf('execute_code_js') >= 0);",
            invoke,
        )
        // file_read is callable; the entry tool itself is excluded (review
        // P1-8 — a script re-entering it parks an IO thread per level).
        assertEquals("true:false", result.output)
    }

    @Test
    fun defaultCallableSetBuildsFromTheAllFlagsRegistry() = runBlocking {
        // review P2-14: the bridge used to be built with vision/speech flags
        // OFF, which silently dropped read_image / transcribe_audio from the
        // bridge — scripts saw a JS TypeError instead of the executor's honest
        // refusal. The default set now rides the same all-flags registry as
        // ToolExposureGate.levelByName.
        val invoke = RecordingInvoke { _, _ -> ToolExecutionResult("ok", true) }
        val result = execute(
            "return (Object.keys(tools).indexOf('read_image') >= 0) + ':' + (Object.keys(tools).indexOf('transcribe_audio') >= 0);",
            invoke,
        )
        assertEquals("true:true", result.output)
    }

    @Test
    fun explicitCallableSetNarrowsTheBridge() = runBlocking {
        val invoke = RecordingInvoke { _, _ -> ToolExecutionResult("ok", true) }
        val result = execute(
            "return Object.keys(tools).join(',');",
            invoke,
            callableNames = setOf("glob"),
        )
        assertEquals("glob", result.output)
    }

    @Test
    fun syntaxErrorIsAnHonestToolFailure() = runBlocking {
        val result = execute("var var var;", RecordingInvoke { _, _ -> ToolExecutionResult("x", true) })
        assertFalse(result.success)
        assertTrue(result.output.contains("codemode failed"))
    }

    @Test
    fun timeoutIsReportedWithTheTimeoutErrorCode() = runBlocking {
        val result = runBlocking {
            CodemodeTool.execute(
                """{"tool_title":"T","code":"while (true) { }","timeout_seconds":1}""",
                { _, _ -> ToolExecutionResult("x", true) },
            )
        }
        assertFalse(result.success)
        assertEquals(ToolErrorCode.TIMEOUT, result.errorCode)
        assertTrue(result.timedOut)
    }

    @Test
    fun blankCodeFailsBeforeEnteringTheSandbox() = runBlocking {
        val result = CodemodeTool.execute(
            """{"tool_title":"T"}""",
            { _, _ -> ToolExecutionResult("x", true) },
        )
        assertFalse(result.success)
        assertTrue(result.output.contains("code required"))
    }

    @Test
    fun noReturnValueIsExplainedNotSilent() = runBlocking {
        val result = execute("var x = 1;", RecordingInvoke { _, _ -> ToolExecutionResult("x", true) })
        assertTrue(result.success)
        assertTrue(result.output.contains("no return value"))
        assertTrue(result.output.contains("return"))
    }

    // ── nesting depth guard (review P1-8) ──

    @Test
    fun nestedCodemodeAttemptIsRefusedBeforeReenteringTheSandbox() = runBlocking {
        // An explicit callable set can still name the entry tool (the
        // callable-set exclusion is defense a); the bridge refusal is
        // defense b. The refusal must fire BEFORE any nested execution starts
        // — parking a second Dispatchers.IO thread in runBlocking is what
        // deadlocks the pool.
        val invoke = RecordingInvoke { _, _ -> ToolExecutionResult("never reached", true) }
        val result = execute(
            "return tools.execute_code_js({'code': 'return 1;'});",
            invoke,
            callableNames = setOf("execute_code_js"),
        )
        assertTrue(result.success)
        assertTrue(result.output.startsWith("ERROR:"))
        assertTrue(result.output.contains("nesting is not supported"))
        assertTrue(result.output.contains("inline the logic in one script"))
        assertTrue("the nested execution must never start", invoke.calls.isEmpty())
    }

    @Test
    fun bridgeRefusesAtTheSecondCodemodeFrame() {
        // Depth-2 direct probe: this thread already runs two sandbox frames,
        // so an entry attempt is refused and the injected executor never runs.
        CodemodeTool.NestingGuard.enter()
        CodemodeTool.NestingGuard.enter()
        try {
            var invoked = false
            val bridge = CodemodeTool.ExecutorBridge(
                { _, _ -> invoked = true; ToolExecutionResult("never reached", true) },
                setOf("execute_code_js"),
            )
            val answer = bridge.callTool(CodemodeTool.NAME, "{}")
            assertTrue(answer.startsWith("ERROR:"))
            assertTrue(answer.contains("nesting is not supported"))
            assertFalse("the nested execution must never start", invoked)
        } finally {
            CodemodeTool.NestingGuard.exit()
            CodemodeTool.NestingGuard.exit()
        }
    }

    @Test
    fun bridgeStillDispatchesOrdinaryToolsInsideASandboxFrame() {
        // The guard blocks ONLY the entry tool: an ordinary nested tool call
        // inside a script frame must still cross the bridge normally.
        CodemodeTool.NestingGuard.enter()
        try {
            val bridge = CodemodeTool.ExecutorBridge(
                { name, _ -> ToolExecutionResult("answered $name", true) },
                setOf("file_read"),
            )
            assertEquals("answered file_read", bridge.callTool("file_read", "{}"))
        } finally {
            CodemodeTool.NestingGuard.exit()
        }
    }

    @Test
    fun guardDepthRestartsFromZeroAfterTheThreadLocalIsRemoved() {
        // Balanced pair: exit() drops the ThreadLocal mapping (P3 hygiene for
        // pooled Dispatchers.IO threads — no boxed depth parked forever) once
        // the frame stack is empty. Counting must restart from the 0 baseline
        // afterwards: the next enter on this thread is depth 1 again.
        CodemodeTool.NestingGuard.enter()
        CodemodeTool.NestingGuard.exit()
        CodemodeTool.NestingGuard.enter()
        try {
            assertTrue(CodemodeTool.NestingGuard.insideScript())
        } finally {
            CodemodeTool.NestingGuard.exit()
        }
        assertFalse(CodemodeTool.NestingGuard.insideScript())
    }

    @Test
    fun innerExitKeepsTheOuterFramesMappingAlive() {
        // remove() fires only when the depth lands back at 0: leaving an INNER
        // frame must not drop the OUTER frame's mapping (a naive unconditional
        // remove would make this assertion fail).
        CodemodeTool.NestingGuard.enter()
        CodemodeTool.NestingGuard.enter()
        CodemodeTool.NestingGuard.exit()
        try {
            assertTrue(
                "outer frame must still count as inside a script",
                CodemodeTool.NestingGuard.insideScript(),
            )
        } finally {
            CodemodeTool.NestingGuard.exit()
        }
        assertFalse(CodemodeTool.NestingGuard.insideScript())
    }

    // ── budget trips vs clock trips (review P2-13) ──

    @Test
    fun toolCallCapIsALimitNotATimeout() = runBlocking {
        // 201 nested calls trip the 200-call cap: the run reports a BUDGET
        // limit (CODEMODE_LIMIT), not a timeout — plain failure, no UI
        // timeout clock, no "return partial results" hint.
        val invoke = RecordingInvoke { _, _ -> ToolExecutionResult("ok", true) }
        val script = "for (var i = 0; i < 201; i++) { tools.file_read({'path': '/a'}); } return i;"
        val result = execute(script, invoke)
        assertFalse(result.success)
        assertEquals(ToolErrorCode.CODEMODE_LIMIT, result.errorCode)
        assertFalse("the cap is not a clock trip", result.timedOut)
        assertTrue(result.output.contains("tool-call cap"))
        assertNull("no timeout recovery hint for a budget trip", result.recoveryHint)
    }
}
