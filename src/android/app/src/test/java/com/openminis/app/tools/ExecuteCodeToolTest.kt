package com.openminis.app.tools

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SEC-5: `execute_code` is documented as a JavaScript sandbox. Rhino's default
 * interop exposes Java classes (`java.lang.Runtime`, `Packages`, reflection);
 * the class shutter must make those invisible while keeping the JS bridge
 * (print / __minis_call) working.
 *
 * With no session, captured stdout is handed intact to the dispatch exit.
 * The hard capture ceiling counts any dropped characters.
 */
class ExecuteCodeToolTest {

    private fun run(code: String): ToolExecutionResult = runBlocking {
        val args = org.json.JSONObject().put("code", code).toString()
        ExecuteCodeTool.execute(args) { _, _ ->
            ToolExecutionResult("ok", true)
        }
    }

    @Test
    fun plainJavaScriptAndPrintStillWork() {
        val result = run("print('hello ' + (1 + 2))")
        assertTrue(result.success)
        assertTrue(result.output.contains("hello 3"))
    }

    @Test
    fun javaRuntimeIsNotVisible() {
        val result = run("java.lang.Runtime.getRuntime().exec('id')")
        assertFalse(result.success)
    }

    @Test
    fun packagesJavaIsNotVisible() {
        val result = run("var f = Packages.java.io.File('/etc/passwd'); f.exists()")
        assertFalse(result.success)
    }

    @Test
    fun javaClassLookupIsNotVisible() {
        val result = run("var c = java.lang.Class.forName('java.lang.Runtime'); c.name")
        assertFalse(result.success)
    }

    // -- [T-executecode-spill] stdout over the cap ---------------------------

    @Test
    fun overCapStdoutKeepsHeadAndTailInsteadOfTakeClipping() {
        val result = run("for (var i = 0; i < 6000; i++) print('line-' + i)")
        assertTrue(result.success)
        assertEquals((0 until 6000).joinToString("") { "line-$it\n" }, result.output)
    }

    @Test
    fun oneLargePrintCannotOverrunTheCaptureCapOrSplitAPair() {
        val cap = ExecuteCodeTool.MAX_CAPTURE_CHARS
        val bridge = ExecuteCodeTool.Bridge { _, _ -> error("no nested call") }
        bridge.print("x".repeat(cap - 1) + "\uD83D\uDE00")
        bridge.print("last")
        assertEquals("x".repeat(cap - 1), bridge.out.toString())
        assertEquals(8L, bridge.droppedPrintChars) // entire pair, its newline, then 'last\n'
    }

    @Test
    fun overCapStdoutWithoutASessionDefersTheFullTextToTheUnifiedExit() {
        // No spill path available here: the FULL captured buffer goes to the
        // caller, whose ToolOutputPolicy.apply owns the cap + spill. A silent
        // take() is exactly what must NOT come back.
        val big = "H".repeat(ToolOutputSpill.LIMIT + 10)
        assertEquals(big, ExecuteCodeTool.presentStdout(big, 0L, null, null))
    }

    @Test
    fun printCharsDroppedPastTheCaptureCeilingAreReported() {
        val big = "H".repeat(ToolOutputSpill.LIMIT + 10)
        val out = ExecuteCodeTool.presentStdout(big, 1_234L, null, null)
        assertTrue(out.contains("(print cap reached — 1234 more chars were dropped"))
        assertTrue(out.contains("capture bound"))
    }
}
