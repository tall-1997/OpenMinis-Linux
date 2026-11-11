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
 * without constructing a PRoot shell.
 *
 * [GH#358] 判定超时不能只看 exit code 124：124 是 BusyBox/GNU `timeout` 的
 * 通用退出码，用户自己执行 `timeout 5 sleep 10`（或任何命令显式 exit 124）
 * 也会得到 124，却完全没有卡死 shell。旧实现把这类正常命令也当成内部超时，
 * 白白丢弃一个健康的 shell（env/cwd 丢失、下次命令重建）。真正的工具超时
 * 只有两个来源：
 *  - [PersistentShell.consumeTimedOut] 的内部标志（超时分支置位、调用方消费
 *    后清除）；
 *  - 工具自己写出的输出标记 `[Command timed out after …]`（guest 超时分支
 *    的完整首行），仅作为无标志调用方的兜底。
 */
internal object ShellChannelRecovery {

    /** `timeout`-style exit code used by PersistentShell.executeCommand. */
    const val TIMEOUT_EXIT_CODE = 124

    /**
     * [GH#358] 内部超时分支输出的首行前缀。仅在拿不到
     * [PersistentShell.consumeTimedOut] 标志时才用它兜底；匹配首行而不是
     * 任意位置，避免命令输出里恰好包含这句话就误判。
     */
    const val TIMEOUT_OUTPUT_PREFIX = "[Command timed out after"

    /**
     * True when the shell instance must be dropped from the per-session
     * registry so the next command builds a fresh channel.
     *
     * @param timedOut the shell's own timeout flag, NOT `exitCode == 124`.
     *   A user command may legitimately exit 124 without wedging the channel.
     */
    fun shouldDiscardChannel(exitCode: Int, output: String, timedOut: Boolean = false): Boolean =
        // [GH#358] The flag is authoritative: only PersistentShell's own timeout
        // branch sets it. A bare 124 from the user's command must not discard a
        // healthy shell (the reported false positive).
        timedOut ||
            // Fallback for callers that only have the result text (and for the
            // regression test that pins the marker): the timeout branch returns
            // exactly this prefix.
            output.trimStart().startsWith(TIMEOUT_OUTPUT_PREFIX)

    /** Human-readable list of the fields reset (used in the recovery log). */
    const val RESET_FIELDS =
        "pendingCallback,stdinWriter,process,isStarting,sourcedOffloadSignature,lastExitCode"
}
