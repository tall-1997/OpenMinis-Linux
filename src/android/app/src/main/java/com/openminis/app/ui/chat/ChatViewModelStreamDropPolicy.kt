package com.openminis.app.ui.chat

/** A relay may omit the terminal event even after delivering all its text. */
internal enum class ReasonlessEnd { COMPLETE, TEXT_CONTINUE, SURFACE }

/** Allow one text continuation while there is a dispatch slot. The next text response can complete. */
internal fun resolveReasonlessStreamEnd(
    continuationUsed: Boolean,
    hasReplyCandidate: Boolean,
    canDispatchAgain: Boolean,
): ReasonlessEnd = when {
    continuationUsed && hasReplyCandidate -> ReasonlessEnd.COMPLETE
    !continuationUsed && canDispatchAgain -> ReasonlessEnd.TEXT_CONTINUE
    else -> ReasonlessEnd.SURFACE
}
