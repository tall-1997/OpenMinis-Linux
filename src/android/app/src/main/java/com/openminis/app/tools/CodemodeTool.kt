package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.sandbox.CodemodeSandbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The codemode entry tool (pi packages/codemode): run several tool calls
 * inside ONE JavaScript (ES5) program and return only its final value.
 *
 * Context economy is the point: nested `tools.<name>(...)` results come back
 * into the script as strings and NEVER enter the conversation. What the
 * model sees is this tool's own toolResult — the serialized script return value,
 * truncated through [ToolOutputTruncator] like every other tool output. A
 * multi-step aggregation (read 10 files, keep 3 lines) costs one result slot
 * instead of ten.
 *
 * Exposure: [ToolExposureGate.DIRECT] — declared in the schema like any other
 * tool (this is pi's model-visible codemode entry). Deliberately NOT in the
 * codemode-callable set: a script re-entering this tool parks one
 * Dispatchers.IO thread per nesting level in the bridge's runBlocking and
 * ~64 levels deadlock the IO pool, and Stop cannot reach the parked threads.
 * [ToolExposureGate.callableForCodemode] excludes the name (first line of
 * defense) and [NestingGuard] refuses any re-entry that reaches the bridge
 * anyway (defense in depth) with the honest self-rescue error, so the model
 * can correct itself by inlining the logic.
 *
 * Permission pipeline: every nested call goes through the [invoke] function
 * the dispatcher injects — the SAME ChatViewModel.executeTool path a model
 * tool-call takes (SecurityGate intercept, guards, evolution hooks, output
 * policy). The sandbox never executes a tool by itself.
 *
 * Callability: the script may call every tool the Gate marks callable
 * (registered, minus model_only/hidden minus this entry). The nested executor
 * remains the real boundary: a gated-off tool (memory disabled, unknown name,
 * permission refused) answers with its own honest error inside the script.
 */
object CodemodeTool {

    const val NAME = "execute_code_js"

    /** Declared level: pi's codemode entry is a normal model-visible tool. */
    const val EXPOSURE = ToolExposureGate.DIRECT

    private const val DEFAULT_TIMEOUT_S = 60L
    private const val MAX_TIMEOUT_S = 300L

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Run MULTIPLE tool calls inside one JavaScript (ES5) program and return only its final value. " +
            "Use for multi-step aggregation tasks — e.g. read 10 files and keep 3 matching lines, or fetch several " +
            "pages and combine one field: the intermediate tool results stay OUT of the conversation, so the only " +
            "cost is this call's return value. Call session tools as tools.<name>(args) with a JSON object argument " +
            "(tools.file_read({\"path\": \"/var/minis/workspace/a.txt\"})); a failed call answers \"ERROR: ...\" and " +
            "the script decides what to do. The script MUST `return` its final value (top-level return works); " +
            "JSON.parse/JSON.stringify and standard globals are available. No print/console — there is no output " +
            "channel besides the return value. Compared to execute_code: that sibling streams print() output into " +
            "the conversation for quick scratch work over a fixed read-only function set; use THIS tool when the " +
            "intermediates must stay out of the conversation. JavaScript only; for shell work use shell_execute.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user."),
            "code" to AgentToolParam(
                "string",
                "JavaScript (ES5) source. Body of an implicit function — use `return value;` for the result. " +
                    "Call tools via tools.<name>({...}); each call returns the tool's output string.",
            ),
            "timeout_seconds" to AgentToolParam(
                "integer",
                "Wall-clock cap for the script, 1-300 (default $DEFAULT_TIMEOUT_S).",
            ),
        ),
        required = listOf("tool_title", "code"),
        propertyOrdering = listOf("tool_title", "code", "timeout_seconds"),
        exposure = EXPOSURE,
    )

    /**
     * Dispatch entry. [invoke] is the SecurityGate-routed executor (the same
     * shape tools.ExecuteCodeTool takes from the dispatcher). [callableNames]
     * narrows what `tools.*` resolves to; null defaults to the Gate's
     * codemode-callable set over the full registry — the executor itself
     * stays the authority (an unknown/gated name answers its own error).
     */
    suspend fun execute(
        argsJson: String,
        invoke: suspend (name: String, argsJson: String) -> ToolExecutionResult,
        callableNames: Set<String>? = null,
    ): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrNull() ?: JSONObject()
        val toolTitle = args.optString("tool_title", NAME).ifBlank { NAME }
        val code = args.optString("code").ifBlank { args.optString("script") }
        if (code.isBlank()) {
            return ToolExecutionResult("code required", false, toolTitle = toolTitle)
        }
        val timeoutSeconds = args.optLong("timeout_seconds", DEFAULT_TIMEOUT_S)
            .coerceIn(1L, MAX_TIMEOUT_S)
        val sandbox = CodemodeSandbox(
            host = ExecutorBridge(invoke, callableNames ?: defaultCallableNames()),
            limits = CodemodeSandbox.Limits(timeoutMs = timeoutSeconds * 1000),
        )
        return withContext(Dispatchers.IO) {
            // The sandbox runs synchronously on THIS IO thread, so marking it
            // here is what lets the bridge below recognize a script thread.
            NestingGuard.enter()
            try {
                val run = sandbox.run(code)
                // Local copies so the when-chain smart-casts are unconditional.
                val error = run.error
                val valueJson = run.valueJson
                when {
                    error != null -> ToolExecutionResult(
                        error,
                        false,
                        errorCode = when {
                            // The tool-call cap is a BUDGET trip, not a clock
                            // trip: a plain failure (no UI timeout clock, no
                            // "return partial results" timeout hint).
                            run.limitExceeded -> ToolErrorCode.CODEMODE_LIMIT
                            run.timedOut -> ToolErrorCode.TIMEOUT
                            else -> ToolErrorCode.EXECUTION_FAILED
                        },
                        recoveryHint = if (run.timedOut) {
                            "Return partial results instead of looping; split the work across several calls."
                        } else null,
                        toolTitle = toolTitle,
                        // TIMEOUT must ride with timedOut=true (SshTool convention):
                        // the UI renders the TIMEOUT status from this flag
                        // (ChatViewModelToolTurnExec result.timedOut), not from the code.
                        timedOut = run.timedOut,
                    )
                    valueJson == null -> ToolExecutionResult(
                        "(no return value after ${run.toolCalls} tool calls) — a codemode script must `return` its result.",
                        true,
                        toolTitle = toolTitle,
                    )
                    else -> ToolExecutionResult(
                        ToolOutputTruncator.truncateHead(valueJson).content,
                        true,
                        toolTitle = toolTitle,
                    )
                }
            } finally {
                NestingGuard.exit()
            }
        }
    }

    /**
     * The Gate's codemode-callable set over the full registry (pi: callable =
     * registered minus model_only/hidden, regardless of activation — and the
     * Gate also drops this entry tool itself, see its KDoc). Built with the
     * SAME flags as [ToolExposureGate.levelByName]: callability is a
     * registry-wide contract and must not depend on which device features
     * happen to be configured — the previous default (vision/speech flags
     * off) silently dropped read_image / transcribe_audio from the bridge, so
     * a script saw a JS TypeError instead of the executor's honest refusal.
     * The nested executor stays the real boundary — a session-gated tool
     * answers its own refusal inside the script.
     */
    private fun defaultCallableNames(): Set<String> = ToolExposureGate.callableNamesForCodemode(
        AgentTools.makeAgentTools(
            supportsImageInput = true,
            visionGroupConfigured = true,
            memoryEnabled = true,
            subAgentEnabled = true,
            codeGraphEnabled = true,
            mcpNativeEnabled = true,
            hasSpeechModel = true,
            includeLongTail = true,
        ),
    )

    /**
     * Defense-in-depth against codemode self-recursion (P1 review): how many
     * sandbox frames are running on THIS thread. The Rhino interpreter runs
     * synchronously on the thread that called [CodemodeSandbox.run], and the
     * bridge's runBlocking parks that very thread per nested tool call — so a
     * script thread starting another codemode frame is the exact shape that
     * parks one IO thread per level and deadlocks the pool at ~64 levels
     * (Stop cannot reach parked threads). [ToolExposureGate.callableForCodemode]
     * already excludes the entry tool from the default bridge; this guard
     * covers paths that bypass that set (an explicit callableNames naming the
     * entry, a future caller). Internal for the guard tests.
     */
    internal object NestingGuard {
        private val depth = ThreadLocal.withInitial { 0 }

        fun enter() {
            depth.set(depth.get() + 1)
        }

        fun exit() {
            val next = depth.get() - 1
            if (next <= 0) {
                // Frame stack empty again: drop the ThreadLocal mapping instead
                // of parking a boxed 0/underflow value in it (P3 hygiene). The
                // guard runs on Dispatchers.IO worker threads, which the pool
                // recycles for the app's lifetime — without remove() every
                // thread that ever ran a codemode script carries the entry
                // forever. Correctness is unchanged: get() after remove()
                // re-initializes to 0 via withInitial, and the next enter()
                // counts from 0 again. An unbalanced exit (caller bug) also
                // lands here and resets to the clean baseline instead of
                // leaving a negative depth that would swallow a later enter.
                depth.remove()
            } else {
                depth.set(next)
            }
        }

        /** True while this thread executes inside a codemode sandbox frame. */
        fun insideScript(): Boolean = depth.get() > 0
    }

    /**
     * The honest refusal the bridge returns for a nested codemode attempt.
     * Model-actionable on purpose: the self-rescue is to inline the logic.
     */
    internal fun nestingRefusal(): String =
        "ERROR: codemode nesting is not supported — inline the logic in one script"

    /**
     * [CodemodeSandbox.ToolHost] backed by the injected executor. Blocks the
     * Rhino thread per call (the sandbox runs on Dispatchers.IO, the same
     * pattern tools.ExecuteCodeTool uses for its bridge) — EXCEPT for a
     * re-entrant codemode attempt, which is refused without parking anything
     * (see [NestingGuard]).
     */
    internal class ExecutorBridge(
        private val invoke: suspend (name: String, argsJson: String) -> ToolExecutionResult,
        private val allowed: Set<String>,
    ) : CodemodeSandbox.ToolHost {

        override fun callableToolNames(): Set<String> = allowed

        override fun callTool(name: String, argsJson: String): String {
            // This thread is inside a sandbox frame, so a call to the ENTRY
            // TOOL would start a SECOND codemode frame — the shape that parks
            // one IO thread per level in runBlocking and deadlocks the pool.
            // Refuse it honestly (the self-rescue is to inline the logic);
            // ordinary nested tool calls are unaffected.
            if (name == NAME && NestingGuard.insideScript()) return nestingRefusal()
            return runBlocking {
                val result = invoke(name, argsJson)
                if (result.success) result.output else "ERROR: ${result.output}"
            }
        }
    }
}
