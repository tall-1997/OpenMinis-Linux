package com.openminis.app.sandbox

/**
 * [stop=kill] Pure text/verdict contract for the user-kill marker, split out
 * of [PersistentShell] so the JVM tests can pin it without an Android
 * process. The marker is the model-facing counterpart of the coordinator's
 * NOT_STARTED_OUTPUT: one line at the HEAD of the killed command's output,
 * naming the user as the killer, then whatever the command managed to print.
 */
internal object ShellStopKill {
    const val KILLED_BY_USER_MARKER = "[killed by user]"

    /** Negative = shell death; 128+n = guest signal death. 124 is timeout's. */
    fun isSignalExit(exitCode: Int): Boolean = exitCode < 0 || exitCode >= 128

    fun markedOutput(killed: Boolean, output: String): String =
        if (killed) "$KILLED_BY_USER_MARKER\n$output" else output
}
