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
     * [T-p2-multiedit-args-coerce] 执行器与 schema 校验同源的**预矫正**：本工具的
     * 宽容传参形状（replacements/changes/operations 别名键、字符串化数组、单对象、
     * 条目内 old/new 缩写、顶层 old_string/new_string 对）在 [coerceEdits] 里处理，
     * 但 schema 校验先于工具执行——严格类型检查把工具本可接受的形状弹回
     * （「参数 edits 类型错误：应为 array，实际是字符串」「不接受参数 old、new」），
     * 工具的宽容路径变成死代码。执行器在归一化后、schema 校验前调用本函数，
     * 校验与执行两侧看到同一份 coerced args。
     *
     * @return 矫正后的 argsJson；解析失败原样返回（工具自有的错误路径接住）。
     */
    fun coerceArgsJson(argsJson: String): String {
        val parsed = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(argsJson) as? kotlinx.serialization.json.JsonObject
        }.getOrNull() ?: return argsJson
        return runCatching { coerceMultiEditArgs(parsed).toString() }.getOrDefault(argsJson)
    }

    private fun coerceMultiEditArgs(args: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject {
        if (!args.containsKey("edits")) {
            val alias = args["replacements"] ?: args["changes"] ?: args["operations"]
                ?: (args["input"] as? kotlinx.serialization.json.JsonObject)?.get("edits")
                ?: (args["arguments"] as? kotlinx.serialization.json.JsonObject)?.get("edits")
            if (alias == null) {
                // 顶层 old_string/new_string 对 → 单条 edit（coerceEdits 的同款宽容）
                val old = args["old_string"] ?: args["old"] ?: return args
                val new = args["new_string"] ?: args["new"] ?: return args
                val kept = kotlinx.serialization.json.buildJsonObject {
                    args.forEach { (k, v) -> if (k != "old" && k != "new") put(k, v) }
                    put("edits", kotlinx.serialization.json.JsonArray(listOf(
                        kotlinx.serialization.json.buildJsonObject {
                            put("old_string", old)
                            put("new_string", new)
                        },
                    )))
                }
                return kept
            }
            return kotlinx.serialization.json.buildJsonObject {
                args.forEach { (k, v) ->
                    if (k != "replacements" && k != "changes" && k != "operations" && k != "input" && k != "arguments") put(k, v)
                }
                put("edits", coerceEditsShape(alias))
            }
        }
        return kotlinx.serialization.json.buildJsonObject {
            args.forEach { (k, v) -> put(k, if (k == "edits") coerceEditsShape(v) else v) }
        }
    }

    /**
     * edits 形状宽容（coerceEdits 的 kotlinx 对应半边）：字符串化数组/对象 → 数组；
     * 单对象 → 包一层数组；条目内 old/new → old_string/new_string **改名**
     * （原键移除——item schema 拒绝未知键，保留会继续误报「不接受参数」）。
     */
    private fun coerceEditsShape(raw: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonElement {
        val arr: kotlinx.serialization.json.JsonArray = when (raw) {
            is kotlinx.serialization.json.JsonArray -> raw
            is kotlinx.serialization.json.JsonObject -> kotlinx.serialization.json.JsonArray(listOf(raw))
            is kotlinx.serialization.json.JsonPrimitive -> runCatching {
                when (val parsed = kotlinx.serialization.json.Json.parseToJsonElement(raw.content)) {
                    is kotlinx.serialization.json.JsonArray -> parsed
                    is kotlinx.serialization.json.JsonObject -> kotlinx.serialization.json.JsonArray(listOf(parsed))
                    else -> kotlinx.serialization.json.JsonArray(emptyList())
                }
            }.getOrDefault(kotlinx.serialization.json.JsonArray(emptyList()))
            else -> return raw
        }
        val items = arr.map { element ->
            val item = element as? kotlinx.serialization.json.JsonObject ?: return@map element
            val oldString = item["old_string"] ?: item["old"]
            val newString = item["new_string"] ?: item["new"]
            if (oldString == null && newString == null) return@map item
            kotlinx.serialization.json.buildJsonObject {
                item.forEach { (k, v) ->
                    when (k) {
                        "old", "new" -> Unit // 改名后原键移除
                        else -> put(k, v)
                    }
                }
                if (oldString != null && !item.containsKey("old_string")) put("old_string", oldString)
                if (newString != null && !item.containsKey("new_string")) put("new_string", newString)
            }
        }
        return kotlinx.serialization.json.JsonArray(items)
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
