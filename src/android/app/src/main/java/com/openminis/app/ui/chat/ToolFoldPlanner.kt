package com.openminis.app.ui.chat

// [port-upstream-1.16] Ported from OpenMinis 1.16-beta (adapted from the iOS ToolFoldPlanner; logic re-expressed for this codebase).

/**
 * [T-port-fold-tools] Decides which runs of consecutive tool calls in one
 * assistant message the chat list folds into a single "Ran N tools · 9s" row.
 * Pure: it sees only a compact description of each block, so the rule is
 * unit-tested without the list. Port of iOS `ToolFoldPlanner` (b42f64364,
 * 55a66303b, 1b5d69c60, fe1500201, e5651c84f, 2f59b72ee), final state.
 *
 * A run starts at the first tool block and continues through
 * - more finished tool blocks (counted),
 * - thinking blocks (folded along, not counted),
 * - empty text blocks (a text block that has not received content yet),
 * - one-line remarks between tool calls ([Input.NOTE], see [markingNotes]):
 *   folded along, not counted.
 *
 * It ends at a non-empty text block, an info block, a sub agent / user_ask
 * block, or a tool that has not finished. Only a run of at least
 * [MINIMUM_TOOL_COUNT] tools that ends at a NON-EMPTY text block folds. A fold
 * covers the first tool through the last tool (or note) of the run.
 *
 * Thinking right before the run's first tool (only empty text between them)
 * folds with the run too. Thinking after the last member stays outside, next
 * to the reply it leads into.
 *
 * A note is also a soft end: when the run is then cut by an unfinished tool, a
 * barrier or the end of the message, the part up to its latest note still
 * folds.
 */
object ToolFoldPlanner {
    /** Tools whose result IS the deliverable: folding them would hide it. */
    private val SUB_AGENT_BARRIER_TOOLS = setOf(
        "spawn_agent",
        "run_subagent",
        "dispatch_agents",
        com.openminis.app.tools.WolfpackTool.NAME,
    )

    /** The minimum number of tool blocks in a run before it folds. */
    const val MINIMUM_TOOL_COUNT = 2

    /**
     * The fewest lines a request's reply needs for the end of that request to
     * fold the tool runs before it.
     */
    const val SEPARATOR_MINIMUM_LINES = 2

    enum class ToolOutcome { UNFINISHED, SUCCEEDED, FAILED, CANCELLED }

    /** What the planner needs to know about one block. */
    sealed class Input {
        data class Tool(val outcome: ToolOutcome) : Input()
        object Thinking : Input() { override fun toString() = "Thinking" }
        data class Text(val isEmpty: Boolean) : Input()
        /** A one-line text with a tool call after it - a remark, not the reply. */
        object Note : Input() { override fun toString() = "Note" }
        /** Always visible and ends a run: info rows, sub agent and user_ask blocks. */
        object Barrier : Input() { override fun toString() = "Barrier" }
    }

    data class Group(
        /** Indices of the folded blocks, first member through last member. */
        val range: IntRange,
        val toolCount: Int,
        val failedCount: Int,
        val cancelledCount: Int,
    )

    fun plan(blocks: List<Input>, enabled: Boolean): List<Group> {
        if (!enabled) return emptyList()
        val groups = mutableListOf<Group>()
        var start: Int? = null
        var leadThinking: Int? = null
        var lastMember = 0
        var tools = 0
        var failed = 0
        var cancelled = 0
        var softEnd: Group? = null

        fun reset() {
            start = null
            tools = 0; failed = 0; cancelled = 0
            softEnd = null
        }
        fun closeAtSoftEnd() {
            softEnd?.let { if (it.toolCount >= MINIMUM_TOOL_COUNT) groups.add(it) }
        }

        for ((i, block) in blocks.withIndex()) {
            when (block) {
                is Input.Barrier -> {
                    closeAtSoftEnd(); reset(); leadThinking = null
                }
                is Input.Tool -> {
                    if (block.outcome == ToolOutcome.UNFINISHED) {
                        closeAtSoftEnd(); reset(); leadThinking = null
                        continue
                    }
                    if (start == null) start = leadThinking ?: i
                    leadThinking = null
                    lastMember = i
                    tools += 1
                    if (block.outcome == ToolOutcome.FAILED) failed += 1
                    if (block.outcome == ToolOutcome.CANCELLED) cancelled += 1
                }
                is Input.Note -> {
                    // Only after a tool: a remark before the first call is not
                    // part of any run, and it separates thinking before it
                    // from the call after it.
                    val s = start
                    if (s == null) {
                        leadThinking = null
                        continue
                    }
                    lastMember = i
                    softEnd = Group(s..i, tools, failed, cancelled)
                }
                is Input.Thinking -> if (start == null && leadThinking == null) leadThinking = i
                is Input.Text -> {
                    if (block.isEmpty) continue
                    val s = start
                    if (s != null && tools >= MINIMUM_TOOL_COUNT) {
                        groups.add(Group(s..lastMember, tools, failed, cancelled))
                    }
                    reset()
                    leadThinking = null
                }
            }
        }
        // A run still open here has no reply after it: only its part up to the
        // latest note folds.
        closeAtSoftEnd()
        return groups
    }

