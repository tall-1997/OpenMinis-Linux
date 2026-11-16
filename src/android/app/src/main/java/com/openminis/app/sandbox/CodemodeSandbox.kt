package com.openminis.app.sandbox

import com.openminis.app.tools.ToolOutputTruncator
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.NativeJSON
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined

/**
 * Language-level light sandbox (pi packages/codemode, Rhino edition): a JS
 * (ES5) VM whose only capability is calling host tools through an injected
 * bridge, running for milliseconds instead of a PRoot boot.
 *
 * PERMISSION PIPELINE (load-bearing): the sandbox NEVER executes tools by
 * itself. The CALLER injects a [ToolHost] whose `callTool` is the
 * SecurityGate-routed tool executor (the same pipeline a model tool-call
 * goes through: SecurityGateHolder.intercept, guards, evolution hooks, output
 * policy). Every nested `tools.<name>(...)` crosses that pipeline — the
 * sandbox is a caller, not a bypass.
 *
 * CONTEXT ECONOMY (the pi point of the feature): tool results come back INTO
 * the script as strings and are NEVER appended to the conversation; the only
 * thing that reaches the model is the script's RETURN VALUE, serialized
 * ([RunResult.valueJson]). A multi-step aggregation (read 10 files, keep 3
 * lines) therefore costs one tool-result slot instead of ten.
 *
 * Interruption (pi's interrupt budget, Rhino flavor): the context runs in
 * interpreted mode ([Context.setOptimizationLevel] -1) with an instruction
 * observer; [ContextFactory.observeInstructionCount] accumulates the count
 * and throws [CodemodeTimeout] past the instruction budget or the wall-clock
 * deadline. That bounds compute-only infinite loops; a script stalled inside
 * a host tool call is bounded by that tool's own timeout. The per-run
 * tool-call cap trips the same exception with [CodemodeTimeout.reason] =
 * [InterruptReason.TOOL_CALL_CAP] — a BUDGET trip, not a clock trip, so
 * [RunResult.timedOut] stays false for it (review P2-13: the cap used to be
 * reported as a timeout, which rendered a false timeout clock in the UI).
 *
 * Memory: the JVM Rhino context has no heap-metering API, so the pi
 * `memoryLimitBytes` maps onto the limits here as INDIRECT caps — the
 * instruction budget bounds live-set growth during a run, and
 * [Limits.maxReturnJsonChars] bounds the value that exits. The constants are
 * declared so a future enforcement point (e.g. a Rhino fork with heap
 * metering) can read the same numbers.
 *
 * Hostile scripts: [ClassShutter] makes EVERY Java class invisible (same
 * SEC-5 posture as tools.ExecuteCodeTool) — no Packages, no reflection, no
 * runtime exec; the only host surface is the `tools` bridge object. There is
 * deliberately no print/console bridge: output would violate the
 * return-value-only contract above.
 */
