package com.openminis.app.sandbox

/**
 * [GH#358] What to do with a per-session shell channel after one command
 * returned a terminal result.
 *
 * 中文：命令超时后必须丢弃该会话的 shell 实例并显式重置
 * pendingCallback/stdinWriter/process/isStarting 等状态，下一次命令
 * 重新建通道，不能复用被卡死的旧通道。
 *
 * Kept as its own pure policy so the regression test can pin the decision
 * without constructing a PRoot shell. Exit 124 is the BusyBox/GNU `timeout`
 * convention [PersistentShell] uses for its own timeout branch; after that the
 * channel must be discarded rather than reused, because the object may still
 * hold a cancelled callback or a blocked stdin writer.
 */
internal object ShellChannelRecovery {

    /** `timeout`-style exit code used by PersistentShell.executeCommand. */
    const val TIMEOUT_EXIT_CODE = 124

    /**
     * True when the shell instance must be dropped from the per-session
     * registry so the next command builds a fresh channel.
     */
    fun shouldDiscardChannel(exitCode: Int, output: String): Boolean =
        exitCode == TIMEOUT_EXIT_CODE ||
            // A shell death can surface as -1 from the early-not-running path;
            // it is equally unrecoverable in-place, but only the timeout path
            // may discard a shell that another queued caller has already
            // rebuilt — keep this predicate narrow.
            output.contains("[Command timed out after")

    /** Human-readable list of the fields reset (used in the recovery log). */
    const val RESET_FIELDS =
        "pendingCallback,stdinWriter,process,isStarting,sourcedOffloadSignature,lastExitCode"
}
