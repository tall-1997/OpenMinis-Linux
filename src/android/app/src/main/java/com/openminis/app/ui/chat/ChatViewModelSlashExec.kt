package com.openminis.app.ui.chat

import com.openminis.app.goal.GoalSheetSignal
import com.openminis.app.logging.AppLogger

/**
 * Execute a slash command. Returns the text the composer should hold
 * afterward (caret via [pendingCaret] when relevant).
 *
 * [T-android-slash-menu-align-ios-prepend] Over-content (the menu was
 * opened via the "/" button, so [savedInputBeforeSlash] holds the user's
 * original text): a skill row prepends "/<skill> " to the original; an
 * action command (clear/compact/…) runs as a side effect and restores the
 * original (stripping the injected "/ "). Typed-"/" (no saved original):
 * a skill fills "/<skill> ", an action clears the input. The original body
 * is always preserved — never discarded (no regression of e48fe7a0).
 *
 * [currentInput] is retained for call-site compatibility; the body text is
 * sourced from [savedInputBeforeSlash], not the live string.
 */
fun ChatViewModel.executeSlashCommand(cmd: SlashCommand, currentInput: String = ""): String {
    val saved = savedInputBeforeSlash
    // [T-skill-slash a88ea8f9] Skill rows aren't directly executable —
    // they're a typing aid. Fill the composer with the literal slash
    // command; the user then taps Send and the model handles the skill via
    // the existing SKILL.md fragment injection in runAgentLoop.
    if (cmd.isSkill) {
        AppLogger.info(ChatViewModel.TAG, "[Slash] tap skill id=${cmd.id} title=${cmd.title} → composer fill only")
        savedInputBeforeSlash = null
        _showSlashMenu.value = false
        _slashMenuSelectedIndex.value = -1
        val prefix = "/${cmd.title} "
        // [T-android-slash-menu-align-ios-prepend] iOS parity: over-content
        // (saved != null) → PREPEND "/<skill> " to the original, so the
        // composer reads "/<skill> <original>" with the original as args,
        // caret right after the prefix (before the original). Typed-"/"
        // (saved == null) → just "/<skill> " (the input WAS the partial
        // command). Trailing space lets the user type "/<skill> <args>".
        return if (saved != null) {
            _pendingCaret.value = prefix.length
            prefix + saved
        } else {
            prefix
        }
    }
    AppLogger.info(ChatViewModel.TAG, "[Slash] tap id=${cmd.id} title=${cmd.title} streaming=${_isStreaming.value} compacting=${_isCompacting.value}")
    savedInputBeforeSlash = null
    _showSlashMenu.value = false
    _slashMenuSelectedIndex.value = -1

    when (cmd.id) {
        "compact" -> compactAll()
        "memory" -> toggleMemoryEnabled()
        "thinking" -> toggleThinking()
        "goal" -> GoalSheetSignal.open()
        "clear" -> _clearChatConfirmRequested.value = true
        else -> AppLogger.info(ChatViewModel.TAG, "[Slash] unrecognized id=${cmd.id} — no dispatch")
    }
    // [T-android-slash-menu-align-ios-prepend] Action command: restore the
    // saved ORIGINAL (stripping the injected "/ " prefix) so the body text
    // survives — never the live "/ <original>". Typed-"/" path → clear.
    if (saved != null) {
        _pendingCaret.value = saved.length
        return saved
    }
    return ""
}
