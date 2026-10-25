package com.openminis.app.ui.chat

import com.openminis.app.data.repository.MultiAgentSettings
import com.openminis.app.tools.SubAgentKind
import com.openminis.app.tools.WritePathGuard
import com.openminis.app.data.model.ThinkingLevel
import org.json.JSONArray
import org.json.JSONObject

internal data class ChatSubAgentSpawn(
    val prompt: String,
    val role: String?,
    val skills: String?,
    val requestedModel: String?,
    val title: String,
    val kind: String,
    val writePaths: List<String>,
    val maxTurns: Int,
    val tokenBudget: Long? = null,
    // Null means no per-task override; dispatch resolves the selected model's
    // effective maximum for this one task.
    val thinkingLevel: ThinkingLevel? = null,
)

internal fun parseSubAgentSpawn(argsJson: String, defaultCap: Int): ChatSubAgentSpawn? {
    return parseSubAgentBatch(argsJson, defaultCap).singleOrNull()
}

internal fun parseSubAgentBatch(argsJson: String, defaultCap: Int): List<ChatSubAgentSpawn> {
    val args = try {
        JSONObject(argsJson)
    } catch (_: Exception) {
        return emptyList()
    }
    val tasks = coerceTasksArray(args.opt("tasks"))
    val raw = if (tasks != null && tasks.length() > 0) {
        (0 until tasks.length()).mapNotNull { i -> tasks.optJSONObject(i) }
    } else {
        listOf(args)
    }
    return raw.mapNotNull { obj -> parseTaskObject(obj, defaultCap) }
        .take(MultiAgentSettings.MAX_CONCURRENT)
}

internal fun parseWritePathsArg(obj: JSONObject): List<String> {
    if (!obj.has("write_paths")) return emptyList()
    val raw = obj.opt("write_paths")
    val joined = when (raw) {
        is JSONArray -> (0 until raw.length()).joinToString(",") { raw.optString(it) }
        null, JSONObject.NULL -> ""
        else -> raw.toString()
    }
    return WritePathGuard.parse(joined)
}

private fun coerceTasksArray(raw: Any?): JSONArray? = when (raw) {
    is JSONArray -> raw
    is String -> {
        val t = raw.trim()
        if (t.startsWith("[")) {
            try {
                JSONArray(t)
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
    }
    else -> null
}

private fun parseTaskObject(args: JSONObject, defaultCap: Int): ChatSubAgentSpawn? {
    val prompt = args.optString("prompt", "").trim()
    if (prompt.isEmpty()) return null
    val kind = SubAgentKind.normalize(args.optString("kind", ""))
    val requested = if (args.has("max_turns")) args.optInt("max_turns") else null
    val budgetRaw = if (args.has("token_budget")) args.optLong("token_budget", -1L) else null
    return ChatSubAgentSpawn(
        prompt = prompt,
        role = args.optString("role", "").trim().ifEmpty { null },
        skills = args.optString("skills", "").trim().ifEmpty { null },
        requestedModel = args.optString("model", "").trim().ifEmpty { null },
        title = args.optString("tool_title", "").trim(),
        kind = kind,
        writePaths = parseWritePathsArg(args),
        maxTurns = SubAgentKind.clampTurns(kind, requested, defaultCap, prompt),
        tokenBudget = budgetRaw?.takeIf { it > 0 },
        thinkingLevel = args.optString("thinking_level", "").trim().takeIf { it.isNotEmpty() }
            ?.let { raw -> runCatching { ThinkingLevel.valueOf(raw.uppercase()) }.getOrNull() },
    )
}
