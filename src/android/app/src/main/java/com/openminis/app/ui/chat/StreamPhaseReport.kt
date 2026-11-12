package com.openminis.app.ui.chat

import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.countsAsFirstTokenProgress

/**
 * [GH#521] 单次请求的阶段时钟：request → headers(`Started`) → 首个真实 token。
 *
 * 两个数字分开记录/输出，避免"慢上传 / 慢网关 headers / headers 后静默"
 * 都藏在同一个 `ttfbMs` 里：headersMs=0 表示连 headers 都没到；headersMs>0
 * 且 firstTokenMs=none 正是 issue 报告的"200 OK 之后静默"形状。
 *
 * 从 runAgentLoopBody 拆出（架构行数上限），行为逐字节保持。
 */
internal class StreamPhaseReport {

    private var attemptStartNanos = 0L
    private var headersAtNanos = 0L
    private var firstTokenAtNanos = 0L

    fun startAttempt() {
        attemptStartNanos = System.nanoTime()
        headersAtNanos = 0L
        firstTokenAtNanos = 0L
    }

    fun onChunk(chunk: LLMStreamChunk) {
        val chunkAt = System.nanoTime()
        if (chunk is LLMStreamChunk.Started) {
            if (headersAtNanos == 0L) headersAtNanos = chunkAt
        } else if (firstTokenAtNanos == 0L && chunk.countsAsFirstTokenProgress()) {
            firstTokenAtNanos = chunkAt
        }
    }

    fun logSuccess(turn: Int, sessionId: String, modelId: String) {
        if (headersAtNanos == 0L) return
        val headersMs = (headersAtNanos - attemptStartNanos) / 1_000_000L
        val firstTokenMs = if (firstTokenAtNanos != 0L) {
            (firstTokenAtNanos - attemptStartNanos) / 1_000_000L
        } else {
            -1L
        }
        val waitAfterHeaders = if (firstTokenAtNanos != 0L) {
            ((firstTokenAtNanos - headersAtNanos) / 1_000_000L).toString()
        } else {
            "none"
        }
        AppLogger.info(
            ChatViewModel.TAG_STREAM,
            "[T-first-token] turn=$turn sid=${sessionId.take(8)} " +
                "model=$modelId headersMs=$headersMs " +
                "firstTokenMs=$firstTokenMs waitAfterHeadersMs=$waitAfterHeaders",
        )
    }

    fun logFailure(turn: Int, error: Throwable) {
        if (headersAtNanos == 0L) return
        val firstTokenPhase = if (firstTokenAtNanos != 0L) {
            ((firstTokenAtNanos - attemptStartNanos) / 1_000_000L).toString()
        } else {
            "none"
        }
        AppLogger.warning(
            ChatViewModel.TAG_STREAM,
            "[T-first-token] attempt failed turn=$turn " +
                "headersMs=${(headersAtNanos - attemptStartNanos) / 1_000_000L} " +
                "firstTokenMs=$firstTokenPhase " +
                "error=${error.javaClass.simpleName}: ${error.message?.take(160)}",
        )
    }
}
