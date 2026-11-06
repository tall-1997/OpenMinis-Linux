package com.openminis.app.ui.chat

// [port-upstream-1.16] Ported from OpenMinis 1.16-beta (verbatim).

/**
 * [T-android-thinking-row-follows-run] The "… is thinking" row must not
 * outlive the run.
 *
 * Three things on screen say "a turn is running", and each read a different
 * state:
 *  - the thinking row: the assistant BUBBLE's own flags
 *    (`isStreaming && (no visible content || isAwaitingModelResponse)`);
 *  - the composer's stop / send button: the ViewModel's `isStreaming`;
 *  - the background capsule and notification: SessionActivityTracker.
 * The last two are cleared by every exit. The bubble's flags were cleared by
 * whichever exit path remembered to (normal end, the error banner, Stop's
 * cleanup). One did not: switching model while the turn sat in the auto-retry
 * ladder cancels the job and clears the other two, and deliberately skips
 * Stop's cleanup - so the placeholder bubble kept saying "thinking" next to a
 * send button, with nothing running (user report 2026-10-07).
 *
 * So the bubble's flags are now settled from the run state itself: whenever
 * the ViewModel is not streaming, no bubble may still claim to be.
 */
internal object ThinkingRowSettle {

    /**
     * [messages] with every bubble's streaming / awaiting flag cleared, or
     * null when none was set (nothing to publish). Content, tool blocks and
     * errors are left as they are.
     */
    fun settled(messages: List<ChatMessage>): List<ChatMessage>? {
        if (messages.none { it.isStreaming || it.isAwaitingModelResponse }) return null
        return messages.map {
            if (it.isStreaming || it.isAwaitingModelResponse) it.copy(isStreaming = false, isAwaitingModelResponse = false) else it
        }
    }
}

/**
 * [messages] with every "still running" claim settled, because no run is
 * streaming right now. Applied at the single `uiMessages` sink
 * ([com.openminis.app.ui.chat.ChatViewModel.uiMessages]), so the ~10 exit paths
 * (normal end, error banner, stop, retry ladder, resume, rerun, group chat…)
 * no longer each have to remember it.
 *
 * - [ThinkingRowSettle] clears the bubble's streaming / awaiting flags, so a
 *   turn cannot keep saying "thinking" next to a send button.
 * - [OrphanedRunningToolGate] turns a tool row still in a spinner state into
 *   TIMEOUT: RUNNING is a claim about *right now* and must be backed by a live
 *   job. An orphaned RUNNING draws an infinite shimmer and pins the UI at
 *   display refresh rate forever (measured upstream: 91 fps of no-op frames on
 *   a 144-message session). Sub-agent transcript cards are exempt — a
 *   background delegation really can outlive the main turn.
 */
internal fun settleIdleRows(messages: List<ChatMessage>): List<ChatMessage> {
    var out = ThinkingRowSettle.settled(messages) ?: messages
    if (out.any { msg -> msg.toolBlocks.any { OrphanedRunningToolGate.isSpinner(it.toolStatus) } }) {
        out = out.map { msg ->
            if (msg.toolBlocks.none { OrphanedRunningToolGate.isSpinner(it.toolStatus) }) {
                msg
            } else {
                msg.copy(toolBlocks = msg.toolBlocks.map { block ->
                    if (isSubAgentTranscriptCard(block)) {
                        block
                    } else {
                        block.copy(
                            toolStatus = OrphanedRunningToolGate.resolve(
                                block.toolStatus,
                                isLiveRun = false,
                                jobIsAlive = false,
                            ),
                        )
                    }
                })
            }
        }
    }
    return out
}
