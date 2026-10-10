package com.openminis.app.ui.chat

/**
 * [T-process-run-card] Pure projection logic for the unified process card:
 * which blocks the card owns, the live phase header state, and the
 * auto/manual expansion rule shared by the flat-item builder, the floating
 * overlay and the detail sheet. Extracted from ChatFlatItems.kt to keep
 * that file under its ratchet baseline.
 */

internal fun isAlwaysVisibleProcessTool(block: AssistantBlock): Boolean =
    block.kind == "tool_use" && block.toolName == "ask_user_question"

private val IN_FLIGHT_PROCESS_TOOL_STATUSES = setOf(
    ToolBlockStatus.STREAMING,
    ToolBlockStatus.PENDING,
    ToolBlockStatus.RUNNING,
)

internal fun isInFlightProcessTool(block: AssistantBlock): Boolean =
    block.kind == "tool_use" && block.toolStatus in IN_FLIGHT_PROCESS_TOOL_STATUSES

// ── [T-process-run-card] process-card helpers ──────────────────────────

/** True when the turn already carries reply text (block or legacy content). */
internal fun hasReplyText(message: ChatMessage): Boolean =
    message.content.isNotBlank() ||
        message.toolBlocks.any { it.kind == "text" && it.content.isNotBlank() }

/** Live activity of an assistant turn, for the process-card header. */
internal enum class ProcessPhaseKind { THINKING, TOOL, REPLYING, DONE }

/**
 * The most specific live activity wins: an in-flight tool beats thinking,
 * and a trailing live thinking block (or the await gap after tool results)
 * reads as THINKING; anything else while streaming is the reply text
 * itself. DONE for finished turns.
 */
internal fun processPhaseKind(message: ChatMessage): ProcessPhaseKind {
    if (!message.isStreaming && !message.isAwaitingModelResponse) return ProcessPhaseKind.DONE
    val blocks = message.toolBlocks
    if (blocks.any { it.kind == "tool_use" && it.toolStatus in IN_FLIGHT_PROCESS_TOOL_STATUSES }) {
        return ProcessPhaseKind.TOOL
    }
    if (message.isAwaitingModelResponse) return ProcessPhaseKind.THINKING
    return if (blocks.lastOrNull()?.kind == "thinking") ProcessPhaseKind.THINKING
    else ProcessPhaseKind.REPLYING
}

/** Name of the newest in-flight tool, for the TOOL phase verb. */
internal fun processPhaseToolName(message: ChatMessage): String =
    message.toolBlocks.lastOrNull {
        it.kind == "tool_use" && it.toolStatus in IN_FLIGHT_PROCESS_TOOL_STATUSES
    }?.toolName.orEmpty()

/**
 * Effective expansion of a turn's process card. Manual taps win
 * ([expandedIds] / [collapsedIds]); otherwise auto: expanded while the
 * turn is live and no reply text has arrived, collapsed once reply text
 * streams or the turn finishes.
 */
internal fun effectiveProcessExpanded(
    messageId: String,
    isRunning: Boolean,
    hasReplyText: Boolean,
    expandedIds: Set<String>,
    collapsedIds: Set<String>,
): Boolean = when {
    messageId in expandedIds -> true
    messageId in collapsedIds -> false
    else -> isRunning && !hasReplyText
}

/** Tool-use rows that belong in the detail sheet (completed or live). */
internal fun isDetailProcessTool(block: AssistantBlock): Boolean {
    if (block.toolStatus == null) return false
    if (block.kind == "thinking" || block.kind == "info") return false
    return true
}

internal fun assistantToolUseBlocks(messages: List<ChatMessage>): List<AssistantBlock> =
    messages.asSequence()
        .filter { it.role == "assistant" }
        .flatMap { it.toolBlocks.asSequence() }
        .filter(::isDetailProcessTool)
        .toList()

/**
 * Whether [block] belongs on the floating tool overlay. When
 * [foldAiProcess] is on, completed tools fold into the process card
 * and must not linger as a second "computer" strip — that was why the
 * Appearance switch looked like a no-op.
 *
 * The overlay subset is *not* the source of truth for ToolDetailSheet:
 * completed tools must remain openable after they leave the overlay.
 *
 * [T-process-run-card] [processExpanded] is the EFFECTIVE expansion of
 * this block's turn's process card (auto + manual override — see
 * [effectiveProcessExpanded]). The overlay exists to surface process
 * activity that the card is currently HIDING; once the card is expanded
 * its entries show the running tool at its chronological position, so
 * pinning a second copy of the same running tool to the viewport bottom
 * shows one card in two places that do not correspond to each other.
 * The card wins — it is positionally anchored to the turn.
 */

internal fun isFloatingProcessTool(
    block: AssistantBlock,
    foldAiProcess: Boolean,
    processExpanded: Boolean = false,
): Boolean {
    if (!isDetailProcessTool(block)) return false
    if (foldAiProcess && processExpanded) return false
    if (foldAiProcess && block.toolStatus !in IN_FLIGHT_PROCESS_TOOL_STATUSES) return false
    return true
}

