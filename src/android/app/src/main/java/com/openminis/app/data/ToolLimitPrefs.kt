package com.openminis.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User-facing caps for shell timeout, file_read size, sub-agent turns, and
 * the main-session agent-loop turn ceiling. SharedPreferences; no Room bump.
 * Unprimed reads return defaults so unit tests and early tool calls stay
 * safe.
 */
object ToolLimitPrefs {
    const val PREFS = "minis_tool_limits"

    const val DEFAULT_SHELL_TIMEOUT_SEC = 600
    const val MIN_SHELL_TIMEOUT_SEC = 30
    const val MAX_SHELL_TIMEOUT_SEC = 1_800
    const val SHELL_STEP_SEC = 30

    const val DEFAULT_FILE_READ_MAX_CHARS = 15_000
    const val MIN_FILE_READ_MAX_CHARS = 2_000
    const val MAX_FILE_READ_MAX_CHARS = 80_000
    const val FILE_CHARS_STEP = 1_000

    const val DEFAULT_FILE_READ_MAX_LINES = 0
    const val MAX_FILE_READ_MAX_LINES = 20_000
    const val FILE_LINES_STEP = 100

    // [T-subagent-unbounded-turns] Runaway guard threshold only (see
    // SubAgentRunner.ABSOLUTE_MAX_TURNS): the default matches the guard, so a
    // sub-agent is effectively unlimited unless the user lowers it.
    const val DEFAULT_SUBAGENT_MAX_TURNS = 1000
    const val MIN_SUBAGENT_MAX_TURNS = 50
    const val MAX_SUBAGENT_MAX_TURNS = 1000
    const val TURNS_STEP = 10

    // [T-main-session-turn-cap] The main agent loop's runaway guard. It was a
    // hardcoded 200 with no user escape hatch — long autonomous runs hit it
    // with no way to raise it, while the sub-agent cap sat one screen away.
    // Floor 50 keeps the guard meaningful (multi-step tool loops need room);
    // ceiling 1000 still bounds a runaway model burning tokens forever.
    // Default 500: long autonomous tasks (research/ports) routinely exceed
    // 200 turns; 500 keeps them untruncated while the 1000 ceiling still
    // guards a runaway model.
    const val DEFAULT_MAIN_MAX_TURNS = 500
    const val MIN_MAIN_MAX_TURNS = 50
    const val MAX_MAIN_MAX_TURNS = 1_000
    const val MAIN_TURNS_STEP = 10

    // [T-parallel-tool-calls] How many CONCURRENT-lane tool calls (reads)
    // may overlap within one tool turn. Barriers (shell/writes/spawns) are
    // unaffected — they chain in emission order regardless of this value.
    // Floor 1 keeps a misconfigured device from serializing reads back to
    // the old behaviour; ceiling 8 matches commandConcurrency's ceiling so
    // the read lane can never out-grow the shell lane's own budget.
    const val DEFAULT_TOOL_CALL_CONCURRENCY = 4
    const val MIN_TOOL_CALL_CONCURRENCY = 1
    const val MAX_TOOL_CALL_CONCURRENCY = 8

    fun toolCallConcurrency(): Int = clampToolCallConcurrency(
        prefs?.int(KEY_TOOL_CALL_CONCURRENCY, DEFAULT_TOOL_CALL_CONCURRENCY) ?: DEFAULT_TOOL_CALL_CONCURRENCY,
    )
    fun setToolCallConcurrency(value: Int) = put(KEY_TOOL_CALL_CONCURRENCY, clampToolCallConcurrency(value))
    private fun clampToolCallConcurrency(value: Int): Int = value.coerceIn(MIN_TOOL_CALL_CONCURRENCY, MAX_TOOL_CALL_CONCURRENCY)

    fun idleShellCount(): Int = (prefs?.int(KEY_IDLE_SHELL_COUNT, 2) ?: 2).coerceIn(0, 8)
    fun idleShellMinutes(): Int = (prefs?.int(KEY_IDLE_SHELL_MINUTES, 3) ?: 3).coerceIn(1, 30)
    fun setIdleShellCount(value: Int) = put(KEY_IDLE_SHELL_COUNT, value.coerceIn(0, 8))
    fun setIdleShellMinutes(value: Int) = put(KEY_IDLE_SHELL_MINUTES, value.coerceIn(1, 30))

