package com.openminis.app.ui.chat

/**
 * [T-android-slash-with-args] Input-side parsing for the slash-command
 * interception in `sendMessage` (see ChatViewModel.tryExecuteInputAsSlashCommand).
 *
 * The old check matched the WHOLE composer text against a command title. So
 * `"/skill-name 用户自己的话"` — the exact shape the "/" picker produces when
 * the user picks a Skill over text they already typed, and the shape users
 * type by hand — never matched anything and was swallowed as
 * "未知命令", losing the message. Same for a built-in followed by arguments.
 *
 * The command is only the FIRST whitespace-delimited token after the leading
 * slash; everything after it is the user's message body. Recognising a known
 * token means "this is a chat message that happens to start with a slash
 * command", not an unknown command.
 *
 * Accepts both ASCII `/` and the full-width `／` (U+FF0F): some Chinese /
 * Japanese IMEs auto-substitute the full-width form while a CJK layout is
 * active, and the composer treats them identically elsewhere.
 */
internal object SlashInputParse {
    /**
     * Leading slash-command token, lowercased — or null when [text] does not
     * start with a slash. A bare "/" yields an empty token (null): that is a
     * panel interaction, never a command name.
     */
    fun commandToken(text: String): String? {
        val trimmed = text.trimStart()
        if (trimmed.isEmpty()) return null
        val first = trimmed[0]
        if (first != '/' && first != '／') return null
        val body = trimmed.drop(1)
        val end = body.indexOfFirst { it.isWhitespace() }
        val token = (if (end < 0) body else body.substring(0, end)).trim()
        return token.takeIf { it.isNotEmpty() }?.lowercase()
    }

    /**
     * True when [text] is exactly one slash-command token with no arguments —
     * the only shape an action command (clear / compact / thinking / memory)
     * may execute for. `"/clear please"` is a message to the model, not a
     * request to wipe the chat.
     */
    fun isBareCommand(text: String): Boolean {
        val body = text.trim().drop(1)
        return body.isNotEmpty() && body.none { it.isWhitespace() }
    }

    /**
     * The message body after a recognized slash token: the text the user
     * actually wants the model to see. For `"/skill 帮我看看"` this is
     * "帮我看看"; for a bare `"/skill"` it is empty.
     */
    fun bodyAfterCommand(text: String): String {
        val trimmed = text.trimStart()
        if (trimmed.isEmpty()) return ""
        if (trimmed[0] != '/' && trimmed[0] != '／') return trimmed
        val body = trimmed.drop(1)
        val end = body.indexOfFirst { it.isWhitespace() }
        return if (end < 0) "" else body.substring(end).trim()
    }
}
