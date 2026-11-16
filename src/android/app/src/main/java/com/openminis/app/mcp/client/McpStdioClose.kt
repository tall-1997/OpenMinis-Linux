package com.openminis.app.mcp.client

/**
 * [T-mcp-stdio-close-sequence] Decision core for closing a stdio server — port
 * of pi `mcp/src/transports/stdio.ts` (STDIN_CLOSE_GRACE_MS /
 * DEFAULT_CLOSE_TIMEOUT_MS and the close() ordering). Pure constants plus the
 * exact phase/window contract, so the sequence is testable without a process.
 *
 * pi close(): end stdin and let the server exit on its own; once the grace
 * window passes, SIGTERM; once the close timeout passes on top, SIGKILL. The
 * grace is capped by the close timeout so a tiny close timeout never extends
 * the total (`grace = Math.min(STDIN_CLOSE_GRACE_MS, closeTimeoutMs)`).
 *
 * On Android the SIGKILL branch also kills the surviving tree: a SIGKILLed
 * proot cannot run its `--kill-on-exit` teardown, so [McpStdioClient] routes
 * the forcible classification to `SandboxWorkload.release(kill = true)` — its
 * `killOwned` walks /proc for the whole descendant set and signals the process
 * group (`Os.kill(-pid, SIGKILL)`), the ExecutionCoordinator kill-tree pattern.
 */
object McpStdioClose {

    /** How long a server gets to exit on its own after stdin closes, before SIGTERM. */
    const val STDIN_CLOSE_GRACE_MS = 500L

    /** Time to wait for the server to exit after SIGTERM before SIGKILL. */
    const val DEFAULT_CLOSE_TIMEOUT_MS = 2_000L

    enum class Phase {
        /** stdin is closed; the server gets [graceMs] to exit on its own. */
        DRAIN_STDIN,

        /** Still running after the grace window: SIGTERM. */
        SIGTERM,

        /** Still running after the close timeout on top: SIGKILL (+ tree kill). */
        SIGKILL,
    }

    /** pi: `const grace = Math.min(STDIN_CLOSE_GRACE_MS, closeTimeoutMs)`. */
    fun graceMs(closeTimeoutMs: Long): Long =
        if (closeTimeoutMs < STDIN_CLOSE_GRACE_MS) closeTimeoutMs else STDIN_CLOSE_GRACE_MS

    /** How long the SIGTERM phase waits before SIGKILL (pi `closeTimeoutMs`). */
    fun sigtermWindowMs(closeTimeoutMs: Long): Long = closeTimeoutMs

    /**
     * Wall-clock SIGKILL deadline measured from the stdin close (pi schedules
     * the SIGKILL timer at `grace + closeTimeoutMs`).
     */
    fun sigkillDeadlineMs(closeTimeoutMs: Long): Long = graceMs(closeTimeoutMs) + closeTimeoutMs

    /** The exact sequence the client must run: phase → how long it may last. */
    fun sequence(closeTimeoutMs: Long): List<Pair<Phase, Long>> = listOf(
        Phase.DRAIN_STDIN to graceMs(closeTimeoutMs),
        Phase.SIGTERM to sigtermWindowMs(closeTimeoutMs),
        Phase.SIGKILL to 0L,
    )
}
