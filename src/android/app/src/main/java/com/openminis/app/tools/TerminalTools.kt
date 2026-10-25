package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.sandbox.MinisTerminals
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-android-agent-terminal] Agent-facing interactive PTY terminal tool over
 * [MinisTerminals] — the deepseek-harness terminal_open/send/read/signal/close
 * surface. For programs that one-shot shell execution cannot drive: TUI apps,
 * REPLs, installers that ask y/N, password prompts, long-running servers.
 *
 * Output reads are bounded ([MinisTerminals.READ_WAIT_CAP_MS]): read returns
 * what arrived during the wait plus a hint to read again, instead of hanging
 * until the program finishes — the agent polls deliberately, never tightly.
 *
 * The tool layer owns nothing but argument parsing and JSON shaping; all
 * state, caps and teardown live in [MinisTerminals].
 */
object TerminalTools {

    const val NAME = "agent_terminal"

    private val ACTIONS = listOf("open", "send", "read", "signal", "close", "list")
    private const val TITLE = "agent_terminal"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description =
            "Interactive PTY terminal inside the session sandbox. Use ONLY for programs that need a real " +
                "terminal and cannot be run with shell_execute: full-screen TUI apps (vim, htop, less), " +
                "REPLs, installers with y/N prompts, password prompts, servers you want to watch. " +
                "Actions: open (start a terminal; optional command typed once the shell is up, otherwise " +
                "a bare login shell), send (type text, press_enter controls the carriage return), " +
                "read (collect NEW output since the last read; waits at most wait_ms, then returns what it " +
                "has plus a hint — call read again instead of polling in a tight loop), signal (ctrl_c " +
                "interrupt, ctrl_d EOF, ctrl_z suspend), close (kill the terminal's whole process tree), " +
                "list (terminals of this session with status). At most 3 live terminals per session; the " +
                "least recently used one is evicted beyond that. While a full-screen TUI app owns the " +
                "screen, read returns the visible screen snapshot rather than the scroll stream. " +
                "Prefer shell_execute for every one-shot command; open a terminal only for genuinely " +
                "interactive work, and close it when done.",
        parameters = mapOf(
            "action" to AgentToolParam(
                "string",
                "One of: open, send, read, signal, close, list.",
                enumValues = ACTIONS,
            ),
            "terminal_id" to AgentToolParam(
                "string",
                "Terminal id from open/list (send, read, signal, close).",
            ),
            "command" to AgentToolParam(
                "string",
                "First command typed into the shell after boot (open, optional). Omit for a bare shell.",
            ),
            "cols" to AgentToolParam("integer", "Terminal width, default 80 (open, optional)."),
            "rows" to AgentToolParam("integer", "Terminal height, default 24 (open, optional)."),
            "text" to AgentToolParam(
                "string",
                "Text to type into the terminal; a carriage return is appended when press_enter is true (send).",
            ),
            "press_enter" to AgentToolParam(
                "boolean",
                "Append a carriage return after text, default true (send, optional).",
            ),
            "signal" to AgentToolParam(
                "string",
                "ctrl_c (interrupt), ctrl_d (EOF), or ctrl_z (suspend) (signal).",
                enumValues = listOf("ctrl_c", "ctrl_d", "ctrl_z"),
            ),
            "wait_ms" to AgentToolParam(
                "integer",
                "How long read waits for output before returning, default 500, max 15000 (read, optional).",
            ),
        ),
        required = listOf("action"),
        propertyOrdering = listOf(
            "action", "terminal_id", "command", "cols", "rows",
            "text", "press_enter", "signal", "wait_ms",
        ),
    )

    /** Production entry point (dispatched from ChatViewModelExecuteToolExt). */
    suspend fun execute(argsJson: String, sessionId: String, context: Context?): ToolExecutionResult =
        withContext(Dispatchers.IO) { route(argsJson, sessionId, context) }

    /** Internal routing seam — nullable context so JVM tests can drive it. */
    internal suspend fun route(argsJson: String, sessionId: String, context: Context?): ToolExecutionResult {
        val args = try {
            JSONObject(argsJson)
        } catch (_: Exception) {
            return error("invalid JSON arguments", ToolErrorCode.INVALID_ARGUMENTS)
        }
        return when (args.optString("action").trim().lowercase()) {
            "open" -> openAction(args, sessionId, context)
            "send" -> sendAction(args, sessionId)
            "read" -> readAction(args, sessionId)
            "signal" -> signalAction(args, sessionId)
            "close" -> closeAction(args, sessionId)
            "list" -> listAction(sessionId)
            else -> error("action must be one of ${ACTIONS.joinToString(", ")}", ToolErrorCode.INVALID_ARGUMENTS)
        }
    }

    private fun openAction(args: JSONObject, sessionId: String, context: Context?): ToolExecutionResult {
        val command = args.optString("command").trim().ifBlank { null }
        val cols = args.optInt("cols", 80)
        val rows = args.optInt("rows", 24)
        val out = runBlocking { MinisTerminals.open(sessionId, context, command, cols, rows) }
        if (out.error != null) return error(out.error, ToolErrorCode.EXECUTION_FAILED)
        val json = JSONObject().apply {
            put("ok", true)
            put("terminal_id", out.terminalId)
            put("status", out.status.name.lowercase())
            put("cols", cols.coerceIn(20, 400))
            put("rows", rows.coerceIn(10, 200))
            if (out.evicted != null) put("evicted", out.evicted)
            put(
                "note",
                if (command != null) {
                    "command queued; terminal boot takes a moment, call terminal read to collect output"
                } else {
                    "call terminal read to collect output"
                },
            )
        }
        return ToolExecutionResult(json.toString(), true, toolTitle = TITLE)
    }

    private fun sendAction(args: JSONObject, sessionId: String): ToolExecutionResult {
        val id = args.optString("terminal_id").trim()
        if (id.isEmpty()) return error("terminal_id is required for send", ToolErrorCode.INVALID_ARGUMENTS)
        val text = args.optString("text")
        if (text.isEmpty() && !args.has("press_enter")) {
            return error("text is required for send (or set press_enter to resend a bare carriage return)", ToolErrorCode.INVALID_ARGUMENTS)
        }
        val pressEnter = args.optBoolean("press_enter", true)
        val out = runBlocking { MinisTerminals.send(sessionId, id, text, pressEnter) }
        return if (out.ok) {
            ToolExecutionResult(
                JSONObject()
                    .put("ok", true)
                    .put("terminal_id", id)
                    .put("note", "use terminal read to collect output")
                    .toString(),
                true,
                toolTitle = TITLE,
            )
        } else {
            error(out.error ?: "send failed", ToolErrorCode.EXECUTION_FAILED)
        }
    }

    private fun readAction(args: JSONObject, sessionId: String): ToolExecutionResult {
        val id = args.optString("terminal_id").trim()
        if (id.isEmpty()) return error("terminal_id is required for read", ToolErrorCode.INVALID_ARGUMENTS)
        val waitMs = if (args.has("wait_ms")) args.optLong("wait_ms", 500L) else 500L
        val out = runBlocking { MinisTerminals.read(sessionId, id, waitMs) }
        if (out.error != null) return error(out.error, ToolErrorCode.NOT_FOUND)
        val status = out.status?.name?.lowercase() ?: "unknown"
        val note = when {
            out.status == MinisTerminals.TState.EXITED ->
                "process exited; this is the final output"
            out.status == MinisTerminals.TState.BOOTING ->
                "terminal still booting; call terminal read again shortly"
            out.output.isEmpty() ->
                "no new output within ${waitMs}ms; the program may be waiting for input " +
                    "(use terminal send or terminal signal), or call terminal read again"
            out.moreExpected ->
                "output may still be arriving; call terminal read again if more is expected"
            else -> ""
        }
        val json = JSONObject().apply {
            put("ok", true)
            put("terminal_id", id)
            put("status", status)
            put("new_output", out.output)
            if (note.isNotEmpty()) put("note", note)
        }
        return ToolExecutionResult(json.toString(), true, toolTitle = TITLE)
    }

    private fun signalAction(args: JSONObject, sessionId: String): ToolExecutionResult {
        val id = args.optString("terminal_id").trim()
        if (id.isEmpty()) return error("terminal_id is required for signal", ToolErrorCode.INVALID_ARGUMENTS)
        val signal = args.optString("signal").trim()
        if (signal.isEmpty()) return error("signal is required for signal (ctrl_c, ctrl_d, ctrl_z)", ToolErrorCode.INVALID_ARGUMENTS)
        val out = runBlocking { MinisTerminals.signal(sessionId, id, signal) }
        return if (out.ok) {
            ToolExecutionResult(
                JSONObject().put("ok", true).put("terminal_id", id).put("signal", signal).toString(),
                true,
                toolTitle = TITLE,
            )
        } else {
            error(out.error ?: "signal failed", ToolErrorCode.EXECUTION_FAILED)
        }
    }

    private fun closeAction(args: JSONObject, sessionId: String): ToolExecutionResult {
        val id = args.optString("terminal_id").trim()
        if (id.isEmpty()) return error("terminal_id is required for close", ToolErrorCode.INVALID_ARGUMENTS)
        val out = runBlocking { MinisTerminals.close(sessionId, id) }
        return if (out.ok) {
            ToolExecutionResult(
                JSONObject().put("ok", true).put("closed", id).toString(),
                true,
                toolTitle = TITLE,
            )
        } else {
            error(out.error ?: "close failed", ToolErrorCode.NOT_FOUND)
        }
    }

    private fun listAction(sessionId: String): ToolExecutionResult {
        val terminals = runBlocking { MinisTerminals.list(sessionId) }
        val arr = JSONArray()
        for (t in terminals) {
            arr.put(
                JSONObject().apply {
                    put("id", t.id)
                    put("status", t.status.name.lowercase())
                    put("cols", t.cols)
                    put("rows", t.rows)
                    put("created_at_ms", t.createdAtMs)
                    put("last_activity_ms", t.lastActivityMs)
                    put("idle_ms", System.currentTimeMillis() - t.lastActivityMs)
                },
            )
        }
        val json = JSONObject().apply {
            put("ok", true)
            put("count", terminals.size)
            put("terminals", arr)
        }
        return ToolExecutionResult(json.toString(), true, toolTitle = TITLE)
    }

    private fun error(message: String, code: ToolErrorCode): ToolExecutionResult =
        ToolExecutionResult("Error: $message", false, code, toolTitle = TITLE)
}
