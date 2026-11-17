package com.openminis.app.ui.chat

import com.openminis.app.tools.PresentTool

/**
 * Pure row-selection predicates and projections for flat chat items,
 * extracted from ChatFlatItems.kt (architecture-policy line ceiling).
 * All referenced types (AssistantBlock / ChatMessage / ToolBlockStatus)
 * live in the same package (ChatModels.kt); PresentTool comes from the tools
 * package (same import ChatFlatItems.kt uses).
 */

internal fun shouldShowToolUseRow(block: AssistantBlock, showCompletedToolCards: Boolean): Boolean {
    if (showCompletedToolCards) return true
    return when (block.toolStatus) {
        ToolBlockStatus.SUCCESS, ToolBlockStatus.FAILED, ToolBlockStatus.TIMEOUT -> false
        else -> true
    }
}

private val SUB_AGENT_TRANSCRIPT_TOOLS = setOf("spawn_agent", "run_subagent", "dispatch_agents")

/** Chat cards that duplicate the sub-agent chip. Hidden while chip mode is on. */
internal fun isSubAgentTranscriptCard(block: AssistantBlock): Boolean =
    block.kind == "tool_use" && (
        block.toolName in SUB_AGENT_TRANSCRIPT_TOOLS || "#sub-" in block.id
    )

internal fun isAlwaysVisibleProcessTool(block: AssistantBlock): Boolean =
    block.kind == "tool_use" && (
        block.toolName == "ask_user_question" || block.toolName == PresentTool.NAME || block.toolName == DOWNLOAD_TOOL_NAME
        )

/**
 * [T-android-present-tool] A completed present call with a parseable archive
 * path renders as [FlatChatItem.AssistantPresentCard]; everything else (still
 * running, failed, legacy output without the Path: line) stays a tool pill.
 */
internal fun isPresentDeliverable(block: AssistantBlock): Boolean =
    block.kind == "tool_use" && block.toolName == PresentTool.NAME &&
        block.toolStatus == ToolBlockStatus.SUCCESS &&
        PresentTool.parseSharedGuestPath(block.content) != null

internal fun shouldShowProcessToolRow(
    block: AssistantBlock,
    showCompletedToolCards: Boolean,
    foldAiProcess: Boolean,
): Boolean {
    if (isAlwaysVisibleProcessTool(block)) return true
    // [P-process-card-single-step] No round-level gate here — the
    // `roundFolded` / `keepVisibleIds` parameters this function used to take
    // (and ignore) were the leftover of one. Every caller has already filtered
    // `block.id !in foldedBlockIds`, so a block that reaches this function
    // belongs to a run that did NOT card (below the 2-step threshold) and must
    // render inline. The old `if (roundFolded) return block.id in
    // keepVisibleIds` — with keepVisibleIds hardcoded to the empty set — hid
    // those blocks whenever any sibling run in the round carded — dropping a
    // lone tool the same way the lone thinking was dropped. Card-owned
    // blocks never reach here; the block-level check upstream is the gate.
    if (isInFlightProcessTool(block)) return true
    // Fold mode owns visibility: a running or user-expanded round shows every
    // step in-thread (the fold bar no longer carries tool chips, so hiding
    // completed tools here would make them unreachable). The
    // showCompletedToolCards setting only governs the fold-OFF mode.
    if (foldAiProcess) return true
    return shouldShowToolUseRow(block, showCompletedToolCards)
}

internal val IN_FLIGHT_PROCESS_TOOL_STATUSES = setOf(
    ToolBlockStatus.STREAMING,
    ToolBlockStatus.PENDING,
    ToolBlockStatus.RUNNING,
)

internal fun isInFlightProcessTool(block: AssistantBlock): Boolean =
    block.kind == "tool_use" && block.toolStatus in IN_FLIGHT_PROCESS_TOOL_STATUSES

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
 * [foldAiProcess] is on, the round model owns process display — a running
 * round renders every step in-list, so a viewport-pinned copy would
 * duplicate rows that are already visible at their own positions. The
 * overlay therefore only serves the fold-OFF mode.
 *
 * The overlay subset is *not* the source of truth for ToolDetailSheet:
 * completed tools must remain openable after they leave the overlay.
 */
internal fun isFloatingProcessTool(
    block: AssistantBlock,
    foldAiProcess: Boolean,
): Boolean {
    if (!isDetailProcessTool(block)) return false
    if (foldAiProcess) return false
    return true
}

/**
 * [P0-4] The blocks the floating activity pill may pin, derived from the
 * LATEST assistant reply. ONE pure projection shared by the pill's mount
 * gate (hasFloatingTools in ChatScreen, synchronous) and its data pipeline
 * (lastToolBlocks, coroutine-side) — previously two hand-maintained filters
 * that could drift: the gate scanned ALL messages while the pipeline read
 * only the latest reply, and neither knew about the showFloatingToolBar
 * pref or the streaming state, which left a 79dp bottom reserve for an
 * overlay that never rendered after a fold-OFF turn ended.
 *
 * Fold-ON returns empty (single channel: the running round renders every
 * step in-list). Sub-agent transcript cards are dropped while the
 * sub-agent bar owns their display.
 */
internal fun floatingOverlayBlocks(
    latestReply: ChatMessage?,
    foldAiProcess: Boolean,
    showSubAgentBar: Boolean,
    activeSubAgentToolIds: Set<String>,
): List<AssistantBlock> = latestReply?.toolBlocks.orEmpty()
    .filter { isFloatingProcessTool(it, foldAiProcess) }
    .filterNot {
        showSubAgentBar && isSubAgentTranscriptCard(it) &&
            (it.id in activeSubAgentToolIds || it.id.substringBefore("#sub-") in activeSubAgentToolIds)
    }