/** [effectiveProcessExpanded] for a whole message — ChatScreen shorthand. */
internal fun processExpandedFor(
    message: ChatMessage,
    expandedIds: Set<String>,
    collapsedIds: Set<String>,
): Boolean = effectiveProcessExpanded(
    messageId = message.id,
    isRunning = message.isStreaming || message.isAwaitingModelResponse,
    hasReplyText = hasReplyText(message),
    expandedIds = expandedIds,
    collapsedIds = collapsedIds,
)

/** Next (expandedIds, collapsedIds) after tapping the card of [item].
 *  [T-process-card-segments] toggles by the SEGMENT id — the tail keeps
 *  the legacy messageId key (existing manual state survives), completed
 *  segments key by their anchor block. */
internal fun nextProcessToggleState(
    item: FlatChatItem.ProcessRunCard,
    expandedIds: Set<String>,
    collapsedIds: Set<String>,
): Pair<Set<String>, Set<String>> {
    val id = item.toggleId
    // (expandedIds, collapsedIds) order — tapping an expanded card moves
    // its id OUT of the expanded set into the collapsed set. Regression:
    // the collapse branch used to return its pair in (collapsed, expanded)
    // order, so a tapped-open card could never close.
    return if (item.expanded) (expandedIds - id) to (collapsedIds + id)
    else (expandedIds + id) to (collapsedIds - id)
}

// ── [T-process-card-segments] segment split ──────────────────────────
//
// A turn is sliced at every NON-EMPTY text block: the process blocks
// (thinking + tool_use, minus always-visible ones) that accumulated
// before each text become their own foldable segment card, the text
// renders below it, and whatever follows the LAST text is the live tail
// segment. This restores the interleaved reading order (text → tool →
// text) that the single-card-per-turn design flattened away.

/** One foldable slice of a turn's process blocks. */
internal class ProcessSegment(
    /** Chronological process blocks owned by this segment. */
    val blocks: List<AssistantBlock>,
    /** First block's id — a stable key anchor (block ids never rewrite). */
    val anchorBlockId: String,
    /** True for the slice after the LAST non-empty text block. */
    val isTail: Boolean,
    /** Non-tail only: the id of the text block that closes this segment. */
    val boundaryTextBlockId: String? = null,
)

/**
 * Splits [blocks] into foldable segments at non-empty text boundaries.
 * Empty text blocks, info rows and always-visible tools neither join a
 * segment nor close one. [forceTail] appends an empty tail segment when
 * no natural tail exists — used when a turn-level error needs a card to
 * ride in (the error row lives in the tail card).
 */
internal fun buildProcessSegments(
    blocks: List<AssistantBlock>,
    forceTail: Boolean = false,
): List<ProcessSegment> {
    val segments = mutableListOf<ProcessSegment>()
    var current = mutableListOf<AssistantBlock>()
    fun flush(boundaryTextId: String?, isTail: Boolean) {
        if (current.isEmpty() && !(isTail && forceTail)) return
        segments.add(ProcessSegment(
            blocks = current.toList(),
            anchorBlockId = current.firstOrNull()?.id.orEmpty(),
            isTail = isTail,
            boundaryTextBlockId = boundaryTextId,
        ))
        current = mutableListOf()
    }
    for (block in blocks) {
        val isProcess = block.kind == "thinking" ||
            (block.kind == "tool_use" && !isAlwaysVisibleProcessTool(block))
        if (isProcess) {
            current.add(block)
        } else if (block.kind == "text" && block.content.isNotEmpty()) {
            flush(boundaryTextId = block.id, isTail = false)
        }
    }
    flush(boundaryTextId = null, isTail = true)
    return segments
}

/**
 * Manual-toggle id for a segment card: the tail keeps the plain
 * messageId (legacy single-card manual state survives the migration);
 * completed segments append their anchor block id.
 */
internal fun processSegmentToggleId(messageId: String, segment: ProcessSegment): String =
    if (segment.isTail) messageId else "$messageId:${segment.anchorBlockId}"

/**
 * [processExpandedFor] re-anchored to the TAIL segment for the floating
 * overlay: in-flight tools always live in the tail, so the overlay only
 * needs to know whether the tail card is hiding them. A turn with no
 * tail segment (reply text is the last block) has nothing in flight to
 * float.
 */
internal fun tailProcessExpandedFor(
    message: ChatMessage,
    expandedIds: Set<String>,
    collapsedIds: Set<String>,
): Boolean {
    val tail = buildProcessSegments(message.toolBlocks).lastOrNull { it.isTail }
        ?: return false
    return effectiveProcessExpanded(
        messageId = processSegmentToggleId(message.id, tail),
        isRunning = message.isStreaming || message.isAwaitingModelResponse,
        // The tail by definition has no reply text after it; the auto rule
        // collapses the OLD single card once text arrived, which here is
        // expressed by the tail becoming empty (no card) instead.
        hasReplyText = false,
        expandedIds = expandedIds,
        collapsedIds = collapsedIds,
    )
}

