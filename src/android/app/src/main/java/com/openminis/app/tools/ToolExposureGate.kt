package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition

/**
 * Five-level tool exposure gate — the port of pi coding-agent
 * `extensions/types.ts:524-570` (`ToolExposure`: direct | model-only |
 * codemode | deferred | hidden), adapted to this registry's
 * `AgentToolDefinition.exposure: String?` field (null = direct).
 *
 * Level semantics as mapped onto this codebase:
 * - [DIRECT] (null exposure): declared in the schema and callable. Every
 *   pre-existing tool is direct by default — null and "direct" are
 *   interchangeable, so the field is a pure addition and behaviour is
 *   unchanged for tools that do not opt in.
 * - [MODEL_ONLY]: declared in the schema (pi: "declared to the model while
 *   active, never callable" — here: declared; the callability half is
 *   enforced by the dispatch entry via [levelForName] + [modelOnlyRefusal]).
 * - [DEFERRED]: NOT in the schema. The existing alwaysOn / find_tools
 *   mechanism IS this level's implementation: find_tools discovers and
 *   enables the tool for subsequent turns, and the enabled name then rides
 *   [AgentTools] `enabledToolNames` into the schema. The gate only makes the
 *   policy explicit on the definition instead of living solely in the
 *   alwaysOn set.
 * - [HIDDEN]: dispatchable through the executor but never declared in any
 *   schema, even when explicitly enabled.
 * - [CODEMODE]: never declared in the schema, but callable from a codemode
 *   script (CodemodeSandbox) through the injected tool host.
 *
 * The codemode-callable set ([callableForCodemode]) mirrors pi's
 * `ToolLoadout.callable`: everything registered EXCEPT model_only, hidden and
 * the codemode ENTRY ITSELF. pi's "codemode tools list the direct entry too"
 * bootstrap is deliberately NOT ported for the entry tool: a script
 * re-entering execute_code_js parks one Dispatchers.IO thread per nesting
 * level in the bridge's runBlocking and ~64 levels deadlock the whole IO pool
 * (Stop cannot reach the parked threads) — see CodemodeTool's nesting guard.
 *
 * Schema assembly calls [filterForSchema] exactly once per path in
 * [AgentTools.makeAgentTools] (full mode and long-tail mode), keeping the
 * alwaysOn / enabledToolNames behaviour byte-identical for direct tools.
 *
 * Unknown exposure values fail OPEN as direct: the registry only gains values
 * through code review, and a typo in a new level must not silently hide tools
 * from the schema. pi's hyphenated "model-only" spelling is normalized to
 * [MODEL_ONLY] so definitions copied straight from pi keep working.
 */
object ToolExposureGate {

    /** Declared in the schema and callable. Also the default for null. */
    const val DIRECT = "direct"

    /**
     * Declared in the schema; the dispatch entry refuses direct calls via
     * [levelForName] / [modelOnlyRefusal] (pi: "declared to the model while
     * active, never callable").
     */
    const val MODEL_ONLY = "model_only"

    /** Never declared; callable from a codemode script via the tool host. */
    const val CODEMODE = "codemode"

    /** Not declared; find_tools enables it into the schema on demand. */
    const val DEFERRED = "deferred"

    /** Dispatchable but never declared in any schema. */
    const val HIDDEN = "hidden"

    /** All values the gate understands, for tests and diagnostics. */
    val KNOWN_LEVELS: Set<String> = setOf(DIRECT, MODEL_ONLY, CODEMODE, DEFERRED, HIDDEN)

    /**
     * Normalized level of one definition: null/blank means [DIRECT], pi's
     * hyphenated spellings normalize to the underscore forms, and an unknown
     * value fails open as [DIRECT] (documented above).
     */
    fun levelOf(definition: AgentToolDefinition): String {
        val raw = definition.exposure?.trim()?.lowercase()?.replace('-', '_').orEmpty()
        return if (raw in KNOWN_LEVELS && raw.isNotEmpty()) raw else DIRECT
    }

