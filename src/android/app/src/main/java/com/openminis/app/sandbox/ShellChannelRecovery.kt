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
 * 白白丢弃一个健康的 shell（env/cwd 丢失、下次命令重建）。
 *
 * ## 第三轮复审修正：输出标记不再参与生产判定
 *
 * 上一版还接受工具自己写出的首行标记 `[Command timed out after …]` 作为
 * “拿不到标志的调用方”的兜底。这从根因上就是错的：该文本是**命令输出**，
 * 用户命令完全可以 `echo '[Command timed out after 1s]'`——于是通道被判超时、
 * 健康 shell 被丢弃，而且这条分支还会被写进工具结果的可见文本里。生产路径
 * 其实只有一个调用点（[ExecutionCoordinator.execute]），它手里始终有
 * [PersistentShell.consumeTimedOut] 的权威标志，根本不需要兜底。
 *
 * 因此标记匹配被降级为**显式开启的测试 seam**（[shouldDiscardChannel] 的
 * `allowMarkerFallback`，默认 false，生产不传）；没有调用点会为了“兼容”打开
 * 它。这是防御性的取舍：保留解析逻辑的唯一理由是回归测试可以钉住标记文本的
 * 形状，而不是让生产行为依赖可伪造的字符串。
 */
internal object ShellChannelRecovery {

    /** `timeout`-style exit code used by PersistentShell.executeCommand. */
    const val TIMEOUT_EXIT_CODE = 124

    /**
     * [GH#358] 内部超时分支输出的首行前缀。
     *
     * 仅 [shouldDiscardChannel] 的 `allowMarkerFallback = true` 测试 seam 会读
     * 它；生产路径只认 [PersistentShell.consumeTimedOut] 标志，绝不解析命令
     * 输出（用户 echo 同样文本不能丢弃健康通道）。
     */
    const val TIMEOUT_OUTPUT_PREFIX = "[Command timed out after"

    /**
     * True when the shell instance must be dropped from the per-session
     * registry so the next command builds a fresh channel.
     *
     * @param exitCode deliberately not consulted: a bare 124 from the user's
     *   command must not discard a healthy shell (the reported false
     *   positive). Kept in the signature so every caller passes the terminal
     *   exit code and nobody can silently reintroduce an `exitCode == 124`
     *   heuristic.
     * @param timedOut the shell's own timeout flag, NOT `exitCode == 124`.
     *   This is the ONLY production authority; [PersistentShell] sets it in
     *   the timeout branch and [ExecutionCoordinator] consumes it immediately.
     * @param allowMarkerFallback test-only seam (default false): when true the
     *   output prefix [TIMEOUT_OUTPUT_PREFIX] also discards. Never pass true
     *   from production — the marker is command-visible text and therefore
     *   forgeable by any user command (`echo '[Command timed out after 1s]'`).
     */
    @Suppress("UNUSED_PARAMETER") // exitCode is intentionally part of the contract, never a verdict.
    fun shouldDiscardChannel(
        exitCode: Int,
        output: String,
        timedOut: Boolean = false,
        allowMarkerFallback: Boolean = false,
    ): Boolean =
        // [GH#358] The flag is authoritative. It is set only by the shell's own
        // watchdog branch, so a user command that merely exits 124 or prints
        // the timeout text keeps its healthy channel (env/cwd survive).
        timedOut ||
            (allowMarkerFallback && output.trimStart().startsWith(TIMEOUT_OUTPUT_PREFIX))

    /** Human-readable list of the fields reset (used in the recovery log). */
    const val RESET_FIELDS =
        "pendingCallback,stdinWriter,process,isStarting,sourcedOffloadSignature,lastExitCode"
}
