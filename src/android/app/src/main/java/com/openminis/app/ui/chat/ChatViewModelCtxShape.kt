package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.logging.AppLogger

/**
 * [T-android-mem-probe-trust] Send-path context-shape probe, extracted from
 * runAgentLoopBody (architecture ceiling) so the counting pass is a PURE
 * function and JVM-testable. One line per run, logged before the first
 * request: history composition (chars, tool results, images, audio), the
 * largest message, the approx-token figure — and, since
 * [T-thinking-dual-ledger], the thinking tier the run was sent under, which
 * pairs with the per-turn `thinkingLevelRequested` field persisted in each
 * turn's token_usage JSON (see [TurnThinkingUsage]).
 */

/** Counted shape of one send-path history snapshot. Pure value. */
internal data class ContextShapeStats(
    val historySize: Int,
    val totalChars: Long,
    val maxMsgChars: Int,
    val maxMsgRole: String,
    val toolResultParts: Int,
    val imageParts: Int,
    val imageBytes: Long,
    val audioB64Chars: Long,
) {
    /** The line's legacy chars/4 estimate — unchanged arithmetic, by design. */
    val approxTokens: Long get() = totalChars / 4

    /**
     * The log payload up to (and including) approxTokens. The caller appends
     * the runtime memory snapshot, which is Android-bound and deliberately
     * kept out of the pure builder. [thinkingLevel] is the tier this run's
     * requests were sent under — the request side of the dual ledger.
     */
    fun line(thinkingLevel: ThinkingLevel): String =
        "[CtxShape] historySize=$historySize totalChars=$totalChars " +
            "maxMsgChars=$maxMsgChars maxMsgRole=$maxMsgRole toolResultParts=$toolResultParts " +
            "imageParts=$imageParts imageBytes=$imageBytes audioB64Chars=$audioB64Chars " +
            "thinkingLevel=${thinkingLevel.name} " +
            "approxTokens=$approxTokens"
}

/** Walk the history and count the shape. Pure — JVM-tested. */
internal fun contextShapeStats(history: List<LLMMessage>): ContextShapeStats {
    var chars = 0L
    var maxOne = 0
    var toolResults = 0
    var images = 0
    var imageBytes = 0L
    var audioChars = 0L
    var biggestRole = ""
    for (m in history) {
        var perMsg = m.content.length
        for (p in m.contentParts) {
            when (p) {
                is AgentContentPart.ToolResult -> {
                    perMsg += p.content.length
                    toolResults++
                    // Inline image bytes never reach the char count, so
                    // track them separately — an image-heavy request is
                    // a different failure shape from a text-heavy one.
                    p.imageData?.let { images++; imageBytes += it.size }
                }
                is AgentContentPart.Text -> perMsg += p.text.length
                is AgentContentPart.ImageData -> { images++; imageBytes += p.data.size }
                else -> {}
            }
        }
        for (a in m.audioParts) audioChars += a.base64Data.length
        images += m.imageParts.size
        chars += perMsg
        if (perMsg > maxOne) { maxOne = perMsg; biggestRole = m.role.name }
    }
    return ContextShapeStats(
        historySize = history.size,
        totalChars = chars,
        maxMsgChars = maxOne,
        maxMsgRole = biggestRole,
        toolResultParts = toolResults,
        imageParts = images,
        imageBytes = imageBytes,
        audioB64Chars = audioChars,
    )
}

/**
 * Log the send-path context shape once per run. runCatching: a probe must
 * never break the send — identical guarantee to the original inline block.
 */
internal fun ChatViewModel.logContextShape(history: List<LLMMessage>, thinkingLevel: ThinkingLevel) {
    runCatching {
        AppLogger.info(
            ChatViewModel.TAG_STREAM,
            contextShapeStats(history).line(thinkingLevel) +
                " ${com.openminis.app.diagnostics.MemorySnapshot.capture().toLogString()}",
        )
    }
}
