package com.openminis.app.ui.chat

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal sealed class FlatChatItem {
    abstract val key: String
    abstract val contentType: String

    /**
     * Defensive-dedupe helper: the same row with `#<n>` appended to the key.
     * The suffix lives ONLY in the key — message ids and `messageId` fields
     * stay clean so key parsing (FlatKeys.parse) always recovers the real
     * id. Before this hook the dedupe rewrote UserBubble.message.id itself,
     * which is what desynced the up-button target resolution.
     */
    abstract fun withKeySuffix(suffix: String): FlatChatItem

    /**
     * Cheap-equals — see [AssistantMarkdownBlock]. User messages are short and
     * don't stream, but during a streaming overlay rebuild we still re-create
     * the entire FlatChatItem list, and LazyColumn calls equals to decide skip.
     * Compare by id + reference identity of the wrapped ChatMessage.
     */
    class UserBubble(
        val message: ChatMessage,
        private val keySuffix: String = "",
    ) : FlatChatItem() {
        override val key = FlatKeys.of(FlatKeys.KIND_USER, message.id) + keySuffix
        override val contentType = "user"
        override fun withKeySuffix(suffix: String): FlatChatItem = UserBubble(message, suffix)
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is UserBubble) return false
            return message == other.message
        }
        override fun hashCode(): Int = message.hashCode()
    }

    data class AssistantHeader(
        val messageId: String,
        val speakerName: String = "",
        val speakerVendor: String = "",
        private val keySuffix: String = "",
    ) : FlatChatItem() {
        override val key = FlatKeys.of(FlatKeys.KIND_HEADER, messageId) + keySuffix
        override val contentType = "header"
        override fun withKeySuffix(suffix: String): FlatChatItem = copy(keySuffix = suffix)
    }

    /**
     * One rendered markdown sub-block (paragraph, code block, list, …) of an
     * assistant reply. Pattern A from the streaming-markdown research: each
     * block is its own LazyColumn item so completed blocks are frozen by
     * LazyList's per-item anchor and only the trailing "live" block
     * re-parses on every chunk. Replaces the previous "whole assistant text
     * block is a single LazyColumn item containing an internal Column of
     * blocks" design which caused the user's scroll position to drift
     * mid-stream.
     *
     * Hand-rolled equals. Historical profile #3 was a full char-by-char
     * compare of `messageMarkdown` — the CONCATENATED markdown of the whole
     * parent message — per fragment per tick. That field still uses a cheap
     * length check because it only feeds Copy Markdown. The rendered fields
     * (`rawText`, `segmentText`) compare by content: length alone skipped
     * same-length replacements (translation / retry), leaving stale text on
     * screen. Each fragment is small, and frozen rows usually share the same
     * String instance, so reference equality short-circuits the common case.
     */
    class AssistantMarkdownBlock(
        val messageId: String,
        val parentBlockId: String,
        val rawText: String,
        val blockIndex: Int,
        val isLastBlockOfMessage: Boolean,
        val messageIsStreaming: Boolean,
        /** Joined raw markdown of the parent message, used by Copy Markdown. */
        val messageMarkdown: String,
        val showTranslate: Boolean = false,
        val segmentText: String = "",
        val translateWholeReply: Boolean = false,
        private val keySuffix: String = "",
    ) : FlatChatItem() {
        override val key = FlatKeys.of(FlatKeys.KIND_MDBLOCK, messageId, parentBlockId, blockIndex) + keySuffix
        override val contentType = "mdblock"
        override fun withKeySuffix(suffix: String): FlatChatItem = AssistantMarkdownBlock(
            messageId = messageId,
            parentBlockId = parentBlockId,
            rawText = rawText,
            blockIndex = blockIndex,
            isLastBlockOfMessage = isLastBlockOfMessage,
            messageIsStreaming = messageIsStreaming,
            messageMarkdown = messageMarkdown,
            showTranslate = showTranslate,
            segmentText = segmentText,
            translateWholeReply = translateWholeReply,
            keySuffix = suffix,
        )
        /** True when this fragment is the streaming tail of a live message. */
        val isStreaming: Boolean get() = messageIsStreaming && isLastBlockOfMessage
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is AssistantMarkdownBlock) return false
            return messageId == other.messageId &&
                parentBlockId == other.parentBlockId &&
                blockIndex == other.blockIndex &&
                isLastBlockOfMessage == other.isLastBlockOfMessage &&
                messageIsStreaming == other.messageIsStreaming &&
                rawText == other.rawText &&
                messageMarkdown.length == other.messageMarkdown.length &&
                showTranslate == other.showTranslate &&
                segmentText == other.segmentText &&
                translateWholeReply == other.translateWholeReply
        }
        override fun hashCode(): Int {
            var h = messageId.hashCode()
            h = h * 31 + parentBlockId.hashCode()
            h = h * 31 + blockIndex
            h = h * 31 + isLastBlockOfMessage.hashCode()
            h = h * 31 + messageIsStreaming.hashCode()
            h = h * 31 + rawText.length
            h = h * 31 + messageMarkdown.length
            h = h * 31 + if (showTranslate) 1 else 0
            h = h * 31 + segmentText.length
            h = h * 31 + if (translateWholeReply) 1 else 0
            return h
        }
    }

    data class AssistantThinking(
        val messageId: String,
        val block: AssistantBlock,
        val isLast: Boolean,
        val messageIsStreaming: Boolean,
        val messageThinkingLevel: com.openminis.app.data.model.ThinkingLevel? = null,
        private val keySuffix: String = "",
    ) : FlatChatItem() {
        override val key = FlatKeys.of(FlatKeys.KIND_THINKING, messageId, block.id) + keySuffix
        override val contentType = "thinking"
        override fun withKeySuffix(suffix: String): FlatChatItem = copy(keySuffix = suffix)
    }

    data class AssistantProcessText(
        val messageId: String,
        val block: AssistantBlock,
        private val keySuffix: String = "",
    ) : FlatChatItem() {
        override val contentType = "processText"
        override val key = FlatKeys.of(contentType, messageId, block.id) + keySuffix
        override fun withKeySuffix(suffix: String): FlatChatItem = copy(keySuffix = suffix)
    }

    /** Collapsed AI process card. */
    data class AssistantProcessSummary(
        val messageId: String,
        val thinkingCount: Int,
        val toolCount: Int,
        val expanded: Boolean,
        val hasFailure: Boolean,
        /** [T-process-summary-duration] Summed block durations (tool + thinking) for the fold-bar
         *  "· 47s" suffix. 0 when no block carried a duration — the bar then omits the suffix. */
        val totalMs: Long = 0L,
        /** [P-process-card] The run's steps, rendered INSIDE the card when expanded. */
        val steps: List<ProcessStepRef> = emptyList(),
        /** True while the run still has a step in flight. */
        val running: Boolean = false,
        /** "thinking" / "tool" / "" — the live header's current state. */
        val activity: String = "",
        val activityTitle: String = "",
        /** Epoch ms the run's first step started; 0 when unknown. */
        val startedAtMs: Long = 0L,
        private val keySuffix: String = "",
    ) : FlatChatItem() {
        override val key = FlatKeys.of(FlatKeys.KIND_PROCESS, messageId) + keySuffix
        override val contentType = "process"
        override fun withKeySuffix(suffix: String): FlatChatItem = copy(keySuffix = suffix)

        // Progress can bring a formerly inline tool into the card. Keep its pill actions.
        val usesToolPill: Boolean get() = thinkingCount == 0 && toolCount == 1
    }

    data class AssistantToolUse(
        val messageId: String,
        val block: AssistantBlock,
        val allToolBlocks: List<AssistantBlock>,
        /** True if this is the last cancelled tool in its message — only one Retry button per message. */
        val isLastCancelled: Boolean = false,
        private val keySuffix: String = "",
    ) : FlatChatItem() {
        override val key = FlatKeys.of(FlatKeys.KIND_TOOL, messageId, block.id) + keySuffix
        override val contentType = "tool"
        override fun withKeySuffix(suffix: String): FlatChatItem = copy(keySuffix = suffix)
    }

    /**
     * [T-android-present-tool] A completed `present` call: the deliverable
     * card (PresentDeliverableCard) replaces the generic tool pill. Reuses
     * KIND_TOOL keys so FlatKeys.parse keeps resolving the real messageId,
     * and the dedupe/selection machinery treats the row like a tool row.
     */
    data class AssistantPresentCard(
        val messageId: String,
        val block: AssistantBlock,
        private val keySuffix: String = "",
    ) : FlatChatItem() {
        override val key = FlatKeys.of(FlatKeys.KIND_TOOL, messageId, block.id) + keySuffix
        override val contentType = "tool"
        override fun withKeySuffix(suffix: String): FlatChatItem = copy(keySuffix = suffix)
    }
    /** [T-port-fold-tools] One folded run of consecutive tool calls (upstream parity). */
    data class AssistantInfo(
        val messageId: String,
        val block: AssistantBlock,
        private val keySuffix: String = "",
    ) : FlatChatItem() {
        override val key = FlatKeys.of(FlatKeys.KIND_INFO, messageId, block.id) + keySuffix
        override val contentType = "info"
        override fun withKeySuffix(suffix: String): FlatChatItem = copy(keySuffix = suffix)
    }

    data class AssistantTyping(val messageId: String, private val keySuffix: String = "") : FlatChatItem() {
        override val key = FlatKeys.of(FlatKeys.KIND_TYPING, messageId) + keySuffix
        override val contentType = "typing"
        override fun withKeySuffix(suffix: String): FlatChatItem = copy(keySuffix = suffix)
    }

    data class AssistantError(
        val messageId: String,
        val error: String,
        private val keySuffix: String = "",
    ) : FlatChatItem() {
        override val key = FlatKeys.of(FlatKeys.KIND_ERROR, messageId) + keySuffix
        override val contentType = "error"
        override fun withKeySuffix(suffix: String): FlatChatItem = copy(keySuffix = suffix)
    }

    /**
     * [T-tool-undo-replay] ZCode-style "changes this turn" card payload:
     * per-path net file changes of one completed assistant message. Paths
     * are aggregated at flat-build time (pure, from tool blocks); checkpoint
     * resolution and the actual restore happen at undo time in the
     * ViewModel (see ChatViewModelUndoExt.kt). Only attached to the LAST
     * completed assistant message.
     */
    data class AssistantTurnChanges(
        val messageId: String,
        val changes: List<TurnChanges.PathChange>,
        private val keySuffix: String = "",
    ) : FlatChatItem() {
        override val key = FlatKeys.of(FlatKeys.KIND_TURN_CHANGES, messageId) + keySuffix
        override val contentType = "turnChanges"
        override fun withKeySuffix(suffix: String): FlatChatItem = copy(keySuffix = suffix)
    }

    /**
     * See [AssistantMarkdownBlock] — same cheap-equals rationale.
     */
    class AssistantLegacyContent(
        val messageId: String,
        val content: String,
        val isStreaming: Boolean,
        /** Same as content here (no separate text-block markdown for legacy rows). */
        val messageMarkdown: String = content,
        private val keySuffix: String = "",
    ) : FlatChatItem() {
        override val key = FlatKeys.of(FlatKeys.KIND_LEGACY, messageId) + keySuffix
        override val contentType = "legacy"
        override fun withKeySuffix(suffix: String): FlatChatItem =
            AssistantLegacyContent(messageId, content, isStreaming, messageMarkdown, suffix)
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is AssistantLegacyContent) return false
            return messageId == other.messageId &&
                isStreaming == other.isStreaming &&
                content == other.content &&
                messageMarkdown == other.messageMarkdown
        }
        override fun hashCode(): Int {
            var h = messageId.hashCode()
            h = h * 31 + isStreaming.hashCode()
            h = h * 31 + content.length
            h = h * 31 + messageMarkdown.length
            return h
        }
    }
}

