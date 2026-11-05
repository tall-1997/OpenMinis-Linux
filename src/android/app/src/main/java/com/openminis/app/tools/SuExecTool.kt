package com.openminis.app.tools

/**
 * [T-android-sueexec-tool-home] Single home for the su_exec tool's command
 * rewriting. The dispatch layer in ui/chat used to inline the
 * `android-su -c <quoted>` string build; it now delegates here. Pure
 * string/JSON logic — no host I/O.
 */
object SuExecTool {
    const val NAME = "su_exec"

    /** Prefix the sandboxed shell resolves to the privileged exec helper. */
    private const val SU_PREFIX = "android-su -c "

    /**
     * Wrap [cmd] for the sandbox shell: single-quote it with the standard
     * `'\''` escape so embedded quotes, `$`, backticks and newlines stay
     * literal, then prefix with `android-su -c `. Same escaping logic the
     * UI dispatch layer used to inline — behavior unchanged, code relocated.
     */
    fun wrapSuCommand(cmd: String): String = SU_PREFIX + quoteSingle(cmd)

    /**
     * Convenience for callers rewriting a whole su_exec args JSON: replaces
     * the `command` field with [wrapSuCommand] of its current value and
     * returns the serialized payload.
     */
    fun wrapSuArgs(argsJson: String): String {
        val o = org.json.JSONObject(argsJson)
        o.put("command", wrapSuCommand(o.optString("command")))
        return o.toString()
    }

    /** POSIX single-quote escaping: close the quote, escaped quote, reopen. */
    private fun quoteSingle(cmd: String): String = "'" + cmd.replace("'", "'\\''") + "'"
}
