package com.openminis.app.ui.chat

import com.openminis.app.R
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the agent loop should do after a stream ended with content but no finish reason. */
internal enum class ReasonlessEnd {
    /** The merged content is accepted as the answer; the loop synthesizes `end_turn`. */
    COMPLETE,
    /** One bounded text-turn continuation; the loop re-enters with the drop reminder. */
    TEXT_CONTINUE,
    /** The full continuation ladder (tool-call turns: a truncated call is dangerous). */
    LADDER_CONTINUE,
    /** Budget exhausted or run stopped: surface the interruption and stop the loop. */
    SURFACE,
}

/**
 * [T-android-stream-drop-autocontinue][T-gemini-signalless-eof] Relay-side silent
 * cuts: the SSE stream ended with no finish_reason and no [DONE] while content was
 * already on screen. The partial assistant turn is already appended and persisted,
 * so a continuation is safe and lossless — mirrors the Goal auto-continuation.
 *
 * A relay that never forwards finish_reason must not tax every COMPLETE text
 * answer with the full ladder (re-billed continuations + an error banner), so a
 * text-only turn gets ONE continuation; if that continuation also ends without a
 * reason, the merged content is accepted as the answer — the same trade the
 * pre-fix behavior made, now bounded. Tool-call turns keep the full ladder: a
 * truncated call is dangerous.
 */
internal suspend fun ChatViewModel.resolveReasonlessStreamEnd(
    isTextOnly: Boolean,
    textChars: Int,
    turn: Int,
    providerId: String,
    providerName: String,
    runStopped: Boolean,
    reasonlessTextContinuations: Int,
    streamDropContinuations: Int,
    resumeEpoch: Long,
): ReasonlessEnd {
    if (isTextOnly && reasonlessTextContinuations >= 1) {
        AppLogger.info(
            ChatViewModel.TAG_STREAM,
            "reason-less stream ended again after continuation — accepting $textChars chars as complete (turn=$turn)",
        )
        return ReasonlessEnd.COMPLETE
    }
    if (runStopped) return ReasonlessEnd.SURFACE
    if (isTextOnly) {
        AppLogger.warning(
            ChatViewModel.TAG_STREAM,
            "stream closed without a finish reason after $textChars chars — " +
                "single text-turn continuation (turn=$turn, model=$providerId, provider=$providerName)",
        )
        commitStreamDropReminder()
        return ReasonlessEnd.TEXT_CONTINUE
    }
    if (streamDropContinuations < ChatViewModel.MAX_STREAM_DROP_CONTINUATIONS) {
        AppLogger.warning(
            ChatViewModel.TAG_STREAM,
            "stream closed without a finish reason after $textChars chars — " +
                "auto-continuation ${streamDropContinuations + 1}/${ChatViewModel.MAX_STREAM_DROP_CONTINUATIONS} " +
                "(turn=$turn, model=$providerId, provider=$providerName)",
        )
        commitStreamDropReminder()
        return ReasonlessEnd.LADDER_CONTINUE
    }
    AppLogger.warning(
        ChatViewModel.TAG_STREAM,
        "stream closed without a finish reason after $textChars chars — " +
            "surfacing as an interrupted reply (turn=$turn)",
    )
    // The budget is exhausted but the turn is NOT over: commit the same reminder
    // the automatic continuations used, so the persisted tail stays resumable.
    // Without it the tail is a plain partial reply, which InterruptedTailDetector
    // reads as a COMPLETED turn — after a reload there is nothing to resume and
    // the banner's Retry can only regenerate and hit the same cut.
    commitStreamDropReminder()
    // A deliberate stop is not an error to surface — the old banner path
    // skipped the inline error when the run was stopped.
    if (!runStopped) {
        withContext(Dispatchers.Main) {
            setInlineError(context.getString(R.string.chat_error_stream_dropped_partial))
        }
    }
    // [T-android-group-pause-badge-restamp] A LIVE interruption just happened:
    // real entry into the paused state.
    markLiveInterruptionAndArmResume(resumeEpoch)
    return ReasonlessEnd.SURFACE
}