/** A user turn can span several assistant messages. Its first available anchor keeps card keys stable. */
private class ChatRound(val key: String, val hasCards: Boolean)

/** One stretch of process blocks. A deferred singleton stays inline. */
private class ProcessSegment(
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

/** Fold two or more visible steps. Progress contributes no thinking/tool count. */
private const val SEGMENT_MIN_STEPS = 2

/**
 * [message]'s blocks after the speaker / sub-agent visibility filters.
 * Shared by the round pre-pass and the row loop so the summary bar's counts
 * can never drift from what the rows actually render.
 */
private fun effectiveBlocks(message: ChatMessage, activeSubAgentToolIds: Set<String>): List<AssistantBlock> {
    val hideSpeakerTools = !message.speakerName.isNullOrBlank() &&
        !message.isStreaming && !message.isAwaitingModelResponse
    if (!hideSpeakerTools && activeSubAgentToolIds.isEmpty()) return message.toolBlocks
    // completed sub-agent left no trace anywhere on the session page.
    fun hiddenSubAgent(block: AssistantBlock): Boolean =
        isSubAgentTranscriptCard(block) &&
            (block.id in activeSubAgentToolIds || block.id.substringBefore("#sub-") in activeSubAgentToolIds)
    return message.toolBlocks.filterNot { block ->
        hiddenSubAgent(block) || (hideSpeakerTools && block.kind == "tool_use")
    }
}

private class MessageProcessRows {
    val ownedBlocks = mutableSetOf<Int>()
    val cardsAfterBlock = mutableMapOf<Int, ProcessSegment>()
}

private class RoundSplit(
    private val rounds: List<ChatRound>,
    private val startIndex: Int,
    private val roundOfIdx: IntArray,
    val rowsByMessage: Map<Int, MessageProcessRows>,
    val boundarySegment: ProcessSegment?,
) {
    fun roundAt(idx: Int): ChatRound? = rounds.getOrNull(roundOfIdx.getOrElse(idx - startIndex) { -1 })
}

private fun lastCancelledToolId(blocks: List<AssistantBlock>): String? =
    blocks.lastOrNull { it.kind == "tool_use" && it.toolStatus == ToolBlockStatus.CANCELLED }?.id

/**
 * Rounds are projected from [fromIndex]'s round onward: the frozen prefix
 * build needs every round, a live suffix build only its current one.
 * [processCardBoundary] marks the run crossing the split so exactly one side
 * emits its card (see [buildFlatChatItems]).
 */
private fun splitRounds(
    messages: List<ChatMessage>,
    activeSubAgentToolIds: Set<String>,
    processCardBoundary: Int = -1,
    // Live suffix builds only emit rows at/after fromIndex; re-folding every
    // earlier round per streaming tick was O(transcript) with no output.
    fromIndex: Int = 0,
): RoundSplit {
    val scanStart = if (fromIndex <= 0) 0 else {
        // Start of the round containing fromIndex - 1.
        var i = (fromIndex - 1).coerceIn(0, messages.lastIndex.coerceAtLeast(0))
        while (i > 0 && messages[i].role != "user") i--
        i
    }
    val roundOfIdx = IntArray(messages.size - scanStart) { -1 }
    val rounds = mutableListOf<ChatRound>()
    val rowsByMessage = mutableMapOf<Int, MessageProcessRows>()
    var boundarySegment: ProcessSegment? = null
    var start = scanStart
    var roundUserId: String? = null
    var roundFirstAssistantId: String? = null
    var running = false
    var roundHasCards = false
    // An open run accumulates process steps until a visible reply or the end of the round.
    var segLastMessageIdx = -1
    var segLastBlockIndex = -1
    var segFirstKey = ""
    val segBlocks = mutableMapOf<Int, MutableSet<Int>>()
    val segSteps = mutableListOf<ProcessStepRef>()
    var segHasProgress = false
    var segThinking = 0
    var segTools = 0
    var segFailure = false
    var segMs = 0L
    var segRunning = false
    var segActivity = ""
    var segActivityTitle = ""
    var segStartedAt = 0L

    fun flushSegment(endIdx: Int, settled: Boolean) {
        if (segSteps.isEmpty()) return
        // A divider or empty assistant row can sit between the last step and the
        // split. Only a run that actually closed in the prefix can render there.
        val deferred = processCardBoundary > 0 && endIdx >= processCardBoundary &&
            segLastMessageIdx < processCardBoundary
        val isCard = segSteps.size >= SEGMENT_MIN_STEPS
        if (isCard || deferred) {
            roundHasCards = roundHasCards || isCard
            for ((idx, indices) in segBlocks) {
                rowsByMessage.getOrPut(idx) { MessageProcessRows() }.ownedBlocks.addAll(indices)
            }
            val handOver = deferred && fromIndex == processCardBoundary
            if (handOver || (!deferred && segLastMessageIdx >= fromIndex)) {
                val segment = ProcessSegment(
                    lastMessageIdx = segLastMessageIdx,
                    firstStepKey = segFirstKey,
                    isCard = isCard,
                    thinkingCount = segThinking,
                    toolCount = segTools,
                    hasFailure = segFailure,
                    totalMs = segMs,
                    steps = segSteps.toList(),
                    running = !settled && (segRunning || running),
                    activity = segActivity,
                    activityTitle = segActivityTitle,
                    startedAtMs = segStartedAt,
                )
                if (handOver) boundarySegment = segment
                else rowsByMessage.getValue(segLastMessageIdx).cardsAfterBlock[segLastBlockIndex] = segment
            }
        }
        segLastMessageIdx = -1; segLastBlockIndex = -1; segFirstKey = ""
        segBlocks.clear(); segSteps.clear()
        segHasProgress = false; segThinking = 0; segTools = 0; segFailure = false; segMs = 0L
        segRunning = false; segActivity = ""; segActivityTitle = ""; segStartedAt = 0L
    }

    fun closeRound(endExclusive: Int) {
        flushSegment(endExclusive, settled = false)
        val firstAssistant = roundFirstAssistantId ?: return
        val roundIdx = rounds.size
        rounds.add(ChatRound(roundUserId ?: firstAssistant, roundHasCards))
        for (i in start until endExclusive) roundOfIdx[i - scanStart] = roundIdx
    }

    fun resetRound(newStart: Int) {
        start = newStart
        roundUserId = null
        roundFirstAssistantId = null
        running = false
        roundHasCards = false
    }

    for (idx in scanStart until messages.size) {
        val raw = messages[idx]
        if (raw.role == "user") {
            closeRound(idx)
            resetRound(idx)
            roundUserId = raw.id
        }
        if (raw.role == "assistant") {
            if (roundFirstAssistantId == null) roundFirstAssistantId = raw.id
            if (raw.isStreaming || raw.isAwaitingModelResponse) running = true
            val blocks = effectiveBlocks(raw, activeSubAgentToolIds)
            var lastCancelledId: String? = null
            for ((blockIndex, block) in blocks.withIndex()) {
                val isThinking = block.kind == "thinking"
                val isVisibleTool = isAlwaysVisibleProcessTool(block)
                val isTool = block.kind == "tool_use" && !isVisibleTool
                val isProgress = block.isText && block.isProcessText && block.content.isNotBlank()
                if ((block.isReplyText && block.content.isNotBlank()) || (isVisibleTool && segHasProgress)) {
                    flushSegment(idx, settled = true)
                }
                if (!isThinking && !isTool && !isProgress) continue

                if (segSteps.isEmpty()) {
                    // Generated text ids can repeat in a new assistant message within the same round.
                    segFirstKey = if (isProgress) "${raw.id}--${block.id}" else block.id
                }
                segLastMessageIdx = idx
                segLastBlockIndex = blockIndex
                if (idx >= fromIndex) segBlocks.getOrPut(idx) { mutableSetOf() }.add(blockIndex)
                segHasProgress = segHasProgress || isProgress
                if (isThinking || isTool) {
                    segMs += block.durationMs
                    if (segStartedAt == 0L && block.startTimeMs > 0L) segStartedAt = block.startTimeMs
                }
                val stepRunning = !isProgress && (block.toolStatus in IN_FLIGHT_PROCESS_TOOL_STATUSES ||
                    (isThinking && raw.isStreaming && blockIndex == blocks.lastIndex))
                val displayTitle = if (isTool) displayToolTitle(block.toolName, block.toolArgs, block.toolTitle) else ""
                if (stepRunning) {
                    segRunning = true
                    segActivity = if (isThinking) "thinking" else "tool"
                    segActivityTitle = displayTitle
                }
                if (isThinking) segThinking++
                if (isTool) {
                    segTools++
                    if (block.toolStatus == ToolBlockStatus.FAILED) segFailure = true
                    if (block.toolStatus == ToolBlockStatus.CANCELLED && lastCancelledId == null) {
                        lastCancelledId = lastCancelledToolId(blocks)
                    }
                }
                segSteps += ProcessStepRef(
                    id = block.id,
                    kind = block.kind,
                    title = if (isTool) displayTitle.ifEmpty { "tool" } else "",
                    status = block.toolStatus.takeIf { isTool },
                    durationMs = if (isThinking || isTool) block.durationMs else 0L,
                    running = stepRunning,
                    block = block,
                    messageId = raw.id,
                    isLastCancelled = isTool && block.id == lastCancelledId,
                )
            }
        }
    }
    closeRound(messages.size)
    return RoundSplit(rounds, scanStart, roundOfIdx, rowsByMessage, boundarySegment)
}

internal fun buildFlatChatItems(
    messages: List<ChatMessage>,
    // [T-android-perf-logging] Optional — when supplied, emit a progress
    // breadcrumb every 100 messages so a low-memory repro shows which batch
    // drives the heap up. Null (default) skips all progress logging, so the
    // hot per-token streaming rebuild path stays log-free.
    sessionId: String? = null,
    // [T-android-stream-pipeline-incremental] Build rows only for
    // messages[fromIndex, size). Neighbor lookbacks (isResumeContinuation)
    // still read the FULL list, so a suffix built with fromIndex > 0 is
    // row-for-row identical to the same span of a full build — rows never
    // depend on later messages, only earlier ones. (Round fold state is the
    // one deliberate exception on the LIVE side: a running round keeps its
    // earlier messages open too, and the frozen prefix rebuild that fires
    // whenever the canonical list changes re-syncs them.)
    fromIndex: Int = 0,
    // Dedupe continuity for split builds: pass the key set of the frozen
    // prefix so the defensive key-collision suffixing behaves exactly as a
    // single full build would.
    seedKeys: Set<String> = emptySet(),
    showCompletedToolCards: Boolean = true,
    foldAiProcess: Boolean = false,
    // [T-android-round-collapse] Card keys — `"<stable round anchor>--<first
    // step id>"` (see [ChatRound.key]). The toggle in ChatScreen adds/removes
    // bar.messageId, which IS that card key, never a bare message id: one
    // message can hold several cards.
    expandedProcessIds: Set<String> = emptySet(),
    activeSubAgentToolIds: Set<String> = emptySet(),
    // [P-process-card-dup] The frozen prefix hands its still-open run to the live suffix.
    processCardBoundary: Int = -1,
    keepFragmentShapeIds: Set<String> = emptySet(), // [T-reading-viewport-yank] see keepFragmentsFor
): List<FlatChatItem> {
    if (fromIndex >= messages.size) return emptyList()
    val out = mutableListOf<FlatChatItem>()
    // Frozen keys are a read-only membership check, not a set to copy per tick.
    val usedKeys = mutableSetOf<String>()
    val roundSplit = if (foldAiProcess) {
        splitRounds(messages, activeSubAgentToolIds, processCardBoundary, fromIndex)
    } else null
    val lastAssistantIdx = (messages.lastIndex downTo fromIndex).firstOrNull { messages[it].role == "assistant" } ?: -1
    fun dedupe(item: FlatChatItem): FlatChatItem {
        // Defensive: duplicated keys crash LazyColumn. If any slip through, suffix
        // a counter until unique. This should never fire if upstream dedup is
        // correct. The suffix is appended to the KEY only — never to
        // message.id / messageId (that used to desync every key consumer; see
        // FlatKeys KDoc).
        if (item.key !in seedKeys && usedKeys.add(item.key)) return item
        var n = 2
        while (true) {
            val suffix = "#$n"
            val key = item.key + suffix
            if (key !in seedKeys && usedKeys.add(key)) return item.withKeySuffix(suffix)
            n++
        }
    }
    // The card row for one process run. Both emission sites (the run's own
    // last block, and the boundary hand-over below) build it here so the two
    // sides can never disagree about the key or the counts.
    fun processCard(round: ChatRound, segment: ProcessSegment): FlatChatItem {
        // The card's own key, so collapsing one card never touches the next
        // one — manual collapse suppresses auto-expand for THIS card only.
        // [T-flatkeys-invariants] The composite lands in FlatKeys.of's messageId slot
        // (':'/'#' rejected) — escape parts INJECTIVELY: a lossy '-' rewrite coupled
        // "grep:121"/"grep-121" fold state; '%' goes FIRST, the key is never decoded.
        val cardKey = "${round.key}--${segment.firstStepKey}".replace("%", "%25").replace(":", "%3A").replace("#", "%23")
        return FlatChatItem.AssistantProcessSummary(
            messageId = cardKey,
            thinkingCount = segment.thinkingCount,
            toolCount = segment.toolCount,
            expanded = cardKey in expandedProcessIds,
            hasFailure = segment.hasFailure,
            totalMs = segment.totalMs,
            steps = segment.steps,
            running = segment.running,
            activity = segment.activity,
            activityTitle = segment.activityTitle,
            startedAtMs = segment.startedAtMs,
        )
    }
    if (fromIndex < messages.size && roundSplit != null) roundSplit.boundarySegment?.let { segment ->
        val row = if (segment.isCard) {
            processCard(checkNotNull(roundSplit.roundAt(segment.lastMessageIdx)), segment)
        } else {
            val step = segment.steps.single()
            val message = messages[segment.lastMessageIdx]
            when (step.kind) {
                "text" -> FlatChatItem.AssistantProcessText(step.messageId, step.block)
                "thinking" -> FlatChatItem.AssistantThinking(
                    messageId = step.messageId,
                    block = step.block,
                    isLast = true,
                    messageIsStreaming = message.isStreaming,
                    messageThinkingLevel = message.thinkingLevel,
                )
                else -> FlatChatItem.AssistantToolUse(
                    messageId = step.messageId,
                    block = step.block,
                    allToolBlocks = effectiveBlocks(message, activeSubAgentToolIds).filter { it.kind == "tool_use" },
                    isLastCancelled = step.isLastCancelled,
                )
            }
        }
        out.add(dedupe(row))
    }
    var prevNonSystem = (fromIndex - 1 downTo 0).firstNotNullOfOrNull { idx ->
        messages[idx].takeUnless { it.role == "system" }
    }
    for (idx in fromIndex until messages.size) {
        val rawMessage = messages[idx]
        // Speaker / sub-agent visibility filters live in [effectiveBlocks] so
        // the round pre-pass and this row loop can never disagree about what
        // counts as a foldable step (the bar's totals must match the rows).
        val effBlocks = effectiveBlocks(rawMessage, activeSubAgentToolIds)
        val message = if (effBlocks.size == rawMessage.toolBlocks.size) rawMessage
        else rawMessage.copy(toolBlocks = effBlocks)
        // [T-android-perf-logging] Per-100-message progress breadcrumb.
        // `out.size` is the running row count, so a sudden jump between two
        // progress lines localizes the heavy batch. Only fires on the
        // full-build path (sessionId != null), never per streaming token.
        if (sessionId != null && idx > 0 && idx % 100 == 0) {
            com.openminis.app.diagnostics.PerfLongCtx.step(
                sessionId,
                "buildFlatChatItems.progress",
                "msgIdx=$idx of=${messages.size} rowsSoFar=${out.size}",
            )
        }
        if (message.role == "user") {
            // [T-android-candidate-bubble-gap] Flag when the previous message
            // is also a user message so the bubble can add a separating top
            // gap — back-to-back candidate / queued sends otherwise have no
            // AssistantHeader between them and visually merge.
            out.add(dedupe(FlatChatItem.UserBubble(message)))
            prevNonSystem = message
            continue
        }
        // System messages (slash-command notices, compact divider, etc.) render
        // as horizontal-divider rows — no "Minis Ultra" attribution, no card. Skip
        // the assistant header so each info block stands on its own. Mirrors
        // iOS systemDividerRow / compactDividerRow.
        val isSystem = message.role == "system"
        // T83: when Resume creates a fresh assistant bubble after the user
        // stopped a streaming turn, the previous (cancelled) assistant
        // message is right before this one in the list. Visually they should
        // read as one continuous turn — suppress the duplicate "Minis Ultra"
        // header. Skip system rows when looking back since they render as
        // dividers, not as separate speaker turns. iOS achieves this by
        // reusing the existing ChatMessage in runAgentLoop(resumingAt:);
        // we reach the same end-result at the render layer.
        // Resume reuses the previous assistant turn. A different group-chat
        // speaker still gets their own header.
        val prevSpeaker = prevNonSystem?.speakerName?.trim().orEmpty()
        val thisSpeaker = message.speakerName?.trim().orEmpty()
        val isResumeContinuation = prevNonSystem?.role == "assistant" && prevSpeaker == thisSpeaker
        if (!isSystem) prevNonSystem = message

        val blocks = message.toolBlocks
        val toolPillBlocks = blocks.filter { it.kind == "tool_use" }
        val lastThinkingId = blocks.lastOrNull { it.kind == "thinking" }?.id
        val lastTextIdx = blocks.indexOfLast { it.isReplyText && it.content.isNotBlank() }
        val legacyContent = if (blocks.any { it.isText }) "" else message.content
        // Reuse a lone reply block's String. Only multi-block replies need a join,
        // shared by all fragments; provisional text is never part of that join.
        val replyText = when {
            lastTextIdx < 0 -> ""
            blocks.indexOfFirst { it.isReplyText && it.content.isNotBlank() } == lastTextIdx -> blocks[lastTextIdx].content
            else -> AssistantReplyText.joined(blocks)
        }
        // Only the last cancelled tool gets the full-turn Retry button.
        val cancelledToolId = lastCancelledToolId(toolPillBlocks)
        val round = if (isSystem) null else roundSplit?.roundAt(idx)
        val processRows = roundSplit?.rowsByMessage?.get(idx)
        val ownedBlocks = processRows?.ownedBlocks.orEmpty()
        val roundFolded = round?.hasCards == true
        // [T-android-fold-toolonly-blank] A turn whose entire visible payload
        // is completed thinking/tool cards collapses into the process summary.
        // Emitting the AssistantHeader row above an otherwise empty turn left
        // header padding + spacedBy(2.dp) rendered as a large blank band in the
        // reverse-layout list. Compute whether anything besides the summary
        // will actually render and skip the header when it will not.
        val headerHiddenByFold = roundFolded && legacyContent.isBlank() && message.error == null &&
            blocks.withIndex().none { (index, block) ->
                when (block.kind) {
                    "text" -> if (block.isProcessText) {
                        block.content.isNotBlank() && index !in ownedBlocks
                    } else block.content.isNotEmpty()
                    "info" -> true
                    "thinking" -> index !in ownedBlocks
                    "tool_use" -> index !in ownedBlocks &&
                        shouldShowProcessToolRow(block, showCompletedToolCards, foldAiProcess)
                    else -> false
                }
            }
        if (!isSystem && !isResumeContinuation && !headerHiddenByFold) {
            out.add(dedupe(FlatChatItem.AssistantHeader(
                message.id,
                message.speakerName.orEmpty(),
                message.speakerVendor.orEmpty(),
            )))
        }
        blocks.forEachIndexed { index, block ->
            when (block.kind) {
                "text" -> {
                    if (block.isProcessText) {
                        if (block.content.isNotBlank() && index !in ownedBlocks) {
                            out.add(dedupe(FlatChatItem.AssistantProcessText(message.id, block)))
                        }
                    } else if (block.content.isNotEmpty()) {
                        val isLastText = index == lastTextIdx
                        // Pattern A: split this text block's content into
                        // independent markdown fragments so each becomes its
                        // own LazyColumn item. Frozen prefix fragments are
                        // anchored separately by LazyList; only the trailing
                        // live fragment can change height during streaming.
                        //
                        // [T-android-defensive-fragment-merge] For a FROZEN
                        // (non-streaming) message, coalesce adjacent
                        // plain-text fragments so a long reply produces a
                        // handful of rows instead of dozens — cuts cold-open
                        // full-build row count ~8x and eases GC pressure on
                        // low-memory devices. The live streaming tail message
                        // keeps fine-grained fragments so only the trailing
                        // paragraph re-parses per token (Pattern A jank
                        // optimization preserved). Code fences stay standalone
                        // either way.
                        val rawFragments = splitMarkdownIntoBlockTexts(block.content)
                        // [T-android-stream-end-reflow-flicker-v18] Preserve
                        // per-fragment FlatChatItem keys across the
                        // streaming→idle boundary. Previously the trailing
                        // text block kept rawFragments only while
                        // `message.isStreaming==true`; the moment it flipped
                        // false the fragments coalesced into fewer rows, all
                        // mdblock:msgId:parentBlockId:N keys for N >= K
                        // suddenly vanished from the flatItems list — the
                        // "整个页面像被重刷" the user reported: LazyColumn
                        // lost every viewport anchor, fell back to numeric
                        // firstVisibleItemIndex, and parked the viewport on
                        // whatever row took that numeric slot.
                        //
                        // Fix: keep the live (== last in the list) text block
                        // on rawFragments regardless of isStreaming; the
                        // boundary that warrants coalesce is "a NEWER
                        // message exists below this one". Until then the key
                        // set the user is scrolled into stays valid.
                        val fragments = if (isLastText && keepFragmentsFor(idx >= lastAssistantIdx, message.id, keepFragmentShapeIds)) rawFragments else coalesceMarkdownFragments(rawFragments)
                        if (fragments.isEmpty()) {
                            // Defensive: if the splitter returns nothing for
                            // a non-empty input (shouldn't happen), fall back
                            // to a single fragment so content isn't dropped.
                            out.add(dedupe(FlatChatItem.AssistantMarkdownBlock(
                                messageId = message.id,
                                parentBlockId = block.id,
                                rawText = block.content,
                                blockIndex = 0,
                                isLastBlockOfMessage = isLastText && message.isStreaming,
                                messageIsStreaming = message.isStreaming && isLastText,
                                messageMarkdown = replyText,
                                showTranslate = !message.isStreaming && block.content.isNotBlank() && (!roundFolded || isLastText),
                                segmentText = if (roundFolded && isLastText) replyText else block.content,
                                translateWholeReply = roundFolded && isLastText && !message.isStreaming && block.content.isNotBlank(),
                            )))
                        } else {
                            fragments.forEachIndexed { fragIdx, raw ->
                                val isLastFragOfText = fragIdx == fragments.lastIndex
                                out.add(dedupe(FlatChatItem.AssistantMarkdownBlock(
                                    messageId = message.id,
                                    parentBlockId = block.id,
                                    rawText = raw,
                                    blockIndex = fragIdx,
                                    isLastBlockOfMessage = isLastText && isLastFragOfText,
                                    messageIsStreaming = message.isStreaming && isLastText,
                                    messageMarkdown = replyText,
                                    showTranslate = isLastFragOfText && !message.isStreaming && block.content.isNotBlank() && (!roundFolded || isLastText),
                                    segmentText = if (roundFolded && isLastText && isLastFragOfText) replyText
                                        else if (isLastFragOfText) block.content else "",
                                    translateWholeReply = roundFolded && isLastText && isLastFragOfText && !message.isStreaming && block.content.isNotBlank(),
                                )))
                            }
                        }
                    }
                }
                "thinking" -> {
                    // [P-process-card-single-step] Block-level gate: a
                    // thinking block not owned by any card renders inline,
                    // whether or not sibling runs in the round carded.
                    if (index !in ownedBlocks) {
                        out.add(dedupe(FlatChatItem.AssistantThinking(
                            messageId = message.id,
                            block = block,
                            isLast = block.id == lastThinkingId,
                            messageIsStreaming = message.isStreaming && !hasReplyTextAfter(blocks, index),
                            messageThinkingLevel = message.thinkingLevel,
                        )))
                    }
                }
                "info" -> out.add(dedupe(FlatChatItem.AssistantInfo(
                    messageId = message.id,
                    block = block,
                )))
                // [T-android-present-tool] A completed present call renders
                // as its deliverable card, not a tool pill. present is in
                // isAlwaysVisibleProcessTool, so it never lands inside
                // foldedBlockIds and always reaches this first arm.
                else -> when {
                    isPresentDeliverable(block) -> out.add(dedupe(FlatChatItem.AssistantPresentCard(
                        messageId = message.id,
                        block = block,
                    )))
                    index !in ownedBlocks && shouldShowProcessToolRow(
                        block,
                        showCompletedToolCards = showCompletedToolCards,
                        foldAiProcess = foldAiProcess,
                    ) -> out.add(dedupe(FlatChatItem.AssistantToolUse(
                        messageId = message.id,
                        block = block,
                        allToolBlocks = toolPillBlocks,
                        isLastCancelled = block.id == cancelledToolId,
                    )))
                }
            }
            // Put the card just after the last step and before the reply.
            if (round != null) processRows?.cardsAfterBlock?.get(index)?.let { segment ->
                out.add(dedupe(processCard(round, segment)))
            }
        }

        // Typing indicator: show while streaming and either (a) no visible
        // content has arrived yet, or (b) we're in the network gap waiting
        // on the model's next response (e.g. after tool results were sent
        // back). Mirrors iOS: `isActiveMessage && (!hasVisibleContent || isAwaitingModelResponse)`.
        //
        // [T-android-typing-hidden-thinking] "Visible" must mean WILL RENDER,
        // not merely "exists". Two blocks used to count as content while
        // drawing nothing, killing the indicator into a blank gap ("thinking
        // 还没等到内容出现就消失了"):
        //  - a "thinking" block when the message's thinking level is OFF —
        //    forced-reasoning models (MiniMax M2, Grok…) still stream
        //    reasoning_content, but ChatScreen suppresses the row when the
        //    snapshot level is disabled (T300), so the user saw nothing;
        //  - a freshly-created "text" block whose content is still empty.
        // A null thinkingLevel snapshot falls back to the chat's CURRENT
        // level in the renderer, which this pure builder can't read — treat
        // it as visible (legacy messages; conservative, old behavior).
        val hasVisibleContent = legacyContent.isNotEmpty() || blocks.any {
            when (it.kind) {
                "info" -> false
                // [T-android-thinking-forced-visible] A thinking block with
                // content renders (forced-reasoning models stream reasoning
                // even when the request level is OFF), so it counts as
                // visible content for the typing indicator. Empty block =
                // still waiting, same as before.
                "thinking" -> it.content.isNotBlank()
                "text" -> if (it.isProcessText) it.content.isNotBlank() else it.content.isNotEmpty()
                else -> true // tool_use pills render immediately
            }
        }
        if (message.isStreaming && (!hasVisibleContent || message.isAwaitingModelResponse)) {
            out.add(dedupe(FlatChatItem.AssistantTyping(message.id)))
        }

        // Legacy messages store their reply in content, without any text blocks.
        if (legacyContent.isNotEmpty()) {
            out.add(dedupe(FlatChatItem.AssistantLegacyContent(message.id, legacyContent, message.isStreaming)))
        }

        // Inline error banner — [T-stale-sticker] tail assistant row only.
        message.error?.takeIf { idx == lastAssistantIdx }?.let {
            out.add(dedupe(FlatChatItem.AssistantError(message.id, it)))
        }

        // [T-tool-undo-replay] ZCode-style "changes this turn" card on the
        // LAST completed assistant message only: earlier turns' snapshots
        // are stale relative to later edits, and undoing an old turn now
        // would clobber newer work. Streaming rows don't get the card —
        // mutating files mid-turn corrupts agent state. Aggregation is
        // pure (tool blocks only); checkpoint resolution is deferred to
        // undo time in the ViewModel.
        if (!message.isStreaming && idx == lastAssistantIdx) {
            val changes = TurnChanges.collect(message.toolBlocks)
            if (changes.isNotEmpty()) {
                out.add(dedupe(FlatChatItem.AssistantTurnChanges(message.id, changes)))
            }
        }
    }
    return out
}

// ── [P1-3] Row rhythm ───────────────────────────────────────────────────
//
// Vertical spacing between list rows, replacing the flat LazyColumn
// spacedBy(2.dp) plus per-row ad-hoc paddings. flatItems is oldest-first and
// rendered via asReversed() under reverseLayout, so the row at index p-1
// renders directly ABOVE the row at index p — the "top gap" of row p is the
// visual gap between p-1 and p, and it is applied as top padding on row p.
//
// Rhythm (taixu ChatMessageList-derived, adapted to our row kinds):
//  - above a user bubble: 12dp turn boundary (16dp between back-to-back
//    user bubbles — candidate/queued pairs, T-android-candidate-bubble-gap);
//  - above an assistant header: 16dp after a user bubble
//    (T-android-user-assistant-spacing-16), else 12dp;
//  - process row → process row: 2.5dp (dense activity cluster);
//  - process row → reply text: 4dp (process hands off to the answer);
//  - header → first row of its turn: 4dp;
//  - md fragment → md fragment of the SAME message: 2dp (paragraph flow);
//  - everything else: 8dp (default breath).

internal val ChatRowDefaultGap: Dp = 8.dp

internal fun isProcessRow(item: FlatChatItem): Boolean =
    item is FlatChatItem.AssistantThinking ||
        item is FlatChatItem.AssistantToolUse ||
        item is FlatChatItem.AssistantProcessSummary ||
        item is FlatChatItem.AssistantProcessText

private fun isTextRow(item: FlatChatItem): Boolean =
    item is FlatChatItem.AssistantMarkdownBlock

internal fun flatItemTopGap(prev: FlatChatItem?, cur: FlatChatItem): Dp = when {
    cur is FlatChatItem.UserBubble ->
        if (prev is FlatChatItem.UserBubble) 16.dp else 12.dp
    cur is FlatChatItem.AssistantHeader ->
        if (prev is FlatChatItem.UserBubble) 16.dp else 12.dp
    prev == null -> 0.dp
    isTextRow(cur) && isTextRow(prev) &&
        (prev as? FlatChatItem.AssistantMarkdownBlock)?.messageId ==
        (cur as? FlatChatItem.AssistantMarkdownBlock)?.messageId -> 2.dp
    isProcessRow(prev) && isProcessRow(cur) -> 2.5.dp
    isProcessRow(prev) && isTextRow(cur) -> 4.dp
    prev is FlatChatItem.AssistantHeader -> 4.dp
    else -> ChatRowDefaultGap
}

/**
 * [T-android-thinking-stable-expand] True when a readable text block sits
 * after [idx] — the reply has started, so this block's reasoning phase is
 * over. A tool_use does NOT count: in a multi-round tool loop the next
 * round's thinking follows it. Blank text ("\n\n" before a tool call) is
 * not reply text, matching flushSegment's isNotBlank() contract.
 */
internal fun hasReplyTextAfter(blocks: List<AssistantBlock>, idx: Int): Boolean {
    for (i in idx + 1 until blocks.size) {
        val b = blocks[i]
        if (b.isReplyText && b.content.isNotBlank()) return true
    }
    return false
}
