package com.openminis.app.ui.chat

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

/**
 * [T-android-slash-with-args] Slash-input interception for the send path.
 *
 * Extracted from ChatViewModel.kt to keep that file under its architecture
 * ceiling. Behaviour is unchanged: the send gate, the "is this a chat message
 * rather than an unknown command" decision, and the single local notice all
 * live here.
 */

/**
 * If `text` is a slash command literal (e.g. "/compact"), run it and
 * return true so the caller can skip the normal send path. Mirrors iOS
 * `tryExecuteInputAsSlashCommand()`. Recognized titles are matched
 * case-insensitively against [ChatViewModel.availableSlashCommands].
 *
 * Accepts both ASCII `/` and the full-width `／` (U+FF0F): some Chinese/
 * Japanese IMEs auto-substitute the full-width form when the user types
 * `/` while a CJK keyboard layout is active. We treat them identically.
 *
 * [T-android-slash-with-args] Only the FIRST token is the command. The
 * previous whole-string match turned `"/skill 用户自己的话"` into
 * "未知命令" and swallowed the message — the shape the "/" picker itself
 * produces when the user picks a Skill over text they already typed (it
 * prepends `/<skill> `), and the shape users type by hand. A token that
 * names a command, Skill or MCP server followed by the user's own words
 * is therefore a normal chat message (return false → normal send), never
 * an unknown command. Action commands still run only when BARE
 * (`"/clear"`), so `"/clear please"` cannot wipe the chat by accident.
 */
internal fun ChatViewModel.tryExecuteInputAsSlashCommand(text: String): Boolean {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return false
    val first = trimmed[0]
    if (first != '/' && first != '／') return false
    val body = trimmed.drop(1)
    if (body.equals("goal", ignoreCase = true) || body.startsWith("goal ", ignoreCase = true) ||
        body.startsWith("goal\t", ignoreCase = true)
    ) {
        viewModelScope.launch { executeGoalCommand(body.removeRange(0, 4).trim()) }
        return true
    }
    // [T-android-slash-with-args] Tokenise: "/compact now" → "compact",
    // "/技能名 用户的话" → "技能名". A bare "/" has no token and stays a
    // panel interaction.
    val token = SlashInputParse.commandToken(trimmed)
    if (token == null) {
        appendSystemInfo(text = unknownSlashCommandNotice(trimmed), iconKind = "info")
        return true
    }
    val cmd = availableSlashCommands.firstOrNull { it.title.equals(token, ignoreCase = true) }
    if (cmd != null && SlashInputParse.isBareCommand(trimmed)) {
        executeSlashCommand(cmd)
        return true
    }
    // A known target + user text (built-in with args / Skill / MCP server)
    // is a chat message, not an unknown command.
    if (cmd != null || isKnownChatSlashTarget(token)) return false
    // [T-android-slash-literal-not-chat] A bare "/" or an unmatched
    // "/xyz" is a slash-panel interaction, not a chat message. Sending
    // it to the model made the assistant answer the literal string.
    // Swallow it with a local notice instead; the composer's slash
    // panel stays open so the user can pick a real command.
    appendSystemInfo(text = unknownSlashCommandNotice(trimmed), iconKind = "info")
    return true
}

/**
 * [T-android-slash-with-args] The one local "not a command" notice.
 * Kept in a single place so the hardcoded-CJK ratchet sees the same one
 * literal the file always had (two inline copies tripped
 * check_cjk_literals.py).
 */
private fun ChatViewModel.unknownSlashCommandNotice(text: String): String =
    "未知命令: $text（可用命令见 / 面板）"

/**
 * [T-android-slash-with-args] True when [token] names something the "/"
 * panel can insert — a built-in command, an enabled Skill, or an enabled
 * MCP server. Such a token followed by free text must be delivered to the
 * model as a user message instead of being reported as an unknown command.
 */
private fun ChatViewModel.isKnownChatSlashTarget(token: String): Boolean {
    if (token.isBlank()) return false
    if (availableSlashCommands.any { it.title.equals(token, ignoreCase = true) }) return true
    val sid = activeSessionId
    val skills = skillRepository
    if (skills != null && skills.skills.value.any { skill ->
            skill.name.equals(token, ignoreCase = true) && skills.isEnabledForSession(skill.id, sid)
        }
    ) return true
    val mcps = mcpRepository
    if (mcps != null && mcps.servers.value.any { server ->
            server.id.equals(token, ignoreCase = true) && mcps.isEnabledForSession(server.id, sid)
        }
    ) return true
    return false
}
