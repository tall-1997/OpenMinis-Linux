package com.openminis.app.sandbox

/**
 * [T-android-shell-fresh-process] Which mechanism runs a `shell_execute`.
 *
 * - [WARM_SHELL]: the long-lived pooled shell with in-band `__MINIS_DONE__`
 *   markers. Retained as the rollback path.
 * - [FRESH_PROCESS]: one `/bin/sh -c` per command, completion read from the
 *   process exit status. Matches iOS (`ISHExecutionCoordinator`).
 *
 * See [FreshProcessShell] for why the in-band marker was structurally
 * unsound (split across reads, swallowed short outputs, cross-command
 * contamination) and why a fresh process eliminates that entire class of bug.
 */
internal enum class ShellExecutionStrategy {
    WARM_SHELL,
    FRESH_PROCESS,
    ;

    companion object {
        /**
         * [FRESH_PROCESS] is the default: each command owns its own process
         * and pipes, so the marker/buffer/shared-stream defects cannot occur.
         * [WARM_SHELL] stays reachable as the rollback for a release cycle.
         */
        val DEFAULT: ShellExecutionStrategy = FRESH_PROCESS

        /** Whether a strategy needs the in-band completion marker. */
        fun needsInBandMarker(strategy: ShellExecutionStrategy): Boolean =
            strategy == WARM_SHELL

        /** Whether a timeout has to recycle the shell afterwards. */
        fun recyclesShellOnTimeout(strategy: ShellExecutionStrategy): Boolean =
            strategy == WARM_SHELL
    }
}