    fun autoConcurrency(): Boolean = prefs?.bool(KEY_AUTO_CONCURRENCY, true) ?: true
    fun setAutoConcurrency(value: Boolean) {
        prefs?.set(KEY_AUTO_CONCURRENCY, value)
        _revision.value++
    }
    fun commandConcurrency(): Int = (prefs?.int(KEY_COMMAND_CONCURRENCY, 4) ?: 4).coerceIn(1, 8)
    fun heavyConcurrency(): Int = (prefs?.int(KEY_HEAVY_CONCURRENCY, 1) ?: 1).coerceIn(1, 4)
    fun queueTimeoutSec(): Int = (prefs?.int(KEY_QUEUE_TIMEOUT_SEC, 0) ?: 0).coerceIn(0, 1800)
    fun setCommandConcurrency(value: Int) = put(KEY_COMMAND_CONCURRENCY, value.coerceIn(1, 8))
    fun setHeavyConcurrency(value: Int) = put(KEY_HEAVY_CONCURRENCY, value.coerceIn(1, 4))
    fun setQueueTimeoutSec(value: Int) = put(KEY_QUEUE_TIMEOUT_SEC, value.coerceIn(0, 1800))

    private const val KEY_SHELL = "shellTimeoutSec"
    private const val KEY_CHARS = "fileReadMaxChars"
    private const val KEY_LINES = "fileReadMaxLines"
    private const val KEY_TURNS = "subagentMaxTurns"
    private const val KEY_MAIN_TURNS = "mainSessionMaxTurns"
    // [T-prefs-literal-keys] These were bare literals. The registry generator
    // only sees `const val`, so they were never registered and every PrefsHandle
    // read threw "key is not registered": the tool-limits page crashed on open,
    // and the sandbox command lock threw before it could start a shell.
    private const val KEY_TOOL_CALL_CONCURRENCY = "toolCallConcurrency"
    private const val KEY_AUTO_CONCURRENCY = "autoConcurrency"
    private const val KEY_IDLE_SHELL_COUNT = "idleShellCount"
    private const val KEY_IDLE_SHELL_MINUTES = "idleShellMinutes"
    private const val KEY_COMMAND_CONCURRENCY = "commandConcurrency"
    private const val KEY_HEAVY_CONCURRENCY = "heavyConcurrency"
    private const val KEY_QUEUE_TIMEOUT_SEC = "queueTimeoutSec"

    @Volatile private var prefs: com.openminis.app.config.PrefsHandle? = null

    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun prime(context: Context) {
        prefs = com.openminis.app.config.Prefs.file(PREFS)
        _revision.value++
    }

    fun shellTimeoutSec(): Int = clampShell(prefs?.int(KEY_SHELL, DEFAULT_SHELL_TIMEOUT_SEC) ?: DEFAULT_SHELL_TIMEOUT_SEC)
    fun fileReadMaxChars(): Int = clampChars(prefs?.int(KEY_CHARS, DEFAULT_FILE_READ_MAX_CHARS) ?: DEFAULT_FILE_READ_MAX_CHARS)
    fun fileReadMaxLines(): Int = clampLines(prefs?.int(KEY_LINES, DEFAULT_FILE_READ_MAX_LINES) ?: DEFAULT_FILE_READ_MAX_LINES)
    fun subagentMaxTurns(): Int = clampTurns(prefs?.int(KEY_TURNS, DEFAULT_SUBAGENT_MAX_TURNS) ?: DEFAULT_SUBAGENT_MAX_TURNS)
    fun mainSessionMaxTurns(): Int = clampMainTurns(prefs?.int(KEY_MAIN_TURNS, DEFAULT_MAIN_MAX_TURNS) ?: DEFAULT_MAIN_MAX_TURNS)

    fun setShellTimeoutSec(value: Int) = put(KEY_SHELL, clampShell(value))
    fun setFileReadMaxChars(value: Int) = put(KEY_CHARS, clampChars(value))
    fun setFileReadMaxLines(value: Int) = put(KEY_LINES, clampLines(value))
    fun setSubagentMaxTurns(value: Int) = put(KEY_TURNS, clampTurns(value))
    fun setMainSessionMaxTurns(value: Int) = put(KEY_MAIN_TURNS, clampMainTurns(value))

    fun clampShell(value: Int): Int = value.coerceIn(MIN_SHELL_TIMEOUT_SEC, MAX_SHELL_TIMEOUT_SEC)
    fun clampChars(value: Int): Int = value.coerceIn(MIN_FILE_READ_MAX_CHARS, MAX_FILE_READ_MAX_CHARS)
    fun clampLines(value: Int): Int = if (value <= 0) 0 else value.coerceIn(FILE_LINES_STEP, MAX_FILE_READ_MAX_LINES)
    fun clampTurns(value: Int): Int = value.coerceIn(MIN_SUBAGENT_MAX_TURNS, MAX_SUBAGENT_MAX_TURNS)
    fun clampMainTurns(value: Int): Int = value.coerceIn(MIN_MAIN_MAX_TURNS, MAX_MAIN_MAX_TURNS)

    fun resolveShellTimeoutSec(requested: Int?): Int {
        val cap = shellTimeoutSec()
        return if (requested == null || requested <= 0) cap else requested.coerceIn(1, cap)
    }

    private fun put(key: String, value: Int) {
        prefs?.set(key, value)
        _revision.value++
    }
}
