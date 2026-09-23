package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.sandbox.PRootKernel
import org.json.JSONObject

/**
 * Workspace directory listing. Paths go through [PRootKernel], not host File.
 *
 * Adapted from XINCODE-Public ListDirTool (GPL-3.0-or-later).
 */
object ListDirTool {
    const val NAME = "list_dir"
    private const val MAX_ENTRIES = 400

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "List files and directories at a Linux path. Prefer this over shell ls — no PRoot round-trip.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user."),
            "path" to AgentToolParam("string", "Absolute Linux path (default /var/minis/workspace)."),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title", "path"),
    )

    fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
        val toolTitle = try { JSONObject(argsJson).optString("tool_title", NAME) } catch (_: Exception) { NAME }
        return try {
            val path = JSONObject(argsJson).optString("path", "/var/minis/workspace").ifBlank { "/var/minis/workspace" }
            val dir = PRootKernel.resolveSessionHostPath(sessionId, path, context)
                ?: return ToolExecutionResult("Error: Cannot resolve path: $path", false, toolTitle = toolTitle)
            if (!dir.exists()) return ToolExecutionResult("Error: not found: $path${guestNamespaceHint(path)}", false, toolTitle = toolTitle)
            if (!dir.isDirectory) return ToolExecutionResult("Error: not a directory: $path", false, toolTitle = toolTitle)
            val kids = dir.listFiles()?.sortedBy { it.name.lowercase() } ?: emptyList()
            val shown = kids.take(MAX_ENTRIES)
            val body = buildString {
                append("$path (${kids.size} entries)\n")
                for (f in shown) {
                    append(if (f.isDirectory) "d " else "f ")
                    append(f.name)
                    if (!f.isDirectory) append(" ${f.length()}")
                    append('\n')
                }
                if (kids.size > shown.size) append("… truncated\n")
            }
            ToolExecutionResult(body, true, toolTitle = toolTitle)
        } catch (e: Exception) {
            ToolExecutionResult("Error listing dir: ${e.message}", false, toolTitle = toolTitle)
        }
    }
}
