package com.openminis.app.ui.chat

import com.openminis.app.browser.BrowserActionInput
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.tools.MemoryTools
import org.json.JSONObject

// [T-android-split-chat-toolhelpers] Legacy tool execution helpers + small
// misc helpers extracted verbatim from ChatViewModel.kt (mechanical split,
// zero behavior change). Member functions became ChatViewModel extensions with
// identical signatures. BrowserToolResult stays nested on ChatViewModel.

// ─── Legacy tool execution methods (kept for compatibility) ───────────

fun ChatViewModel.executeMemoryWrite(argsJson: String): MemoryTools.ToolResult {
    val repo = sessionMemoryRepo()
    if (!_memoryEnabled.value) {
        return MemoryTools.ToolResult(
            "Memory writes are disabled for this session. Reads are still available. The user can re-enable writes via the /memory slash command.",
            false,
        )
    }
    val result = MemoryTools.executeMemoryWrite(argsJson, repo)
    val content = try {
        JSONObject(argsJson).optString("content", "")
    } catch (_: Exception) { "" }
    _memoryToolRecords.value = _memoryToolRecords.value + MemoryToolRecord(
        title = result.toolTitle,
        isWrite = true,
        preview = content.lines().firstOrNull { it.isNotBlank() }?.take(100) ?: "",
        output = result.output,
        writtenContent = content,
    )
    return result
}

fun ChatViewModel.executeMemoryGet(argsJson: String): MemoryTools.ToolResult {
    val repo = sessionMemoryRepo()
    val result = MemoryTools.executeMemoryGet(argsJson, repo)
    val keywords = try {
        JSONObject(argsJson).optString("keywords", "")
    } catch (_: Exception) { "" }
    _memoryToolRecords.value = _memoryToolRecords.value + MemoryToolRecord(
        title = result.toolTitle,
        isWrite = false,
        preview = if (keywords.isNotBlank()) "Search: $keywords" else result.output.take(100),
        output = result.output,
        keywords = keywords,
    )
    return result
}

suspend fun ChatViewModel.executeBrowserUse(argsJson: String): ChatViewModel.BrowserToolResult {
    val input = BrowserActionInput.parse(argsJson)
        ?: return ChatViewModel.BrowserToolResult(text = "Error: Invalid browser_use input. Required: 'action' parameter.", success = false)

    return try {
        val result = browserTabPool.execute(input)
        ChatViewModel.BrowserToolResult(
            text = result.text,
            success = result.success,
            base64Image = result.base64Image,
            imageFilePath = result.imageFilePath,
            pageURL = result.pageURL,
        )
    } catch (e: Exception) {
        ChatViewModel.BrowserToolResult(text = "Error: ${e.message}", success = false)
    }
}

// ─── Misc Helpers ────────────────────────────────────────────────────

/**
 * [T-android-overlay-reply-status-34599] Pull the most recent
 * assistant text out of `_messages` and hand it to
 * [SessionActivityTracker.publishLastReply]. The tracker truncates
 * to a fixed-width excerpt and pairs it with [sessionId] so the
 * floating overlay can render a "tap to open this chat" capsule
 * after the stream completes. No-op when no assistant message has
 * content yet (e.g. fail during the very first turn).
 */
internal fun ChatViewModel.publishOverlayReplyExcerpt(sessionId: String) {
    val snapshot = _messages.value
    val text = snapshot.asReversed().firstOrNull { msg ->
        msg.role == "assistant" && msg.content.isNotBlank()
    }?.content
    SessionActivityTracker.publishLastReply(sessionId, text)
}

internal fun ChatViewModel.escapeJson(text: String): String {
    val sb = StringBuilder("\"")
    for (c in text) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> {
                if (c.code < 0x20) sb.append("\\u%04x".format(c.code))
                else sb.append(c)
            }
        }
    }
    sb.append("\"")
    return sb.toString()
}
