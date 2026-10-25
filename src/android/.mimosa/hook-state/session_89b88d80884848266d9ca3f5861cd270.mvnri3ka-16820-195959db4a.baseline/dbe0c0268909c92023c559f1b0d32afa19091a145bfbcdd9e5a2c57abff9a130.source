package com.openminis.app.sandbox.kernel

/**
 * Birth budget. The guest does not choose these numbers, and a caller-supplied
 * timeout does not raise them. [cpuSeconds] of 0 means no CPU-time cap
 * (a long-lived shell must not inherit a clock that accumulates across commands).
 *
 * There is deliberately no address-space field. RLIMIT_AS is the host's
 * decision: HyperOS and the memory-pressure policies clamp the hard limit on
 * the process and that clamp survives an app restart. A value here could
 * only be re-asserted by raising a hard limit, which needs privilege and
 * fails with EPERM.
 *
 * [fileSizeBytes] is bytes. `ulimit -f` wants 1024-byte blocks; [fileBlocks]
 * is the value that actually gets passed to the shell.
 */
enum class WorkClass {
    INTERACTIVE,
    NORMAL,
    BATCH,
    SERVICE,
    SETUP,
}

data class ProcessBudget(
    val workClass: WorkClass,
    val wallMs: Long,
    val cpuSeconds: Int,
    val fileSizeBytes: Long,
    val nproc: Int,
    val outputCapBytes: Long,
    val outputRateBytesPerSec: Long,
) {
    val wallSeconds: Int get() = (wallMs / 1000L).toInt().coerceAtLeast(1)
    fun fileBlocks(): Long = (fileSizeBytes / 1024L).coerceAtLeast(1L)
}