    /**
     * The schema-facing filter. [activeSet] is the set of tool names that are
     * "activated" in pi's sense — here, the find_tools-enabled names
     * (plus the alwaysOn core, which the caller usually pre-filtered on).
     *
     * - direct / model_only -> kept (declared)
     * - deferred -> kept only when the name is in [activeSet]
     * - codemode / hidden -> never kept (callable by script / by dispatch
     *   respectively, but never declared)
     */
    fun filterForSchema(
        definitions: List<AgentToolDefinition>,
        activeSet: Set<String>,
    ): List<AgentToolDefinition> = definitions.filter { includesInSchema(levelOf(it), it.name, activeSet) }

    /** Single-name predicate behind [filterForSchema], exposed for tests. */
    fun includesInSchema(level: String, name: String, activeSet: Set<String>): Boolean = when (level) {
        DIRECT, MODEL_ONLY -> true
        DEFERRED -> name in activeSet
        CODEMODE, HIDDEN -> false
        else -> true // unreachable: levelOf normalizes to the known levels
    }

    // ── dispatch-side callability enforcement (MODEL_ONLY) ──

    /**
     * Canonical tool name -> normalized exposure, built ONCE from the full
     * registry with every gate flag on: the flags decide schema inclusion per
     * request, while the exposure a definition DECLARES is flag-independent,
     * so the union build is the complete lookup. Lazy so the registry is only
     * assembled when a dispatch entry actually asks.
     */
    private val levelByName: Map<String, String> by lazy {
        AgentTools.makeAgentTools(
            supportsImageInput = true,
            visionGroupConfigured = true,
            memoryEnabled = true,
            subAgentEnabled = true,
            codeGraphEnabled = true,
            mcpNativeEnabled = true,
            hasSpeechModel = true,
            includeLongTail = true,
        ).associate { it.name to levelOf(it) }
    }

    /**
     * The exposure level for a CANONICAL tool name — the dispatch-entry
     * lookup behind the MODEL_ONLY refusal in
     * ChatViewModelExecuteToolExt.executeTool. A pure in-memory map read (no
     * I/O, no registry rebuild), so the dispatcher can afford it before any
     * SecurityGate / failure-memory work. Names absent from the registry
     * (aliases already canonicalized upstream, user-defined local tools,
     * future dynamic tools) fail open as [DIRECT] — same policy as
     * [levelOf] for unknown exposure strings.
     */
    fun levelForName(canonicalName: String): String = levelByName[canonicalName] ?: DIRECT

    /**
     * The unified-shape refusal the dispatch entry returns for a model_only
     * call. Kept beside [levelForName] so text, error code and hint stay
     * pinned by the same tests that pin the lookup; the shape matches every
     * other early refusal (ToolFailureMemory / SecurityGate / ActionGuard):
     * success=false, a typed [ToolErrorCode], a model-actionable recovery
     * hint, and the resolved tool title.
     */
    fun modelOnlyRefusal(canonicalName: String, toolTitle: String): ToolExecutionResult =
        ToolExecutionResult(
            "Tool '$canonicalName' is not callable in this context (exposure=model_only).",
            false,
            errorCode = ToolErrorCode.PERMISSION_DENIED,
            recoveryHint = "This tool is declared for schema presence only; do not call it directly.",
            toolTitle = toolTitle,
        )

    /**
     * The tools a codemode script may call through its injected tool host —
     * pi's `ToolLoadout.callable`: everything registered except model_only
     * (never callable) and hidden (unreachable). Deferred and codemode tools
     * ARE callable from scripts without being declared, which is the whole
     * point of the level.
     *
     * The codemode entry tool (execute_code_js) is excluded on purpose: each
     * nesting level parks one Dispatchers.IO thread in the bridge's
     * runBlocking and ~64 levels deadlock the pool. Scripts that need another
     * aggregation layer inline it in the one script instead. The callable-set
     * exclusion is the first line of defense; CodemodeTool's thread-depth
     * guard catches any path that would bypass it (e.g. a custom
     * callableNames set naming the entry).
     */
    fun callableForCodemode(definitions: List<AgentToolDefinition>): List<AgentToolDefinition> =
        definitions.filter {
            when (levelOf(it)) {
                MODEL_ONLY, HIDDEN -> false
                else -> it.name != CodemodeTool.NAME
            }
        }

    /** Convenience: the codemode-callable tool NAMES, for the sandbox host. */
    fun callableNamesForCodemode(definitions: List<AgentToolDefinition>): Set<String> =
        callableForCodemode(definitions).map { it.name }.toSet()
}
