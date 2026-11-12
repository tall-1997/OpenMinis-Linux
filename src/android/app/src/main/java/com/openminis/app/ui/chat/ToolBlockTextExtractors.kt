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
 * [T-shell-detail-dedupe GH#339] Drop a leading `$ <command>` echo from a
 * shell block's stored output.
 *
 * The detail sheet renders the command itself (from `toolArgs`) at the top of
 * the terminal card. Older/persisted blocks (and imports produced by hosts
 * that prefixed the prompt) store the same line as the first line of
 * `block.content`, so the sheet printed it twice. [extractShellCommand]'s
 * fallback #3 documents that this shape exists; this is the render-side half.
 * A genuine output line equal to the command without the `$ ` prefix is left
 * alone — only the echoed form is stripped.
 */
internal fun stripShellCommandEcho(content: String, command: String): String {
    if (content.isEmpty() || command.isEmpty()) return content
    val cmd = command.trim()
    if (cmd.isEmpty()) return content
    // Only the `$ `-prefixed echo is stripped. A bare first line equal to the
    // command is genuine output far more often (e.g. `ls` listing a file named
    // `ls`), so it must be kept.
    val prefixes = listOf("$ $cmd", "$ ${cmd.replace("\n", " ")}")
    for (prefix in prefixes) {
        if (!content.startsWith(prefix)) continue
        // Only strip when the match is a WHOLE first line — a prefix of a
        // longer output line ("$ ls -la /tmp" vs "$ ls -la /tmp2") must not
        // lose that line's tail.
        val rest = content.substring(prefix.length)
        if (rest.isEmpty()) return ""
        if (rest.startsWith("\n")) return rest.removePrefix("\n")
        if (rest.startsWith("\r\n")) return rest.removePrefix("\r\n")
    }
    return content
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
