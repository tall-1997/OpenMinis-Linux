package com.openminis.app.ui.chat

// AssistantBlock lives in this package (ChatModels.kt); no import needed.

// Helper: extract shell command from args or content (mirrors iOS toolDescription logic).
// Also tolerant of *partial* streaming JSON (JSONObject.optString returns "" for
// truncated objects, so fall back to a streaming-safe substring scan).
// Extracted from ChatToolDetailUI to keep that file under its line ceiling;
// both helpers are detail-sheet render support used only within this package.
internal fun extractShellCommand(args: org.json.JSONObject, block: AssistantBlock): String {
    // 1. Try toolArgs "command" field (works once JSON is complete)
    val fromArgs = args.optString("command", "")
    if (fromArgs.isNotEmpty()) return fromArgs
    // 2. Streaming fallback: scan the raw toolArgs buffer for `"command":"…`
    //    which may not yet close. Mirrors iOS extractPartialStringValue.
    val partial = extractPartialJsonString("command", block.toolArgs)
    if (!partial.isNullOrEmpty()) return partial
    // 3. Try parsing "$ <command>" from first line of content (iOS fallback)
    if (block.content.startsWith("$ ")) {
        val firstLine = block.content.lineSequence().firstOrNull() ?: ""
        if (firstLine.length > 2) return firstLine.drop(2)
    }
    // 4. iOS fallback: generic "Shell command"
    return "Shell command"
}

/**
 * Tolerant partial-JSON string extractor — UI-side mirror of
 * StreamChunkJsonHelper.extractPartialStringValue. Used so detail-sheet renderers
 * (shell command, file path, write content) can show live content while the
 * model is still streaming the tool input JSON.
 */
internal fun extractPartialJsonString(key: String, json: String): String? {
    if (json.isEmpty()) return null
    val patterns = listOf("\"$key\": \"", "\"$key\":\"")
    for (p in patterns) {
        val at = json.indexOf(p)
        if (at < 0) continue
        val after = json.substring(at + p.length)
        var i = 0
        val n = after.length
        while (i < n) {
            val c = after[i]
            if (c == '\\') { i += 2; continue }
            if (c == '"') {
                return after.substring(0, i)
                    .replace("\\n", "\n").replace("\\t", "\t")
                    .replace("\\\"", "\"").replace("\\/", "/")
                    .replace("\\\\", "\\")
            }
            i++
        }
        // No closing quote yet (still streaming) — return what we have.
        return after.replace("\\n", "\n").replace("\\t", "\t")
            .replace("\\\"", "\"").replace("\\/", "/")
            .replace("\\\\", "\\")
    }
    return null
}

/**
 * [P1-17] Single source for the model-provided `tool_title` argument.
 * Replaces three hand-rolled extractions that had drifted apart
 * (ToolTurnExec dispatch status, ExecuteToolExt guard results, AgentLoopExt
 * streaming pill):
 *
 *  1. Complete-JSON path — `JSONObject.optString` is authoritative once the
 *     args are fully received (it also decodes \uXXXX escapes the substring
 *     scan doesn't).
 *  2. Partial/streaming path — [extractPartialJsonString] scans the value out
 *     of a still-streaming args buffer (`"tool_title": "Editing Cha…`), where
 *     the JSONObject constructor would throw.
 *
 * A blank / absent title falls through to [fallback], so callers keep their
 * own "what to show instead" policy (null, the canonical tool name, the
 * previous live title, …). Note the deliberate unification of the
 * explicit-empty case: sites that used to surface an empty-string title now
 * take the fallback, matching the isBlank() handling those same call sites
 * apply downstream.
 */
internal fun extractToolTitle(argsJson: String, fallback: String?): String? {
    if (argsJson.isEmpty()) return fallback
    val fromJson = try {
        org.json.JSONObject(argsJson).optString("tool_title", "")
    } catch (_: Exception) {
        ""
    }
    if (fromJson.isNotBlank()) return fromJson
    val partial = extractPartialJsonString("tool_title", argsJson)
    if (!partial.isNullOrBlank()) return partial
    return fallback
}