    /**
     * Marks the one-line remarks a model writes between tool calls as
     * [Input.NOTE]: a non-empty text is a note when [isSingleLine] holds AND a
     * tool call comes after it. Everything after the last tool call is the
     * reply and is never a note.
     */
    fun markingNotes(inputs: List<Input>, isSingleLine: (Int) -> Boolean): List<Input> {
        val lastTool = inputs.indexOfLast { it is Input.Tool }
        if (lastTool < 0) return inputs
        val out = inputs.toMutableList()
        for (i in 0 until lastTool) {
            if (out[i] == Input.Text(isEmpty = false) && isSingleLine(i)) out[i] = Input.Note
        }
        return out
    }

    /** One line by line BREAKS: no newline between the first and last visible character. */
    fun isSingleLine(text: String): Boolean {
        val first = text.indexOfFirst { !it.isWhitespace() }
        if (first < 0) return true
        val last = text.indexOfLast { !it.isWhitespace() }
        for (i in first..last) if (text[i] == '\n' || text[i] == '\r') return false
        return true
    }

    private fun lines(text: String): Sequence<String> = text.splitToSequence("\r\n", "\n", "\r")

    /** Lines with at least one visible character. */
    fun nonEmptyLineCount(text: String): Int = lines(text).count { line -> line.any { !it.isWhitespace() } }

    /**
     * Whether a request whose reply text (all its text blocks, joined) is
     * [requestText] folds the runs before it when it ends.
     */
    fun isFoldSeparator(requestText: String): Boolean {
        var n = 0
        for (line in lines(requestText)) {
            if (line.any { !it.isWhitespace() }) {
                n += 1
                if (n >= SEPARATOR_MINIMUM_LINES) return true
            }
        }
        return false
    }

    /**
     * For a message whose task is still running: how many leading blocks may
     * be planned - through the last block of the latest separator request; 0
     * before the first one. Ids no longer in the message are ignored.
     */
    fun <ID> foldHorizon(blockIds: List<ID>, separators: Set<ID>): Int {
        if (separators.isEmpty()) return 0
        val last = blockIds.indexOfLast { it in separators }
        return if (last < 0) 0 else last + 1
    }

    /**
     * Whether a run that is due to fold is held back for now because folding
     * it would move what the user is reading: only while they are scrolled up,
     * never for a run already folded once, and only when it belongs to the
     * running message or its blocks are on the list as separate rows right
     * now. [shownAsRows] is only called when the cheaper conditions leave the
     * answer open.
     */
    fun holdsBack(
        browsing: Boolean,
        alreadyFolded: Boolean,
        inRunningMessage: Boolean,
        shownAsRows: () -> Boolean,
    ): Boolean {
        if (!browsing || alreadyFolded) return false
        return inRunningMessage || shownAsRows()
    }

    // --- AssistantBlock adapters ---------------------------------------------

    /** The planner's view of one block. */
    fun input(block: AssistantBlock): Input = when (block.kind) {
        "text" -> Input.Text(isEmpty = block.content.none { !it.isWhitespace() })
        "thinking" -> Input.Thinking
        "info" -> Input.Barrier
        else -> when {
            // iOS: delegate and user_ask blocks are never folded.
            // [T-port-fold-tools] Upstream asks its own helper/userask registries;
            // this tree keeps the same "never fold" set locally.
            block.toolName in SUB_AGENT_BARRIER_TOOLS -> Input.Barrier
            block.toolName == com.openminis.app.tools.AskUserQuestion.NAME -> Input.Barrier
            else -> when (block.toolStatus) {
                ToolBlockStatus.SUCCESS -> Input.Tool(ToolOutcome.SUCCEEDED)
                // A timed-out call ended in failure; the capsule shows it as such.
                ToolBlockStatus.FAILED, ToolBlockStatus.TIMEOUT -> Input.Tool(ToolOutcome.FAILED)
                ToolBlockStatus.CANCELLED -> Input.Tool(ToolOutcome.CANCELLED)
                ToolBlockStatus.STREAMING, ToolBlockStatus.PENDING, ToolBlockStatus.RUNNING, null ->
                    Input.Tool(ToolOutcome.UNFINISHED)
            }
        }
    }

