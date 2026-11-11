package com.openminis.app.ui.chat

/**
 * [GH#119] 截断参数被自动闭合后执行的统一提示文案（UI + 模型侧）。
 *
 * The user-visible and model-visible wording for a tool call whose
 * argument stream arrived truncated and was auto-closed before execution.
 *
 * Before this existed the repair was only an AppLogger WARNING: the tool block
 * rendered as a normal success and the model's tool_result said "success", so
 * a half-written file silently became the basis for the next steps. Both sides
 * now get the same facts, including how many bytes the tool actually wrote
 * when the result reports it ("Wrote to /x (1234 bytes)", "Edited … (12
 * bytes)"); when the tool does not report a size we fall back to the size of
 * the argument stream that was repaired, which is still enough for the model
 * to decide to re-read the target.
 *
 * Pure functions only — pinned by TruncatedArgsNoticeTest.
 */
internal object TruncatedArgsNotice {

    /**
     * Parse the byte count out of a tool result such as `Wrote to /x (1234 bytes)`.
     *
     * [GH#119] Some results only put the count at the end without its own
     * opening paren — `Edited /a (1 replacement(s), 12 bytes)` — so the number
     * match must not require the leading `(`. The closing `)` IS required:
     * without it, ordinary prose like "The file has 100 bytes" (or a path
     * containing "100 bytes") would be reported as the written size.
     */
    fun writtenBytes(toolOutput: String): Int? =
        WRITTEN_BYTES.find(toolOutput)?.groupValues?.getOrNull(1)?.toIntOrNull()

    /** Size of the repaired argument payload in UTF-8 bytes (0 on malformed input). */
    fun argumentBytes(argsJson: String): Int = argsJson.toByteArray(Charsets.UTF_8).size

    /** One-line marker prepended to the tool block content (UI side). */
    fun uiLine(repairTag: String, writtenBytes: Int?, argumentBytes: Int): String {
        val size = if (writtenBytes != null) {
            "已写入 $writtenBytes 字节"
        } else {
            "修复后的参数共 $argumentBytes 字节"
        }
        return "⚠ 参数流在传输中被截断（$repairTag），客户端自动闭合 JSON 后执行；" +
            "$size，结果可能不完整，建议重读目标确认。"
    }

    /** `<system-reminder>` appended to the tool_result the model sees. */
    fun modelNote(repairTag: String, writtenBytes: Int?, argumentBytes: Int): String {
        val size = if (writtenBytes != null) {
            "the tool reported $writtenBytes bytes written"
        } else {
            "the repaired arguments were $argumentBytes bytes"
        }
        return "<system-reminder>The argument stream for this call was truncated in transit " +
            "and auto-closed by the client before execution (repair strategy: $repairTag; " +
            "$size). The arguments actually used may be incomplete. Re-read the affected " +
            "file/path and verify the result before building on it; re-issue the call with " +
            "complete arguments if anything is missing.</system-reminder>"
    }

    private val WRITTEN_BYTES = Regex("""(\d+)\s+bytes?\)""")
}