class CodemodeSandbox(
    private val host: ToolHost,
    private val limits: Limits = Limits(),
) {

    /** The host bridge the script reaches through `tools.<name>(args)`. */
    interface ToolHost {
        /**
         * Names the script may call. The Gate's codemode-callable set
         * (ToolExposureGate.callableNamesForCodemode) is the declared policy;
         * callers MAY pass a wider set — the permission pipeline behind
         * [callTool] remains the boundary either way (an unknown name is
         * answered by the executor's own "Unknown tool" error).
         */
        fun callableToolNames(): Set<String>

        /**
         * Run ONE tool call through the full permission pipeline, blocking
         * the script thread until it settles. Returns the tool's output as a
         * string; failures follow the codebase's `ERROR: ...` convention so
         * the script can branch on them.
         */
        fun callTool(name: String, argsJson: String): String
    }

    /** Run limits. Defaults are the constants this port ships with. */
    data class Limits(
        /** Wall-clock cap for the whole script. */
        val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        /** Rhino instruction-observer poll interval (interpreted mode). */
        val instructionObserverThreshold: Int = DEFAULT_OBSERVER_THRESHOLD,
        /** Total interpreted instructions before the script is interrupted. */
        val maxInstructions: Long = DEFAULT_MAX_INSTRUCTIONS,
        /** Tool calls one script may make (pi's per-run call cap). */
        val maxToolCalls: Int = DEFAULT_MAX_TOOL_CALLS,
        /**
         * Chars allowed in the serialized return value (the indirect memory
         * cap on what exits the sandbox; the tool layer may truncate further).
         */
        val maxReturnJsonChars: Int = DEFAULT_MAX_RETURN_CHARS,
        /**
         * pi `memoryLimitBytes` analogue. JVM Rhino cannot meter the heap per
         * context, so this constant documents the intended ceiling; the
         * enforcement is indirect (see the class KDoc).
         */
        val memoryLimitBytes: Long = DEFAULT_MEMORY_LIMIT_BYTES,
    ) {
        companion object {
            const val DEFAULT_TIMEOUT_MS = 60_000L
            const val DEFAULT_OBSERVER_THRESHOLD = 100_000
            const val DEFAULT_MAX_INSTRUCTIONS = 20_000_000L
            const val DEFAULT_MAX_TOOL_CALLS = 200
            const val DEFAULT_MAX_RETURN_CHARS = 16 * 1024
            const val DEFAULT_MEMORY_LIMIT_BYTES = 64L * 1024 * 1024
        }
    }

    /** Which budget cut a script short. */
    enum class InterruptReason {
        /**
         * Wall-clock deadline or instruction budget (a TIME trip). Both trip
         * kinds report timedOut=true — a deliberately MERGED accounting: the
         * instruction budget bounds compute-only loops that would otherwise
         * burn the wall clock anyway, and the UI renders the timeout clock
         * from [RunResult.timedOut], not from which limit actually fired.
         */
        TIME_BUDGET,
        /** [Limits.maxToolCalls] exceeded (a BUDGET trip, not the clock). */
        TOOL_CALL_CAP,
    }

    /**
     * Thrown from the instruction observer or the tool bridge when a limit
     * trips mid-script. [reason] says which one — the timeout and the
     * tool-call cap must NOT be reported the same way downstream (P2-13).
     */
    class CodemodeTimeout(
        message: String,
        val reason: InterruptReason = InterruptReason.TIME_BUDGET,
    ) : RuntimeException(message)

    /** Outcome of one [run]. Exactly one of [valueJson]/[error] carries data. */
    data class RunResult(
        /** Serialized script return value; null when the script returned undefined. */
        val valueJson: String?,
        val toolCalls: Int,
        val durationMs: Long,
        /** True only for TIME_BUDGET interruptions (wall clock / instruction budget). */
        val timedOut: Boolean,
        /**
         * True only for TOOL_CALL_CAP interruptions — a budget trip the model
         * can act on (make fewer calls), rendered as a plain failure, never
         * with the timeout clock.
         */
        val limitExceeded: Boolean,
        /** Honest failure line (syntax error, script throw, limit tripped). */
        val error: String?,
    )

    /** One-shot run: enter a fresh context, evaluate, exit. Not reusable. */
    fun run(script: String): RunResult {
        require(script.isNotBlank()) { "script must not be blank" }
        val started = System.currentTimeMillis()
        val deadline = started + limits.timeoutMs
        val state = RunState()
        val factory = InterruptFactory(deadline, limits, state)
        val cx = factory.enterContext()
        try {
            // SEC-5: no Java class is visible to scripts; the only host
            // surface is the `tools` bridge built below.
            cx.setClassShutter(ClassShutter { false })
            val scope = cx.initStandardObjects()
            installToolsBridge(cx, scope, state)
            // Top-level `return` is the pi script shape; Rhino only allows it
            // inside a function, so the script runs in an implicit IIFE.
            val wrapped = "(function(){\n$script\n})()"
            val value = cx.evaluateString(scope, wrapped, "codemode", 1, null)
            // sliceHead, not take(): maxReturnJsonChars is a UTF-16 code-unit
            // budget, and a bare take() would cut a surrogate pair in half at
            // the boundary (review P2-12) — the shared slicer drops the
            // orphaned half instead.
            return RunResult(
                valueJson = serializeReturn(cx, scope, value)
                    ?.let { ToolOutputTruncator.sliceHead(it, limits.maxReturnJsonChars) },
                toolCalls = state.toolCalls,
                durationMs = System.currentTimeMillis() - started,
                timedOut = false,
                limitExceeded = false,
                error = null,
            )
        } catch (t: Throwable) {
            val durationMs = System.currentTimeMillis() - started
            return when (t) {
                is CodemodeTimeout -> RunResult(
                    valueJson = null,
                    toolCalls = state.toolCalls,
                    durationMs = durationMs,
                    timedOut = t.reason == InterruptReason.TIME_BUDGET,
                    limitExceeded = t.reason == InterruptReason.TOOL_CALL_CAP,
                    error = t.message,
                )
                else -> RunResult(
                    valueJson = null,
                    toolCalls = state.toolCalls,
                    durationMs = durationMs,
                    timedOut = false,
                    limitExceeded = false,
                    error = describeFailure(t),
                )
            }
        } finally {
            Context.exit()
        }
    }

    // ── internals ─────────────────────────────────────────────────────────────

    /** Per-run mutable state shared with the observer and the bridge. */
    private class RunState {
        var instructions: Long = 0
        var toolCalls: Int = 0
    }

    /**
     * The interrupt mechanism: interpreted mode + instruction observer. The
     * observer is the ONLY place the deadline is enforced while JS code runs
     * (Rhino polls it every [Limits.instructionObserverThreshold] instructions).
     */
    private class InterruptFactory(
        private val deadlineMs: Long,
        private val limits: Limits,
        private val state: RunState,
    ) : ContextFactory() {
        override fun makeContext(): Context {
            val cx = super.makeContext()
            // Interpreted mode: the instruction observer only fires here.
            cx.optimizationLevel = -1
            cx.instructionObserverThreshold = limits.instructionObserverThreshold
            return cx
        }

        override fun observeInstructionCount(cx: Context, count: Int) {
            state.instructions += count
            if (state.instructions > limits.maxInstructions) {
                throw CodemodeTimeout(
                    "codemode script exceeded the instruction budget " +
                        "(${state.instructions} > ${limits.maxInstructions}); keep scripts small or split them.",
                )
            }
            if (System.currentTimeMillis() > deadlineMs) {
                throw CodemodeTimeout(
                    "codemode script timed out after the ${limits.timeoutMs}ms wall-clock limit; " +
                        "return partial results instead of looping.",
                )
            }
        }
    }

    /** Builds the `tools` host object: one function per callable tool name. */
    private fun installToolsBridge(cx: Context, scope: Scriptable, state: RunState) {
        val tools = cx.newObject(scope)
        for (name in host.callableToolNames().sorted()) {
            ScriptableObject.putProperty(tools, name, toolFunction(name, state))
        }
        ScriptableObject.putProperty(scope, "tools", tools)
    }

    private fun toolFunction(name: String, state: RunState): BaseFunction = object : BaseFunction() {
        override fun call(
            cx: Context,
            scope: Scriptable,
            thisObj: Scriptable,
            args: Array<out Any>,
        ): Any? {
            if (++state.toolCalls > limits.maxToolCalls) {
                // BUDGET trip, not a clock trip: the reason field is what keeps
                // this from being misreported as a timeout downstream (P2-13).
                throw CodemodeTimeout(
                    "codemode script exceeded the ${limits.maxToolCalls}-tool-call cap.",
                    InterruptReason.TOOL_CALL_CAP,
                )
            }
            val argsJson = toArgsJson(cx, scope, args.getOrNull(0))
            return host.callTool(name, argsJson)
        }
    }

    /**
     * Script argument -> the JSON string the executor receives. An object (or
     * array) is JSON-stringified; a string passes through verbatim (the
     * script author may hand a pre-built JSON body); null/undefined is `{}`.
     */
    private fun toArgsJson(cx: Context, scope: Scriptable, arg: Any?): String = when (arg) {
        null, is Undefined -> "{}"
        is CharSequence -> arg.toString()
        else -> NativeJSON.stringify(cx, scope, arg, null, null)?.let {
            (it as? String) ?: Context.toString(arg)
        } ?: Context.toString(arg)
    }

    /**
     * Script return value -> model-facing text. Strings pass through raw
     * (JSON.stringify would only add quotes the model would echo back);
     * other values JSON-serialize; undefined has no representation and means
     * "no return value"; anything else is an honest, explicit failure (pi:
     * the return value must be JSON-serializable).
     */
    private fun serializeReturn(cx: Context, scope: Scriptable, value: Any?): String? = when (value) {
        null, is Undefined -> null
        is CharSequence -> value.toString()
        else -> (NativeJSON.stringify(cx, scope, value, null, null) as? String)
            ?: throw IllegalArgumentException(
                "return value is not JSON-serializable (${value.javaClass.simpleName}); " +
                    "return a JSON-compatible value (string, number, boolean, array, plain object)",
            )
    }

    /** Honest failure line, with script position when Rhino recorded one. */
    private fun describeFailure(t: Throwable): String {
        val detail = t.message ?: t.javaClass.simpleName
        return if (t is RhinoException) {
            // RhinoException exposes position via sourceName()/lineNumber()
            // METHODS (no get/is prefix, so Kotlin cannot read them as
            // properties — the backing fields are private).
            "codemode failed: ${t.javaClass.simpleName} at ${t.sourceName() ?: "script"}:${t.lineNumber()}: $detail"
        } else {
            "codemode failed: ${t.javaClass.simpleName}: $detail"
        }
    }
}
