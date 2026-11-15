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
 * into the script as strings and NEVER enter the conversation. What the model
 * sees is this tool's own toolResult — the serialized script return value,
 * truncated through [ToolOutputTruncator] like every other tool output. A
 * multi-step aggregation (read 10 files, keep 3 lines) costs one result slot
 * instead of ten.
 *
 * Exposure: [ToolExposureGate.DIRECT] — declared in the schema like any other
 * tool (this is pi's model-visible codemode entry). [ToolExposureGate]
 * additionally counts it in the codemode-callable set, so a codemode script
 * can bootstrap another aggregation — the pi "codemode tools list it" rule.
 *
 * Permission pipeline: every nested call goes through the [invoke] function
 * the dispatcher injects — the SAME ChatViewModel.executeTool path a model
 * tool-call takes (SecurityGate intercept, guards, evolution hooks, output
 * policy). The sandbox never executes a tool by itself.
 *
 * Callability: the script may call every tool the Gate marks callable
 * (registered, minus model_only/hidden). The nested executor remains the
 * real boundary: a gated-off tool (memory disabled, unknown name, permission
 * refused) answers with its own honest error inside the script.
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
            "channel besides the return value. JavaScript only; for shell work use shell_execute.",
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
            val run = sandbox.run(code)
            // Local copies so the when-chain smart-casts are unconditional.
            val error = run.error
            val valueJson = run.valueJson
            when {
                error != null -> ToolExecutionResult(
                    error,
                    false,
                    errorCode = if (run.timedOut) ToolErrorCode.TIMEOUT else ToolErrorCode.EXECUTION_FAILED,
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
        }
    }

    /**
     * The Gate's codemode-callable set over the full registry (pi: callable =
     * registered minus model_only/hidden, regardless of activation). The
     * nested executor stays the real boundary — a session-gated tool answers
     * its own refusal inside the script.
     */
    private fun defaultCallableNames(): Set<String> = ToolExposureGate.callableNamesForCodemode(
        AgentTools.makeAgentTools(
            includeLongTail = true,
            subAgentEnabled = true,
            codeGraphEnabled = true,
            mcpNativeEnabled = true,
        ),
    )

    /**
     * [CodemodeSandbox.ToolHost] backed by the injected executor. Blocks the
     * Rhino thread per call (the sandbox runs on Dispatchers.IO, the same
     * pattern tools.ExecuteCodeTool uses for its bridge).
     */
    private class ExecutorBridge(
        private val invoke: suspend (name: String, argsJson: String) -> ToolExecutionResult,
        private val allowed: Set<String>,
    ) : CodemodeSandbox.ToolHost {

        override fun callableToolNames(): Set<String> = allowed

        override fun callTool(name: String, argsJson: String): String = runBlocking {
            val result = invoke(name, argsJson)
            if (result.success) result.output else "ERROR: ${result.output}"
        }
    }
}
