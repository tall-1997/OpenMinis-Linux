package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition

/**
 * 拾忆-style `spawn_agent` kinds.
 *
 * explore / plan are read-only (tool whitelist). worker may write and must
 * declare [write_paths] when two or more workers run in the same wave.
 * general-purpose is the unrestricted fallback (still no nested spawn).
 */
object SubAgentKind {
    const val WORKER = "worker"
    const val EXPLORE = "explore"
    const val PLAN = "plan"
    const val GENERAL = "general-purpose"

    const val RUN_SUBAGENT = "run_subagent"
    const val SPAWN_AGENT = "spawn_agent"

    const val SIMPLE_TURNS = 10
    const val MEDIUM_TURNS = 20
    const val COMPLEX_TURNS = 40
    const val COMPLEX_MAX_TURNS = 60
    // No longer a hard global ceiling — the coordinator assigns the budget and
    // SubAgentRunner only enforces its own ABSOLUTE_MAX_TURNS runaway guard.
    const val MAX_TURNS = SubAgentRunner.ABSOLUTE_MAX_TURNS

    private val READ_ONLY_ALLOW = setOf(
        "file_read",
        "web_search",
        "search_sessions",
        "read_session",
        "memory_get",
        "read_image",
        "browser_use",
        // grep_source is read-only source recon — exactly what explore/plan are
        // for, so it must be allowed here or those kinds lose their best lookup.
        GrepSourceTool.NAME,
        CodeGraphTool.NAME,
        GrepTool.NAME,
        GlobTool.NAME,
        ListDirTool.NAME,
        "shell_execute",
        "shell_exec",
        "env_exec",
        WebFetchTool.NAME,
        ExecuteCodeTool.NAME,
    )

    private val ALWAYS_DENY = setOf(
        RUN_SUBAGENT,
        SPAWN_AGENT,
        "ask_user_question",
        "AskUserQuestion",
        CronJobTool.NAME,
        DispatchAgentsTool.NAME,
        WolfpackTool.NAME,
    )

    fun isSpawnTool(name: String): Boolean {
        val n = name.trim()
        return n.equals(SPAWN_AGENT, ignoreCase = true) ||
            n.equals(RUN_SUBAGENT, ignoreCase = true) ||
            n.equals(DispatchAgentsTool.NAME, ignoreCase = true) ||
            n.equals(WolfpackTool.NAME, ignoreCase = true)
    }

    fun normalize(raw: String?): String = when (raw?.trim()?.lowercase()) {
        EXPLORE, "read-only", "readonly", "research", "recon" -> EXPLORE
        PLAN, "planner", "design" -> PLAN
        GENERAL, "general", "general_purpose", "generalpurpose", "gp" -> GENERAL
        else -> WORKER
    }

    fun canWrite(kind: String): Boolean {
        val k = normalize(kind)
        return k == WORKER || k == GENERAL
    }

    fun isReadOnly(kind: String): Boolean {
        val k = normalize(kind)
        return k == EXPLORE || k == PLAN
    }

    fun requiresWritePaths(kind: String, parallelWriters: Int): Boolean {
        return normalize(kind) == WORKER && parallelWriters > 1
    }

    private val mutatingShell = Regex(
        """(?:^|[;&|`\n]|\$\()(?:sudo\s+)?(rm|mv|cp|tee|chmod|chown|chgrp|mkdir|rmdir|touch|truncate|dd|install|apt|apt-get|dpkg|pip|npm|ln|unlink|shred)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val fileRedirect = Regex("""(?<![\d])>{1,2}(?!\s*/dev/null)""")

    /** explore/plan may inspect the guest, not change it. */
    fun readOnlyShellDenial(command: String): String? {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return null
        if (mutatingShell.containsMatchIn(trimmed) || fileRedirect.containsMatchIn(trimmed)) {
            return "Error: explore/plan shell is read-only. date, uname, cat, ls, and df are fine; writes and redirects are not."
        }
        return null
    }

    fun blocks(kind: String, toolName: String): Boolean {
        if (toolName in ALWAYS_DENY || isSpawnTool(toolName)) return true
        val k = normalize(kind)
        if (k == EXPLORE || k == PLAN) return toolName !in READ_ONLY_ALLOW
        return false
    }

    fun filterTools(
        kind: String,
        tools: List<AgentToolDefinition>,
        role: String? = null,
        roleContext: android.content.Context? = null,
    ): List<AgentToolDefinition> {
        val base = tools.filter { !blocks(kind, it.name) }
        val allowed = roleContext?.let { CollabRoles.toolsFor(it, role) }
            ?: CollabRoles.toolsFor(role)
            ?: return base
        return base.filter { it.name in allowed }
    }

    /**
     * Simple recon ≈ 10 turns; medium ≈ 20; complex implement/refactor ≈ 40–60.
     * Callers still clamp to the user-facing settings cap.
     */
    fun inferTurns(kind: String, prompt: String): Int {
        val k = normalize(kind)
        val len = prompt.length
        val simple = containsSimpleHint(prompt)
        val complex = containsComplexHint(prompt)
        return when (k) {
            EXPLORE -> if (len > 1500 || complex) MEDIUM_TURNS else SIMPLE_TURNS
            PLAN -> if (len > 2000 || complex) COMPLEX_TURNS else MEDIUM_TURNS
            else -> when {
                len > 3500 || (complex && len > 1800) -> COMPLEX_MAX_TURNS
                len > 1200 || complex -> COMPLEX_TURNS
                len < 400 && simple -> SIMPLE_TURNS
                else -> MEDIUM_TURNS
            }
        }
    }

    /**
     * The coordinator's assigned budget wins outright — no global settings
     * clamp. Only SubAgentRunner.ABSOLUTE_MAX_TURNS bounds a runaway. When the
     * coordinator omits max_turns, auto-size from the prompt (unbounded by the
     * old 60-turn cap, so a complex task can actually get the turns it needs).
     */
    fun clampTurns(
        kind: String,
        requested: Int?,
        cap: Int = MAX_TURNS,
        prompt: String = "",
    ): Int {
        val limit = cap.coerceIn(1, MAX_TURNS)
        val target = if (requested == null || requested <= 0) {
            inferTurns(kind, prompt)
        } else {
            requested
        }
        return target.coerceIn(1, limit)
    }

    private fun containsSimpleHint(prompt: String): Boolean {
        val p = prompt.lowercase()
        val keys = listOf(
            "look", "find", "list", "where", "summarize",
            "搜索", "查找", "看看", "摘要", "定位",
        )
        return keys.any { p.contains(it) }
    }

    private fun containsComplexHint(prompt: String): Boolean {
        val p = prompt.lowercase()
        val keys = listOf(
            "implement", "refactor", "migrate", "rewrite", "fix all",
            "实现", "重构", "迁移", "重写", "全量",
        )
        return keys.any { p.contains(it) }
    }
}
