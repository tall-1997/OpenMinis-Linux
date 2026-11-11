package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

/**
 * [GH#352] Last-ditch guard against an image that entered history as TEXT.
 *
 * 中文：图片若以 base64 文本进入历史，会在每次请求中重复计入上下文并
 * 撑爆窗口（该 issue 实测 1MB 图 ≈96 万 token）；发送前把这种文本替换为
 * 简短占位，图片字节仍走结构化 image 通道，会话得以恢复。
 *
 * The reported session died because a 1,030,449-byte PNG was carried into the
 * request as base64 text (~959,936 tokens) instead of an image content block
 * (267 tokens). The bad bytes live in persisted history, so every retry — and
 * every later user turn — re-sent the same over-limit request and the session
 * stayed unusable. Compaction only helps if it happens to drop that exact
 * message.
 *
 * This scrubber works on the OUTGOING history only (never the DB): a Text
 * content part (or a plain message body) that is both very large and carries
 * an inline base64/data-URL image is replaced by a bounded note. The model
 * loses a recoverable-by-user attachment, not the whole conversation; the
 * request can leave the device and the user is told to re-attach.
 *
 * Threshold is deliberately far above any real text tool output (which is
 * capped/offloaded elsewhere) and far below the 1MB report.
 */
internal object TextifiedImageGuard {

    /** Text this large containing base64 is an attachment, not prose. */
    internal const val MIN_BASE64_CHARS = 64_000

    internal const val STUB_PREFIX =
        "[Inline image data omitted from history: "

    internal const val STUB_SUFFIX =
        " characters of base64/data-URL. This session would exceed the model's " +
            "context window if it were sent as text. Ask the user to re-attach the " +
            "image if it is still needed.]"

    data class Result(
        val history: List<LLMMessage>,
        val scrubbedParts: Int,
        val savedChars: Long,
    )

    fun scrub(history: List<LLMMessage>): Result {
        var parts = 0
        var saved = 0L
        val out = history.map { msg ->
            val cleanedParts = msg.contentParts.map { part ->
                when (part) {
                    is AgentContentPart.Text -> {
                        val text = part.text
                        if (looksLikeTextifiedImage(text)) {
                            parts += 1
                            val replacement = stub(text.length)
                            saved += (text.length - replacement.length).toLong()
                            part.copy(text = replacement)
                        } else {
                            part
                        }
                    }
                    is AgentContentPart.ToolResult -> {
                        // A tool can also hand back a base64 blob as its text
                        // content (e.g. fetch/browser results) while its pixels
                        // ride imageData. Keep the image block, drop the text
                        // duplicate that would cost ~3600x the tokens.
                        val text = part.content
                        if (looksLikeTextifiedImage(text)) {
                            parts += 1
                            val replacement = stub(text.length)
                            saved += (text.length - replacement.length).toLong()
                            part.copy(content = replacement)
                        } else {
                            part
                        }
                    }
                    else -> part
                }
            }
            val cleanedBody = if (looksLikeTextifiedImage(msg.content)) {
                parts += 1
                val replacement = stub(msg.content.length)
                saved += (msg.content.length - replacement.length).toLong()
                replacement
            } else {
                msg.content
            }
            if (cleanedParts == msg.contentParts && cleanedBody == msg.content) {
                msg
            } else {
                msg.copy(content = cleanedBody, contentParts = cleanedParts)
            }
        }
        return Result(out, parts, saved)
    }

    internal fun looksLikeTextifiedImage(text: String): Boolean =
        text.length >= MIN_BASE64_CHARS &&
            (text.contains(";base64,") || text.contains("data:image"))

    private fun stub(originalChars: Int): String = STUB_PREFIX + originalChars + STUB_SUFFIX
}