    /** The planner's view of a message's blocks, remarks marked as notes. */
    fun inputs(blocks: List<AssistantBlock>): List<Input> =
        markingNotes(blocks.map(::input)) { isSingleLine(blocks[it].content) }

    /** One folded run, as the list draws it. */
    data class FoldedRun(
        /**
         * Expanded-state key: the first TOOL's id (the provider's tool_use
         * id, which survives a reload), not a leading thinking block's.
         */
        val key: String,
        val firstBlockId: String,
        val memberIds: List<String>,
        val group: Group,
        /** Summed execution time of the folded calls; null when none recorded one. */
        val durationMs: Long?,
    )

    fun foldedRuns(blocks: List<AssistantBlock>): List<FoldedRun> =
        plan(inputs(blocks), enabled = true).map { g ->
            val members = blocks.subList(g.range.first, g.range.last + 1)
            val firstTool = members.firstOrNull { it.kind == "tool_use" } ?: members.first()
            val durations = members.filter { it.kind == "tool_use" && it.durationMs > 0 }.map { it.durationMs }
            FoldedRun(
                key = firstTool.id,
                firstBlockId = members.first().id,
                memberIds = members.map { it.id },
                group = g,
                durationMs = if (durations.isEmpty()) null else durations.sum(),
            )
        }

    /**
     * [T-port-fold-tools] The live message's fold horizon, from its blocks.
     *
     * iOS marks a request's end from inside the agent loop. Android derives it
     * from the blocks the loop has written, which carry the same information:
     * - a request is the stretch of blocks one LLM call produced. A new one
     *   starts at the first non-tool block after a tool block (the model
     *   speaks again only after the previous call's tools returned);
     * - a request has ended when a later request has started, or when it has
     *   tool calls and none of them is still streaming its arguments (the LLM
     *   stream is over; its tools may still be running). A request with no
     *   tool calls is the final reply, which ends with the task, and then the
     *   whole message is planned anyway;
     * - an ended request whose text has [SEPARATOR_MINIMUM_LINES]+ lines is a
     *   separator, and the horizon runs through its last block.
     * Text between parallel calls of one request counts as a new request here,
     * a small difference from iOS that can only make a separator come later.
     */
    fun liveHorizon(blocks: List<AssistantBlock>): Int {
        if (blocks.isEmpty()) return 0
        val separators = HashSet<String>()
        var start = 0
        var i = 0
        while (start < blocks.size) {
            // Extend through the non-tool lead-in, then the tool calls.
            i = start
            while (i < blocks.size && blocks[i].kind != "tool_use") i++
            while (i < blocks.size && blocks[i].kind == "tool_use") i++
            val end = i - 1 // inclusive
            val request = blocks.subList(start, end + 1)
            val isLast = i >= blocks.size
            val tools = request.filter { it.kind == "tool_use" }
            val ended = !isLast || (tools.isNotEmpty() && tools.none { it.toolStatus == ToolBlockStatus.STREAMING })
            if (ended) {
                val text = request.filter { it.kind == "text" }.joinToString("\n") { it.content }
                if (isFoldSeparator(text)) separators.add(blocks[end].id)
            }
            start = i
        }
        return foldHorizon(blocks.map { it.id }, separators)
    }

    /** Runs to fold in [message]: up to its live horizon while it is running, else all of it. */
    fun foldedRuns(message: ChatMessage): List<FoldedRun> {
        val blocks = message.toolBlocks
        if (blocks.isEmpty()) return emptyList()
        val horizon = if (message.isStreaming) liveHorizon(blocks) else blocks.size
        if (horizon <= 0) return emptyList()
        return foldedRuns(if (horizon >= blocks.size) blocks else blocks.subList(0, horizon))
    }
}
