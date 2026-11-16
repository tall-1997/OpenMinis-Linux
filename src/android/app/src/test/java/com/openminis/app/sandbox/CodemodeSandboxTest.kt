package com.openminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [pi-port §2.7] CodemodeSandbox contract on the JVM (Rhino 1.7.14 is pure
 * Java): arithmetic/return semantics, the tools host bridge, the
 * return-value-only context contract, the interrupt triple, and the SEC-5
 * class shutter.
 */
class CodemodeSandboxTest {

    /** Fake host: records calls, answers canned strings (executor convention). */
    private class FakeHost(
        private val answers: Map<String, String> = emptyMap(),
        private val allowed: Set<String> = setOf("file_read", "list_dir", "web_search"),
    ) : CodemodeSandbox.ToolHost {
        val calls = mutableListOf<Pair<String, String>>()

        override fun callableToolNames(): Set<String> = allowed

        override fun callTool(name: String, argsJson: String): String {
            calls += name to argsJson
            return answers[name] ?: "ERROR: no fake answer for $name"
        }
    }

    private fun run(script: String, host: CodemodeSandbox.ToolHost = FakeHost(), limits: CodemodeSandbox.Limits = CodemodeSandbox.Limits()) =
        CodemodeSandbox(host, limits).run(script)

    // ── evaluation & return serialization ──

    @Test
    fun arithmeticAndTopLevelReturnWork() {
        val r = run("return 6 * 7;")
        assertNull(r.error)
        assertEquals("42", r.valueJson)
        assertTrue(r.durationMs >= 0)
    }

    @Test
    fun stringReturnPassesThroughRawWithoutJsonQuotes() {
        assertEquals("hello world", run("return 'hello world';").valueJson)
    }

    @Test
    fun objectReturnJsonSerializes() {
        assertEquals("""{"a":1,"b":"x"}""", run("return {a: 1, b: 'x'};").valueJson)
    }

    @Test
    fun undefinedReturnHasNoValue() {
        val r = run("var x = 1;")
        assertNull(r.error)
        assertNull(r.valueJson)
    }

    @Test
    fun jsonGlobalsAreAvailable() {
        assertEquals(
            "5",
            run("return JSON.parse('{\"k\": 5}').k;").valueJson,
        )
        assertEquals(
            """{"z":1}""",
            run("return JSON.stringify({z: 1});").valueJson,
        )
    }

    @Test
    fun nonSerializableReturnIsAnHonestError() {
        val r = run("return function() {};")
        assertNotNull(r.error)
        assertTrue(r.error!!.contains("not JSON-serializable"))
        assertNull(r.valueJson)
    }

    // ── the tools host bridge ──

    @Test
    fun toolsBridgeCallsTheHostWithJsonArgs() {
        val host = FakeHost(answers = mapOf("file_read" to "line1\nline2"))
        val r = run("return tools.file_read({path: '/a'});", host)
        assertNull(r.error)
        assertEquals("line1\nline2", r.valueJson)
        assertEquals(1, host.calls.size)
        assertEquals("file_read", host.calls[0].first)
        // The object argument crossed as JSON.
        assertEquals("""{"path":"/a"}""", host.calls[0].second)
        assertEquals(1, r.toolCalls)
    }

    @Test
    fun stringArgumentPassesThroughVerbatim() {
        val host = FakeHost(answers = mapOf("web_search" to "results"))
        run("return tools.web_search('{\"query\": \"kotlin\"}');", host)
        assertEquals("""{"query": "kotlin"}""", host.calls[0].second)
    }

    @Test
    fun disallowedToolIsNotRegisteredOnTheBridge() {
        val host = FakeHost(allowed = setOf("file_read"))
        assertEquals("undefined", run("return typeof tools.web_search;", host).valueJson)
        // A registered name still resolves — a bridge entry IS a function, so
        // JS typeof answers "function" (the exclusion proof is the
        // "undefined" above).
        assertEquals("function", run("return typeof tools.file_read;", host).valueJson)
    }

    @Test
    fun failedToolAnswerFollowsTheErrorConvention() {
        val host = FakeHost() // no answers -> "ERROR: ..." strings
        val r = run("return tools.list_dir({path: '/x'});", host)
        assertTrue(r.valueJson!!.startsWith("ERROR:"))
    }

    // ── return-value-only context contract ──

    @Test
    fun thereIsNoOutputChannelBesidesTheReturnValue() {
        // pi: the script's ONLY model-facing surface is its return value. No
        // print/console bridge exists to append anything to the context.
        assertEquals("undefined", run("return typeof print;").valueJson)
        assertEquals("undefined", run("return typeof console;").valueJson)
    }

    @Test
    fun twoToolCallsStayInsideTheScript() {
        val host = FakeHost(
            answers = mapOf(
                "file_read" to "TOOL_OUTPUT_BODY",
                "list_dir" to "DIR_OUTPUT_BODY",
            ),
        )
        val script = """
            var a = tools.file_read({path: '/a'});
            var b = tools.list_dir({path: '/dir'});
            return a.length + b.length;
        """.trimIndent()
        val r = run(script, host)
        assertEquals(2, host.calls.size)
        assertNull(r.error)
        // Only the script's computed return reaches the result — neither tool
        // output string appears anywhere in it.
        assertFalse(r.valueJson!!.contains("TOOL_OUTPUT_BODY"))
        assertFalse(r.valueJson!!.contains("DIR_OUTPUT_BODY"))
    }

