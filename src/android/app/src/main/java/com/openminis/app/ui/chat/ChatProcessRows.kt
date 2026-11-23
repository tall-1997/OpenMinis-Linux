package com.openminis.app.ui.chat

/**
 * [T-process-rows-extract] Process-card data classes, extracted from
 * ChatFlatItems.kt to keep that file under its architecture ceiling.
 * Behaviour-free: same declarations, same visibility, same shapes.
 * `ToolBlockStatus` / `AssistantBlock` / `ChatRound` live in this same package.
 */

/** One stretch of process blocks. A deferred singleton stays inline. */
internal class ProcessSegment(
    val lastMessageIdx: Int,
    val firstStepKey: String,
    val isCard: Boolean,
    val thinkingCount: Int,
    val toolCount: Int,
    val hasFailure: Boolean,
    val totalMs: Long,
    val steps: List<ProcessStepRef>,
    val running: Boolean,
    /** "thinking" / "tool" / "" for the live header. Reply text settles the card. */
    val activity: String,
    val activityTitle: String,
    val startedAtMs: Long,
)

/** One step of an expanded process card, in chronological order. */
internal data class ProcessStepRef(
    val id: String,
    val kind: String,
    val title: String,
    val status: ToolBlockStatus?,
    val durationMs: Long,
    val running: Boolean,
    val block: AssistantBlock,
    val messageId: String,
    val isLastCancelled: Boolean = false,
)

/** [T-process-rows-extract] Per-message card ownership, moved here with the data classes. */
internal class MessageProcessRows {
    val ownedBlocks = mutableSetOf<Int>()
    val cardsAfterBlock = mutableMapOf<Int, ProcessSegment>()
}

/** [T-process-rows-extract] Round index + card ownership for one build pass. */
internal class RoundSplit(
    private val rounds: List<ChatRound>,
    private val startIndex: Int,
    private val roundOfIdx: IntArray,
    val rowsByMessage: Map<Int, MessageProcessRows>,
    val boundarySegment: ProcessSegment?,
) {
    fun roundAt(idx: Int): ChatRound? = rounds.getOrNull(roundOfIdx.getOrElse(idx - startIndex) { -1 })
}
