package com.openminis.app.sandbox

/**
 * [T-android-parity-fixes] The single place a shell tool result is annotated
 * with its exit status.
 *
 * The coordinator writes `\n(exit code: N)` to the output, and the chat layer
 * fills in a status the coordinator left out. This object centralizes the
 * formatting so it's consistent across paths.
 */
internal object ShellExitCode {

    fun suffix(code: Int): String = "(exit code: $code)"

    /** [output] with its exit status appended, unless it already ends with it. */
    fun ensureSuffix(output: String, code: Int): String =
        if (output.trimEnd().endsWith(suffix(code))) output else "$output\n${suffix(code)}"
}
