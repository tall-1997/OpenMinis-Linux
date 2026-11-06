package com.openminis.app.ui.chat

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
