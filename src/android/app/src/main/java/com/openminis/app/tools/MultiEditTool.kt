package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.sandbox.PRootKernel
import org.json.JSONArray
import org.json.JSONObject

/**
 * Sequential unique replacements on one file.
 *
 * Adapted from XINCODE-Public MultiEditTool (GPL-3.0-or-later).
 */
object MultiEditTool {
    const val NAME = "multi_edit"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Apply several unique old_string→new_string replacements to one file in order. Each old_string must match exactly once unless replace_all is set on that edit.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user."),
            "path" to AgentToolParam("string", "Absolute Linux path."),
            "edits" to AgentToolParam(
                type = "array",
                description = "List of {old_string, new_string, replace_all?}.",
                items = AgentToolParam(
                    type = "object",
                    description = "One replacement",
                    properties = mapOf(
                        "old_string" to AgentToolParam("string", "Exact text to find"),
                        "new_string" to AgentToolParam("string", "Replacement"),
                        "replace_all" to AgentToolParam("boolean", "Replace every occurrence"),
                    ),
                    required = listOf("old_string", "new_string"),
                ),
            ),
        ),
        required = listOf("tool_title", "path", "edits"),
        propertyOrdering = listOf("tool_title", "path", "edits"),
    )

    fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
        val toolTitle = try { JSONObject(argsJson).optString("tool_title", NAME) } catch (_: Exception) { NAME }
        return try {
            val args = JSONObject(argsJson)
            val path = args.optString("path", "")
            if (path.isBlank()) return ToolExecutionResult("Error: path required", false, toolTitle = toolTitle)
            if (PRootKernel.isLinuxPathUnderReadOnlyMount(path)) {
                return ToolExecutionResult("Error: $path is read-only mounted", false, toolTitle = toolTitle)
            }
            WritePathGuard.denyReason(path)?.let {
                return ToolExecutionResult(it, false, toolTitle = toolTitle)
            }
            val edits = coerceEdits(args)
            if (edits.length() == 0) {
                val keys = args.keys().asSequence().joinToString(",")
                return ToolExecutionResult(
                    "Error: edits required as an array of {old_string, new_string}. " +
                        "A JSON string, one edit object, or a top-level old_string/new_string pair is also accepted. " +
                        "Received keys: $keys",
                    false,
                    toolTitle = toolTitle,
                )
            }
            var last: ToolExecutionResult? = null
            for (i in 0 until edits.length()) {
                val e = edits.getJSONObject(i)
                val one = JSONObject()
                    .put("tool_title", toolTitle)
                    .put("path", path)
                    .put("old_string", e.optString("old_string", e.optString("old", "")))
                    .put("new_string", e.optString("new_string", e.optString("new", "")))
                    .put("replace_all", e.optBoolean("replace_all", false))
                last = FileEditTool.execute(one.toString(), sessionId, context)
                if (last?.success != true) {
                    return ToolExecutionResult(
                        "multi_edit stopped at #${i + 1}: ${last?.output}",
                        false,
                        toolTitle = toolTitle,
                    )
                }
            }
            ToolExecutionResult("Applied ${edits.length()} edits to $path. ${last?.output.orEmpty()}", true, toolTitle = toolTitle)
        } catch (e: Exception) {
            ToolExecutionResult("Error multi_edit: ${e.message}", false, toolTitle = toolTitle)
        }
    }

    /**
     * Models sometimes stringify the array, nest it under replacements/changes,
     * or send one old_string/new_string pair. Those used to become "edits required"
     * even though the edit was in the arguments.
     */
    private fun coerceEdits(args: JSONObject): JSONArray {
        val raw = args.opt("edits")
            ?: args.opt("replacements")
            ?: args.opt("changes")
            ?: args.opt("operations")
            ?: args.optJSONObject("input")?.opt("edits")
            ?: args.optJSONObject("arguments")?.opt("edits")
        val arr = when (raw) {
            is JSONArray -> raw
            is JSONObject -> JSONArray().put(raw)
            is String -> parseEditString(raw)
            else -> JSONArray()
        }
        if (arr.length() == 0 && (args.has("old_string") || args.has("old"))) {
            arr.put(
                JSONObject()
                    .put("old_string", args.optString("old_string", args.optString("old", "")))
                    .put("new_string", args.optString("new_string", args.optString("new", ""))),
            )
        }
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            if (!item.has("old_string") && item.has("old")) item.put("old_string", item.opt("old"))
            if (!item.has("new_string") && item.has("new")) item.put("new_string", item.opt("new"))
        }
        return arr
    }

    private fun parseEditString(raw: String): JSONArray {
        val text = raw.trim()
        if (text.isEmpty()) return JSONArray()
        return try {
            JSONArray(text)
        } catch (_: Exception) {
            try {
                JSONArray().put(JSONObject(text))
            } catch (_: Exception) {
                JSONArray()
            }
        }
    }
}
