package com.openminis.app.tools

import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-execute-code-java-interop] The Rhino scope must not expose Java interop.
 *
 * Rhino's LiveConnect hands any script `java.*`, `Packages` and
 * `new java.io.File(...)` for free. `execute_code` is allow-listed for
 * explore/plan sub-agents (SubAgentKind.READ_ONLY_ALLOW), so that bridge let
 * a lane whose entire contract is "cannot write" create files and spawn
 * processes — no approval prompt in between, because the tool classifies as
 * a REVERSIBLE SYSTEM read.
 *
 * These tests fail on the old initStandardObjects() wiring (the file gets
 * created / `java` is defined) and pass with the deny-all ClassShutter.
 */
class ExecuteCodeToolSandboxTest {

    private fun run(code: String, invoke: (String, String) -> ToolExecutionResult = { _, _ ->
        ToolExecutionResult("(stub)", true)
    }): ToolExecutionResult {
        val args = JSONObject().put("tool_title", "sandbox test").put("code", code).toString()
        return runBlocking { ExecuteCodeTool.execute(args) { n, a -> invoke(n, a) } }
    }

    @Test
    fun javaIoIsNotReachable() {
        val victim = File(System.getProperty("java.io.tmpdir"), "minis-exec-code-escape-${System.nanoTime()}.txt")
        val r = run(
            """
            try {
              var f = new java.io.FileOutputStream('${victim.absolutePath}');
              f.write(new java.lang.String('pwned').getBytes());
              f.close();
              print('ESCAPED');
            } catch (e) { print('blocked'); }
            """.trimIndent(),
        )
        assertTrue(r.success)
        assertFalse("script reached java.io and wrote a file", victim.exists())
        assertFalse(r.output.contains("ESCAPED"))
    }

    @Test
    fun runtimeExecIsNotReachable() {
        val r = run(
            """
            try { var rt = java.lang.Runtime.getRuntime(); print('ESCAPED ' + rt); }
            catch (e) { print('blocked'); }
            """.trimIndent(),
        )
        assertTrue(r.success)
        assertFalse(r.output.contains("ESCAPED"))
    }

    @Test
    fun javaAndPackagesAreUndefined() {
        val r = run("print(typeof java + ',' + typeof Packages + ',' + typeof getClass);")
        assertTrue(r.success)
        assertTrue("expected all interop globals undefined, got: ${r.output}", r.output.contains("undefined,undefined,undefined"))
    }

    @Test
    fun readOnlyOrchestrationStillWorks() {
        val calls = mutableListOf<Pair<String, String>>()
        val r = run(
            """
            var s = JSON.stringify({a: 1, b: [2, 3]});
            print(s);
            print([1, 2, 3].map(function (v) { return v * 2; }).join('-'));
            print(file_read({path: '/tmp/x'}));
            print(web_search('q'));
            """.trimIndent(),
        ) { n, a ->
            calls += n to a
            ToolExecutionResult("content-of-$n", true)
        }
        assertTrue("orchestration broke under the safe scope: ${r.output}", r.success)
        assertTrue(r.output.contains("""{"a":1,"b":[2,3]}"""))
        assertTrue(r.output.contains("2-4-6"))
        assertTrue(r.output.contains("content-of-file_read"))
        assertTrue(r.output.contains("content-of-web_search"))
        // String shorthand still builds the right argument object.
        assertTrue(calls.contains("web_search" to """{"query":"q"}"""))
    }

    @Test
    fun sandboxToolAllowListStillEnforced() {
        val r = run("print(typeof file_write + ',' + typeof shell_execute);")
        assertTrue(r.success)
        assertTrue("non-allow-listed names leaked into the scope: ${r.output}", r.output.contains("undefined,undefined"))
    }
}
