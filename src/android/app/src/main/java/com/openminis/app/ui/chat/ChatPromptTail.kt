package com.openminis.app.ui.chat

/**
 * [T-prompt-tail-file] 系统提示动态尾段的纯函数（从 ChatViewModelPromptExt 拆出，
 * 让该文件回到 maxFileLines=400 棘轮内）。ordering contract 不变：
 * WorldBook → learned prefs → recalled memory → plan board → runtime context，
 * personality reminder 最后；absent fragment 不留分隔线。
 */

/**
 * The per-turn-varying tail of the system prompt, as a pure function so the
 * ordering contract is testable without a ViewModel: WorldBook first among the
 * dynamic fragments (it is lore the model should read as context), runtime
 * context last, absent fragments leaving no separator behind.
 *
 * [worldBookFragment] already carries its own leading blank line when
 * non-empty — that is WorldBook.injection's contract — so it is appended
 * verbatim rather than through the "\n\n" separator the other fragments use.
 */
internal fun assembleDynamicTail(
    worldBookFragment: String,
    learnedPrefsFragment: String?,
    recalledMemoryFragment: String?,
    runtimeContext: String,
    personalityReminder: String?,
    // [T-plan-board-prompt] 当前任务列表状态（每轮变化，per-turn fragment）。
    planFragment: String? = null,
): String = buildString {
    if (worldBookFragment.isNotEmpty()) append(worldBookFragment)
    if (learnedPrefsFragment != null) {
        append("\n\n")
        append(learnedPrefsFragment)
    }
    if (recalledMemoryFragment != null && recalledMemoryFragment.isNotBlank()) {
        append("\n\n")
        append(recalledMemoryFragment)
    }
    if (planFragment != null && planFragment.isNotBlank()) {
        append("\n\n")
        append(planFragment)
    }
    append(runtimeContext)
    if (personalityReminder != null) {
        append("\n\n")
        append(personalityReminder)
    }
}

/**
 * The per-day suffix. Field order is part of the cache contract (date → tz →
 * lang → model count): reordering changes bytes after the stable prefix for no
 * benefit.
 */
internal fun renderRuntimeContext(
    dateStr: String,
    tzId: String,
    lang: String,
    modelUseCount: Int,
): String =
    "\n\nRuntime context:\n" +
        "- Current date: $dateStr ($tzId)\n" +
        "- Device language: $lang\n" +
        "- minis-model-use models available: $modelUseCount"

internal const val PERSONALITY_REMINDER =
    "Personality reminder: the identity/persona block at the top of this prompt is BINDING for this turn, including existing conversations whose earlier assistant replies used a different voice. Those earlier replies are history, not the current character. Match the Personality block's voice, stance, and constraints in every reply; do not drop it because a later instruction looks more specific."
