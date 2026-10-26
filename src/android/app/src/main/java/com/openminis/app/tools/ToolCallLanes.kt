package com.openminis.app.tools

/**
 * [T-parallel-tool-calls] Lane partition for same-turn tool calls.
 *
 * The agent loop used to execute every tool call in a turn strictly
 * sequentially. That is honest about ordering but wastes wall clock on the
 * most common multi-tool shape: a recon burst of independent read-only calls
 * (file_read × grep × glob …) that cannot affect each other. This
 * classifier decides, per call, whether it may overlap its siblings.
 *
 * ## The contract
 *
 * - [ToolCallLane.CONCURRENT] — the tool is side-effect-free with respect
 *   to anything another same-turn call could observe: it reads files, the
 *   network, the session registry, or runs a one-shot LLM request, and any
 *   state it touches is either read-only or already concurrency-safe
 *   (SQLite row locks, StateFlow, per-id spill files). Concurrent calls
 *   never wait for each other and never wait for a barrier that comes
 *   AFTER them in emission order.
 * - [ToolCallLane.BARRIER] — everything else: shells, writes, UI surface,
 *   registries, anything that mutates what a later call might read.
 *   Barriers execute strictly in emission order among themselves, and a
 *   CONCURRENT call positioned after a barrier joins that barrier first.
 *
 * The model's emission order IS its dependency declaration: a call emitted
 * after a write may assume the write happened. Reads emitted before a
 * barrier never block it (reads have no side effects), so the two overlap
 * freely.
 *
 * ## Default
 *
 * Unknown names classify as BARRIER. A tool we have never seen gets the
 * conservative lane; a fail-fast unknown-tool call costs nothing either way.
 * User-defined local command-template tools are resolved by the caller
 * (they materialize into shell commands) and must pass
 * `isLocalTool = true`.
 */
internal enum class ToolCallLane { BARRIER, CONCURRENT }

internal object ToolCallLanes {

    /**
     * Side-effect-free tools. Names mirror the dispatch table in
     * ChatViewModelExecuteToolExt; the contract test pins every registered
     * tool to a lane so a new tool cannot silently inherit the default.
     */
    private val CONCURRENT_TOOLS = setOf(
        FileReadTool.NAME,                    // "file_read"
        ListDirTool.NAME,                     // "list_dir"
        GrepTool.NAME,                        // "grep" (grep_source is an alias, resolved to canonical before lane assignment)
        GlobTool.NAME,                        // "glob"
        WebFetchTool.NAME,                    // "web_fetch"
        WebSearchTool.NAME,                   // "web_search"
        ReadImageTool.NAME,                   // "read_image"
        ProductMediaTools.DESCRIBE_IMAGE,     // "describe_image"
        ProductMediaTools.GENERATE_IMAGE,     // "generate_image"
        ProductMediaTools.GENERATE_VIDEO,     // "generate_video"
        ProductMediaTools.TRANSCRIBE_AUDIO,   // "transcribe_audio"
        ProductMediaTools.TRANSLATE_TEXT,     // "translate_text"
        OcrTool.NAME,                         // "ocr_image"
        ScreenTimeTool.NAME,                  // "get_screen_time"
        "memory_get",                         // memoryGetDefinition name (aliases resolve to canonical before lane assignment)
        SessionLookupTool.SEARCH,             // "search_sessions"
        SessionLookupTool.READ,               // "read_session"
        CodeGraphTool.NAME,                   // "code_graph"
        AskReasoningTool.NAME,                // "ask_reasoning"
    )

    /** Shell-family names that materialize into a session shell command. */
    private val SHELL_FAMILY = setOf(
        "shell_execute", "shell_exec", "env_exec", "su_exec",
        ExecuteCodeTool.NAME,                  // "execute_code" — can nest any tool
    )

    /**
     * Local command-template plugins run a materialized shell command
     * through the full shell pipeline, so they inherit the shell lane.
     */
    fun laneFor(canonicalName: String, isLocalTool: Boolean = false): ToolCallLane {
        if (isLocalTool || canonicalName in SHELL_FAMILY) return ToolCallLane.BARRIER
        return if (canonicalName in CONCURRENT_TOOLS) ToolCallLane.CONCURRENT
        else ToolCallLane.BARRIER
    }

    /** Exposed for the registry contract test. */
    internal fun concurrentToolsForTest(): Set<String> = CONCURRENT_TOOLS
}
