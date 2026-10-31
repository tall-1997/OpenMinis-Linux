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
