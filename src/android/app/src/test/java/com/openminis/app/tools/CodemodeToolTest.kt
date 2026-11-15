package com.openminis.app.tools

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun gateListsTheEntryToolAsCodemodeCallable() {
        // Bootstrap: a codemode script can call the codemode entry itself.
        val registry = AgentTools.makeAgentTools(includeLongTail = true)
        assertTrue(CodemodeTool.NAME in ToolExposureGate.callableNamesForCodemode(registry))
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
}
