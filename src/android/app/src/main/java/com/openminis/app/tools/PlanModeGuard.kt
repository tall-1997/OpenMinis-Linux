package com.openminis.app.tools

import android.content.Context
import org.json.JSONObject

/**
 * Plan-mode enforcement: while [PlanModeStore] is enabled for a session,
 * every tool call that is not provably read-only is refused with the
 * [ExitPlanModeTool.refusalMessage] so the model self-corrects toward
 * investigation + exit_plan_mode instead of mutating state mid-plan.
 *
 * Default-deny: a tool is allowed only if it is on the allowlist below.
 * Tools registered later (or model-invented names that survive alias
 * canonicalization) are refused — the safe direction for a guardrail.
 *
 * Threat model: this guards against the model's *honest* mistakes, not
 * against adversarial shell injection. The single caller is the agent
 * loop; shell_execute gets a conservative prefix + metacharacter screen
 * (see [isReadOnlyShell]) rather than full shell parsing, and anything
 * ambiguous is refused.
 */
object PlanModeGuard {
    /**
     * Read-only investigation tools. [AgentPlanTool] is included on
     * purpose: the board is session-local tracking (the model's own
     * todo list), not a mutation of the user's world — same stance as
     * Claude Code allowing TodoWrite in plan mode.
     */
    private val ALLOWED = setOf(
        FileReadTool.NAME,
        ListDirTool.NAME,
        GrepTool.NAME,
        GlobTool.NAME,
        CodeGraphTool.NAME,
        ReadImageTool.NAME,
        WebSearchTool.NAME,
        WebFetchTool.NAME,
        UiReadTool.NAME,
        FindTools.NAME,
        AskUserQuestion.NAME,
        "memory_get",
        AgentPlanTool.NAME,
        ExitPlanModeTool.NAME,
        SessionLookupTool.SEARCH,
        SessionLookupTool.READ,
    )

    /** Single read-only commands shell_execute may run in plan mode. */
    private val SHELL_READ_ONLY_PREFIXES = listOf(
        "ls", "cat", "head", "tail", "wc", "file", "stat", "du", "df",
        "pwd", "which", "whoami", "id", "uname", "date", "env", "printenv",
        "git status", "git log", "git diff", "git show", "git branch",
        "git remote", "git rev-parse", "git describe", "git ls-files",
        "git shortlog", "git tag", "git blame", "git grep", "git ls-remote",
    )

    /** Shell metacharacters that could smuggle a second command or a write. */
    private val SHELL_METACHARACTERS = charArrayOf(';', '|', '>', '<', '`')

    /**
     * Returns a refusal [ToolExecutionResult] when plan mode blocks this
     * call, or null when the call may proceed. Pure w.r.t. the session
     * flag: reads [PlanModeStore.isEnabled], no side effects.
     */
    fun intercept(
        canonical: String,
        argsJson: String,
        sessionId: String,
        context: Context?,
    ): ToolExecutionResult? {
        if (!PlanModeStore.isEnabled(sessionId, context)) return null
        if (canonical in ALLOWED) return null
        if (canonical == "shell_execute" && isReadOnlyShell(argsJson)) return null
        return ToolExecutionResult(
            ExitPlanModeTool.refusalMessage(),
            false,
            errorCode = ToolErrorCode.PERMISSION_DENIED,
            recoveryHint = "Switch to read-only tools, or submit the plan with exit_plan_mode.",
            toolTitle = canonical,
        )
    }

    /**
     * A shell command is plan-mode-safe when it is a SINGLE command whose
     * first words match a read-only prefix and it contains no shell
     * metacharacters (no chaining, no pipes, no redirects, no command
     * substitution). `find` is deliberately absent: `-exec` runs arbitrary
     * programs. `python3 -c` and friends are absent: arbitrary code.
     */
    internal fun isReadOnlyShell(argsJson: String): Boolean {
        val command = runCatching { JSONObject(argsJson).optString("command") }
            .getOrElse { return false }
            .trim()
        if (command.isEmpty()) return false
        if (command.indexOfAny(SHELL_METACHARACTERS) >= 0) return false
        if ("$(" in command) return false
        val lowered = command.lowercase()
        return SHELL_READ_ONLY_PREFIXES.any { lowered == it || lowered.startsWith("$it ") }
    }
}
