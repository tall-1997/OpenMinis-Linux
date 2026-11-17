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
 * [T-executecode-spill] The stdout cap is a preview trigger, not a loss point:
 * past [ExecuteCodeTool.MAX_STDOUT] the full stdout must stay recoverable
 * (spill file + path when a session is wired, full-text deferral to the
 * unified exit otherwise), and chars dropped past the capture ceiling are
 * reported, never silent.
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
    fun overCapStdoutKeepsHeadAndTailInsteadOfTakeClipping() = runBlocking {
        // ~60k chars of print output: the old `text.take(MAX_STDOUT)` silently
        // dropped the last ~10k. The tail must now survive end to end.
        val result = run("for (var i = 0; i < 6000; i++) print('line-' + i)")
        assertTrue(result.success)
        assertTrue(result.output.contains("line-0\n"))
        assertTrue(result.output.contains("line-5999\n"))
        assertTrue(result.output.length > ExecuteCodeTool.MAX_STDOUT)
    }

    @Test
    fun overCapStdoutWithSpillPathBecomesTheSharedPlaceholderPreview() {
        val big = "H".repeat(ExecuteCodeTool.MAX_STDOUT + 10)
        val out = ExecuteCodeTool.presentOverCap(big, "/var/minis/workspace/tool-spill/x.txt")
        assertTrue(out.startsWith("[tool-output-spill] execute_code produced ${big.length} chars."))
        assertTrue(out.contains("Full output saved to /var/minis/workspace/tool-spill/x.txt"))
        assertTrue(out.contains("file_read"))
        assertTrue(out.length < big.length)
    }

    @Test
    fun overCapStdoutWithoutASessionDefersTheFullTextToTheUnifiedExit() {
        // No spill path available here: the FULL captured buffer goes to the
        // caller, whose ToolOutputPolicy.apply owns the cap + spill. A silent
        // take() is exactly what must NOT come back.
        val big = "H".repeat(ExecuteCodeTool.MAX_STDOUT + 10)
        assertEquals(big, ExecuteCodeTool.presentStdout(big, 0L, null, null))
    }

    @Test
    fun printCharsDroppedPastTheCaptureCeilingAreReported() {
        val big = "H".repeat(ExecuteCodeTool.MAX_STDOUT + 10)
        val out = ExecuteCodeTool.presentStdout(big, 1_234L, null, null)
        assertTrue(out.contains("(print cap reached — 1234 more chars were dropped"))
        assertTrue(out.contains("capture bound"))
    }
}