    // ── SEC-5: no Java escape hatch ──

    @Test
    fun javaRuntimeIsNotVisible() {
        val r = run("return java.lang.Runtime.getRuntime();")
        assertNotNull(r.error)
    }

    @Test
    fun packagesReflectionIsNotVisible() {
        assertNotNull(run("return Packages.java.io.File('/etc/passwd').exists();").error)
        // importClass exists as a global function, but its CLASS argument can
        // never resolve through the shutter — the call fails honestly.
        assertNotNull(run("importClass(java.lang.Runtime); return 'escaped';").error)
    }

    // ── return-value cap: surrogate-pair boundary (review P2-12) ──

    @Test
    fun returnValueCapNeverSplitsASurrogatePair() {
        // The cap is a UTF-16 code-unit budget; a bare take() cut the emoji
        // pair in half (lone high surrogate in the model-facing string).
        // sliceHead drops the whole pair at the boundary instead.
        val r = run(
            "return 'a😀b';",
            limits = CodemodeSandbox.Limits(maxReturnJsonChars = 2),
        )
        assertEquals("a", r.valueJson)
        assertFalse("no orphaned surrogate may reach the result", r.valueJson!!.any { it.isHighSurrogate() })
    }

    @Test
    fun returnValueCapKeepsTheWholePairWhenItFits() {
        val r = run(
            "return 'a😀b';",
            limits = CodemodeSandbox.Limits(maxReturnJsonChars = 3),
        )
        assertEquals("a😀", r.valueJson)
    }

    @Test
    fun returnValueCapOnObjectReturnAlsoSlicesAtCodeUnits() {
        // Same path for JSON-serialized returns (a nested string carrying an
        // emoji at the cap boundary).
        val r = run(
            "return {x: '😀😀'};",
            limits = CodemodeSandbox.Limits(maxReturnJsonChars = 10),
        )
        // {"x":"😀😀"} is 12 code units (6 ASCII + 2 pairs); sliceHead(10)
        // cuts at unit 10 — the pair boundary after the second emoji — so no
        // orphaned half exists and both pairs survive; the closing quote is
        // what gets dropped.
        assertEquals("""{"x":"😀😀""", r.valueJson)
        // No orphaned half: every high surrogate is followed by its low half.
        assertTrue(
            r.valueJson!!.indices.none { i ->
                r.valueJson[i].isHighSurrogate() &&
                    (i + 1 >= r.valueJson.length || !r.valueJson[i + 1].isLowSurrogate())
            },
        )
    }

    // ── interruption: wall clock + instruction budget + call cap ──

    @Test
    fun wallClockDeadlineInterruptsAComputeOnlyLoop() {
        val r = run(
            "var x = 0; while (true) { x++; }",
            limits = CodemodeSandbox.Limits(
                timeoutMs = 150,
                // Disable the instruction budget: on a fast host 20M interpreted
                // instructions can exhaust BEFORE the 150ms wall clock, and the
                // budget's honest message ("instruction budget") is not this
                // test's subject (its mechanism is pinned by
                // instructionBudgetInterruptsBeforeTheDeadline). One trip-wire
                // per test keeps the mechanism race out of the assertions.
                maxInstructions = Long.MAX_VALUE,
            ),
        )
        assertTrue(r.timedOut)
        assertFalse("a clock trip is not a budget trip", r.limitExceeded)
        assertNotNull(r.error)
        assertTrue(r.error!!.contains("timed out"))
        assertNull(r.valueJson)
    }

    @Test
    fun instructionBudgetInterruptsBeforeTheDeadline() {
        val r = run(
            "var x = 0; while (true) { x++; }",
            limits = CodemodeSandbox.Limits(
                timeoutMs = 60_000,
                instructionObserverThreshold = 10_000,
                maxInstructions = 50_000,
            ),
        )
        assertTrue(r.timedOut)
        assertFalse(r.limitExceeded)
        assertTrue(r.error!!.contains("instruction budget"))
    }

    @Test
    fun toolCallCapInterruptsALoopOfCalls() {
        val host = FakeHost(answers = mapOf("file_read" to "ok"))
        val r = run(
            "for (var i = 0; i < 100; i++) { tools.file_read({path: '/a'}); } return i;",
            host,
            CodemodeSandbox.Limits(maxToolCalls = 3, timeoutMs = 30_000),
        )
        assertNotNull(r.error)
        assertTrue(r.error!!.contains("tool-call cap"))
        assertEquals(3, host.calls.size)
        // review P2-13: the cap is a BUDGET trip, not a clock trip —
        // timedOut must stay false so the UI renders a plain failure.
        assertFalse(r.timedOut)
        assertTrue(r.limitExceeded)
    }

    // ── honest failures ──

    @Test
    fun syntaxErrorIsReportedHonestlyWithPosition() {
        val r = run("var var var;")
        assertNotNull(r.error)
        assertTrue(r.error!!.contains("codemode failed"))
        assertTrue(r.error!!.contains("codemode:"))
        assertFalse(r.timedOut)
    }

    @Test
    fun scriptThrowIsReportedHonestly() {
        val r = run("throw new Error('boom inside');")
        assertNotNull(r.error)
        assertTrue(r.error!!.contains("boom inside"))
    }
}
