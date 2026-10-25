package com.openminis.app.agent

/**
 * The synthetic user turn that tells a model its previous reply was cut off
 * in transit and where to pick up.
 *
 * One text, two consumers, and they must not drift:
 *
 *  - the agent loop appends it when a relay silently closes an SSE stream
 *    with no `finish_reason` (both for the automatic continuation and for the
 *    turn that surfaces the "connection dropped" banner), and
 *  - [InterruptedTailDetector] matches [MARKER] to recognise such a tail as
 *    resumable after a reload or a process death.
 *
 * A third reminder with the same job already exists for a deliberate stop
 * ([InterruptedTailDetector.CONTINUE_REMINDER_MARKER]); the two are kept as
 * separate strings because they describe different interruptions to the model,
 * but both classify as `CONTINUE_REMINDER`.
 */
object StreamDropReminder {

    /**
     * Substring that identifies this reminder in a persisted history tail.
     * Deliberately short and free of punctuation that a translation or a
     * rewrite might drop.
     */
    const val MARKER = "The connection dropped mid-reply"

    /** The full reminder text. */
    const val TEXT = "<system-reminder>$MARKER: your previous assistant message " +
        "was cut off in transit and ends abruptly in the transcript above. " +
        "Continue the task from EXACTLY where that message stopped. Do not " +
        "repeat or restate any text already present there. If a sentence was " +
        "cut mid-way, complete it; if you were about to call a tool, issue " +
        "that tool call now; if the work was already finished, give the final " +
        "answer.</system-reminder>"
}
